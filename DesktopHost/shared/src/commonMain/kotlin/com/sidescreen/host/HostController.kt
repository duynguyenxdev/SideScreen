package com.sidescreen.host

import com.sidescreen.host.input.GestureRecognizer
import com.sidescreen.host.platform.DisplayProvider
import com.sidescreen.host.platform.DisplayRequest
import com.sidescreen.host.platform.EncoderConfig
import com.sidescreen.host.platform.FrameSink
import com.sidescreen.host.platform.HostPlatform
import com.sidescreen.host.platform.InputInjector
import com.sidescreen.host.platform.ManagedDisplay
import com.sidescreen.host.platform.UsbStatus
import com.sidescreen.host.platform.VideoPipeline
import com.sidescreen.host.platform.currentTimeMillis
import com.sidescreen.host.platform.monotonicNanos
import com.sidescreen.host.protocol.AuthToken
import com.sidescreen.host.protocol.ClientMessage
import com.sidescreen.host.protocol.CodecLimits
import com.sidescreen.host.protocol.PairingCode
import com.sidescreen.host.protocol.PairingUrl
import com.sidescreen.host.protocol.Size
import com.sidescreen.host.protocol.StreamCodec
import com.sidescreen.host.server.ClientCapabilities
import com.sidescreen.host.server.DisplayTransform
import com.sidescreen.host.server.StreamingServer
import com.sidescreen.host.server.StreamingServerListener
import com.sidescreen.host.settings.ConnectionMode
import com.sidescreen.host.settings.HostSettings
import com.sidescreen.host.settings.PairedDevice
import com.sidescreen.host.settings.PairedDeviceRegistry
import com.sidescreen.host.video.AccessUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

data class HostState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val clientConnected: Boolean = false,
    val clientName: String? = null,
    val fps: Double = 0.0,
    val mbps: Double = 0.0,
    val displayDescription: String? = null,
    val pipelineDescription: String? = null,
    val codec: StreamCodec? = null,
    val encodeSize: Size? = null,
    val usbStatus: UsbStatus? = null,
    val lanAddress: String? = null,
    val pairingCode: String? = null,
    val pairingUrl: String? = null,
    val inputProblem: String? = null,
    val error: String? = null,
)

/**
 * Orchestrates a streaming session: display → capture/encode pipeline →
 * server → client, and client touches → gestures → injected input.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostController(
    private val platform: HostPlatform,
    parentScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())
    private val lifecycle = Mutex()
    private val pipelineLock = Mutex()

    /** Gestures and injection run on one thread, in order. */
    private val inputDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val inputScope = CoroutineScope(scope.coroutineContext + inputDispatcher)

    private val _settings = MutableStateFlow(platform.store.loadSettings())
    val settings: StateFlow<HostSettings> = _settings.asStateFlow()

    private val _state = MutableStateFlow(HostState())
    val state: StateFlow<HostState> = _state.asStateFlow()

    val pairedDevices = PairedDeviceRegistry(platform.store)
    private val _paired = MutableStateFlow(pairedDevices.all())
    val paired: StateFlow<List<PairedDevice>> = _paired.asStateFlow()

    private var display: ManagedDisplay? = null
    private var server: StreamingServer? = null
    private var pipeline: VideoPipeline? = null
    private var pipelineConfig: EncoderConfig? = null
    private var injector: InputInjector? = null
    private var gestures: GestureRecognizer? = null

    @Volatile private var pipelineGeneration = 0

    fun displayProviders(): List<DisplayProvider> = platform.displayProviders()

    fun availableEncoders(): List<String> = platform.pipelines.availableEncoders()

    val supportsHiDPI: Boolean get() = platform.supportsHiDPI

    fun updateSettings(transform: (HostSettings) -> HostSettings) {
        val before = _settings.value
        val after = transform(before)
        if (after == before) return
        _settings.value = after
        platform.store.saveSettings(after)
        server?.touchEnabled = after.touchEnabled
        if (!_state.value.running) return

        val needsRestart =
            after.resolution != before.resolution || after.refreshRate != before.refreshRate ||
                after.hiDPI != before.hiDPI || after.port != before.port ||
                after.connectionMode != before.connectionMode || after.displayTarget != before.displayTarget ||
                after.gamingBoost != before.gamingBoost ||
                ((after.rotation == 90 || after.rotation == 270) != (before.rotation == 90 || before.rotation == 270))
        when {
            needsRestart -> scope.launch { restart() }
            after.rotation != before.rotation || after.flipHorizontal != before.flipHorizontal ||
                after.flipVertical != before.flipVertical ->
                server?.updateDisplay(transform = after.transform())
            after.bitrateMbps != before.bitrateMbps || after.quality != before.quality ||
                after.encoder != before.encoder || after.keyframeIntervalSeconds != before.keyframeIntervalSeconds ||
                after.ffmpegPath != before.ffmpegPath ->
                scope.launch { pipelineConfig?.let { restartPipeline(it.codec, it.size) } }
        }
    }

    fun start() {
        scope.launch { startInternal() }
    }

    fun stop() {
        scope.launch { lifecycle.withLock { stopLocked() } }
    }

    suspend fun restart() {
        lifecycle.withLock { stopLocked() }
        startInternal()
    }

    fun shutdown() {
        // Called on app exit: tear down synchronously-enough that the virtual display disappears.
        pipeline?.stop()
        server?.stop()
        display?.close()
        injector?.close()
    }

    fun resetAuthToken() {
        val fresh = AuthToken.generate()
        platform.store.saveAuthToken(fresh)
        pairedDevices.clear()
        _paired.value = emptyList()
        server?.expectedAuthToken = fresh
        refreshPairingUrl(fresh)
    }

    fun forgetDevice(name: String) {
        pairedDevices.forget(name)
        _paired.value = pairedDevices.all()
    }

    private suspend fun startInternal() =
        lifecycle.withLock {
            if (_state.value.running || _state.value.starting) return
            val s = _settings.value
            _state.value = HostState(starting = true)
            try {
                val provider =
                    platform.displayProviders().firstOrNull { it.id == s.displayTarget }
                        ?: platform.displayProviders().first()
                provider.unavailableReason()?.let { throw IllegalStateException("${provider.label}: $it") }

                val desktop = s.resolutionSize
                val usb =
                    if (s.connectionMode == ConnectionMode.USB) scope.async { platform.setupUsbReverse(s.port) } else null
                val d = provider.open(DisplayRequest(desktop.width, desktop.height, s.effectiveRefreshRate, s.hiDPI && platform.supportsHiDPI))
                display = d
                platform.log("Display: ${d.description}, capture ${d.captureSize()}, bounds ${d.inputBounds()}")

                val inj = platform.createInputInjector()
                injector = inj
                gestures = GestureRecognizer(inj, inputScope, ::monotonicNanos)

                val srv = StreamingServer(s.port, Listener(), scope)
                srv.touchEnabled = s.touchEnabled
                srv.hostName = platform.hostName
                var token: ByteArray? = null
                if (s.connectionMode == ConnectionMode.WIRELESS) {
                    token = platform.store.loadAuthToken()?.takeIf { it.size == AuthToken.LENGTH }
                        ?: AuthToken.generate().also { platform.store.saveAuthToken(it) }
                    srv.expectedAuthToken = token
                }
                val initial = CodecLimits.encodeSize(d.captureSize(), StreamCodec.HEVC, null, null, s.effectiveRefreshRate)
                srv.updateDisplay(encoded = initial, desktop = d.desktopSize, transform = s.transform())
                srv.start()
                server = srv

                restartPipeline(StreamCodec.HEVC, initial)

                val lan = platform.lanAddress()
                _state.update {
                    it.copy(
                        running = true,
                        starting = false,
                        displayDescription = d.description,
                        lanAddress = lan,
                        usbStatus = usb?.await(),
                        inputProblem = inj.permissionProblem(),
                    )
                }
                if (token != null) {
                    refreshPairingUrl(token)
                    rotatePairingCode()
                }
                platform.log("Server started on port ${s.port} (${s.connectionMode})")
            } catch (e: Throwable) {
                platform.log("Failed to start: ${e.message}")
                stopLocked()
                _state.value = HostState(error = e.message ?: e.toString())
            }
        }

    private fun stopLocked() {
        pipelineGeneration++
        pipeline?.stop()
        pipeline = null
        pipelineConfig = null
        gestures?.cancel()
        gestures = null
        server?.stop()
        server = null
        injector?.close()
        injector = null
        display?.close()
        display = null
        val error = _state.value.error
        _state.value = HostState(error = error)
    }

    private suspend fun restartPipeline(
        codec: StreamCodec,
        size: Size,
    ) = pipelineLock.withLock {
        val d = display ?: return@withLock
        val srv = server ?: return@withLock
        val s = _settings.value
        val config =
            EncoderConfig(
                codec = codec,
                size = size,
                fps = s.effectiveRefreshRate,
                bitrateMbps = s.effectiveBitrateMbps,
                quality = s.effectiveQuality,
                keyframeIntervalSeconds = s.keyframeIntervalSeconds,
                encoder = s.encoder,
            )
        val generation = ++pipelineGeneration
        pipeline?.stop()
        srv.resync()
        val sink =
            FrameSink { unit: AccessUnit, ts: Long ->
                // Frames still draining from a replaced pipeline carry the old size or codec.
                if (generation == pipelineGeneration) srv.sendFrame(unit, ts)
            }
        try {
            val p =
                platform.pipelines.start(d, config, sink) { reason ->
                    if (generation != pipelineGeneration) return@start
                    platform.log("Video pipeline stopped: $reason")
                    _state.update { it.copy(error = "Video pipeline stopped: $reason") }
                }
            pipeline = p
            pipelineConfig = config
            _state.update { it.copy(pipelineDescription = p.description, codec = codec, encodeSize = size, error = null) }
        } catch (e: Throwable) {
            pipeline = null
            platform.log("Could not start video pipeline: ${e.message}")
            _state.update { it.copy(error = "Could not start video pipeline: ${e.message}") }
        }
    }

    private fun refreshPairingUrl(token: ByteArray) {
        val host = _state.value.lanAddress ?: platform.lanAddress() ?: return
        val url = PairingUrl.build(host, _settings.value.port, token, platform.hostName)
        _state.update { it.copy(pairingUrl = url) }
    }

    private suspend fun rotatePairingCode() {
        val srv = server ?: return
        val code = PairingCode.generate()
        srv.setPairingCode(code)
        _state.update { if (it.running) it.copy(pairingCode = code) else it }
    }

    private fun HostSettings.transform() = DisplayTransform(rotation, flipHorizontal, flipVertical)

    private inner class Listener : StreamingServerListener {
        override fun onNegotiate(capabilities: ClientCapabilities): Size {
            val d = display ?: return Size(1920, 1080)
            val fps = _settings.value.effectiveRefreshRate
            val size =
                CodecLimits.encodeSize(d.captureSize(), capabilities.codec, capabilities.decoderLimit, capabilities.decoderLimitTable, fps)
            val current = pipelineConfig
            if (current == null || current.codec != capabilities.codec || current.size != size) {
                platform.log("Negotiated ${capabilities.codec} at $size (client limit ${capabilities.decoderLimit}, table ${capabilities.decoderLimitTable})")
                // Set synchronously so a second negotiation in a row compares against it.
                pipelineConfig = current?.copy(codec = capabilities.codec, size = size)
                scope.launch { restartPipeline(capabilities.codec, size) }
            }
            return size
        }

        override fun onClientConnected(
            capabilities: ClientCapabilities,
            loopback: Boolean,
            deviceName: String?,
        ) {
            if (deviceName != null) {
                pairedDevices.upsert(deviceName, currentTimeMillis())
                _paired.value = pairedDevices.all()
            }
            _state.update {
                it.copy(clientConnected = true, clientName = deviceName ?: if (loopback) "USB" else null, inputProblem = injector?.permissionProblem())
            }
        }

        override fun onClientDisconnected() {
            _state.value.clientName?.takeIf { it != "USB" }?.let {
                pairedDevices.upsert(it, currentTimeMillis())
                _paired.value = pairedDevices.all()
            }
            inputScope.launch { gestures?.cancel() }
            _state.update { it.copy(clientConnected = false, clientName = null, fps = 0.0, mbps = 0.0) }
        }

        override fun onTouch(touch: ClientMessage.Touch) {
            val d = display ?: return
            inputScope.launch {
                val g = gestures ?: return@launch
                val bounds = d.inputBounds()
                g.onTouch(bounds.map(norm(touch.x1), norm(touch.y1)), touch.action, touch.pointerCount, bounds.map(norm(touch.x2), norm(touch.y2)))
            }
        }

        private fun norm(v: Float) = if (v.isNaN()) 0f else v.coerceIn(0f, 1f)

        override fun onKeyframeRequested(force: Boolean) {
            // Encoders emit a keyframe every keyframeIntervalSeconds; the server
            // already holds the client until one arrives.
        }

        override fun onPairingSuccess(deviceName: String) {
            pairedDevices.upsert(deviceName, currentTimeMillis())
            _paired.value = pairedDevices.all()
            scope.launch { rotatePairingCode() }
        }

        override fun onPairingCodeExhausted() {
            scope.launch { rotatePairingCode() }
        }

        override fun onStats(
            fps: Double,
            mbps: Double,
        ) {
            _state.update { it.copy(fps = fps, mbps = mbps) }
        }

        override fun log(message: String) = platform.log(message)
    }
}
