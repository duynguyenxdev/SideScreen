package com.sidescreen.host.server

import com.sidescreen.host.protocol.AuthToken
import com.sidescreen.host.protocol.ClientMessage
import com.sidescreen.host.protocol.ClientMessageParser
import com.sidescreen.host.protocol.HandshakeCodec
import com.sidescreen.host.protocol.HandshakeError
import com.sidescreen.host.protocol.HandshakeException
import com.sidescreen.host.protocol.HandshakeStatus
import com.sidescreen.host.protocol.HostMessages
import com.sidescreen.host.protocol.PairingCode
import com.sidescreen.host.protocol.PairingStatus
import com.sidescreen.host.protocol.Size
import com.sidescreen.host.protocol.StreamCodec
import com.sidescreen.host.video.AccessUnit
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/** What the client told us about itself during protocol startup. */
data class ClientCapabilities(
    val avcOnly: Boolean = false,
    val frameMetadata: Boolean = false,
    val desktopGeometry: Boolean = false,
    val decoderLimit: Size? = null,
    val decoderLimitTable: Map<Int, Size>? = null,
) {
    val codec: StreamCodec get() = if (avcOnly) StreamCodec.H264 else StreamCodec.HEVC
}

/** Rotation/flip as shown on the tablet. */
data class DisplayTransform(
    val rotation: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
)

interface StreamingServerListener {
    /**
     * Called during protocol startup BEFORE the display config is sent, and
     * again whenever the client's decoder limits arrive late. Returns the
     * size the stream will be encoded at — that is what the display config
     * carries. Must not block for long: kick off pipeline changes asynchronously.
     */
    fun onNegotiate(capabilities: ClientCapabilities): Size

    fun onClientConnected(
        capabilities: ClientCapabilities,
        loopback: Boolean,
        deviceName: String?,
    ) {}

    fun onClientDisconnected() {}

    fun onKeyframeRequested(force: Boolean) {}

    fun onTouch(touch: ClientMessage.Touch) {}

    fun onPairingSuccess(deviceName: String) {}

    fun onPairingCodeExhausted() {}

    fun onStats(
        fps: Double,
        mbps: Double,
    ) {}

    fun log(message: String) {}
}

class StreamingServerException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * TCP server speaking the SideScreen protocol. One streaming client at a
 * time; a newly authenticated client replaces the previous one.
 *
 * Loopback connections (USB via `adb reverse`) skip auth. Others must pass
 * the SSWA token handshake, or may redeem a one-time pairing code (SSPC)
 * for the token, and only while wireless mode is enabled.
 */
class StreamingServer(
    private val port: Int,
    private val listener: StreamingServerListener,
    parentScope: CoroutineScope,
    private val bindHost: String = "0.0.0.0",
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO)
    private var selector: SelectorManager? = null
    private var serverSocket: ServerSocket? = null

    @Volatile private var session: Session? = null

    private val sessionMutex = Mutex()

    /** Non-null enables wireless clients; they must present this token. */
    @Volatile var expectedAuthToken: ByteArray? = null

    /** Whether touch input is forwarded to the listener (ping/keyframe requests are always handled). */
    @Volatile var touchEnabled: Boolean = true

    /** Host name sent to clients that pair by code. */
    @Volatile var hostName: String = "Computer"

    @Volatile private var displayConfig: DisplayState = DisplayState(Size(1920, 1080), Size(0, 0), DisplayTransform())

    private data class DisplayState(val encoded: Size, val desktop: Size, val transform: DisplayTransform)

    // Pairing code state: validate-and-consume is one critical section, so a
    // code can never be redeemed twice and pipelined guesses can never exceed
    // the attempt budget.
    private val pairingMutex = Mutex()
    private var pairingCode: String? = null
    private var pairingFailures = 0

    val boundPort: Int? get() = (serverSocket?.localAddress as? InetSocketAddress)?.port

    val hasClient: Boolean get() = session?.ready == true

    suspend fun start() {
        val sel = SelectorManager(Dispatchers.IO)
        val socket =
            try {
                aSocket(sel).tcp().bind(bindHost, port) { reuseAddress = true }
            } catch (e: Throwable) {
                sel.close()
                throw StreamingServerException("Could not listen on port $port: ${e.message}", e)
            }
        selector = sel
        serverSocket = socket
        listener.log("TCP server listening on port ${boundPort ?: port}")
        scope.launch {
            while (isActive) {
                val client =
                    try {
                        socket.accept()
                    } catch (e: Throwable) {
                        if (isActive) listener.log("Accept failed: ${e.message}")
                        break
                    }
                launch { handleConnection(client) }
            }
        }
    }

    fun stop() {
        session?.close()
        session = null
        scope.cancel()
        serverSocket?.close()
        selector?.close()
        serverSocket = null
        selector = null
    }

    suspend fun setPairingCode(code: String?) {
        pairingMutex.withLock {
            pairingCode = code
            pairingFailures = 0
        }
    }

    /** Update what the display config advertises and push it to a connected client. */
    fun updateDisplay(
        encoded: Size = displayConfig.encoded,
        desktop: Size = displayConfig.desktop,
        transform: DisplayTransform = displayConfig.transform,
    ) {
        val changed = displayConfig != DisplayState(encoded, desktop, transform)
        displayConfig = DisplayState(encoded, desktop, transform)
        if (changed) session?.takeIf { it.ready }?.let { s -> scope.launch { s.sendDisplayConfig() } }
    }

    /** Called by the video pipeline for every encoded access unit. Never blocks. */
    fun sendFrame(
        unit: AccessUnit,
        timestampNs: Long,
    ) {
        session?.offerFrame(unit, timestampNs)
    }

    /** Forces the connected client to wait for the next keyframe (e.g. after an encoder restart). */
    fun resync() {
        session?.waitingForKeyframe = true
    }

    // --- connection handling ---------------------------------------------------------

    private suspend fun handleConnection(socket: Socket) {
        val input = socket.openReadChannel()
        val output = socket.openWriteChannel(autoFlush = false)
        val loopback = isLoopback(socket)
        try {
            if (loopback) {
                listener.log("Client connected via loopback (USB) — skipping auth")
                runSession(socket, input, output, loopback = true, deviceName = null)
                return
            }
            val token = expectedAuthToken
            if (token == null) {
                listener.log("Rejecting non-loopback client: wireless mode not active")
                return
            }
            listener.log("Client connected via LAN — running auth handshake")
            val deviceName = authenticate(input, output, token) ?: return
            runSession(socket, input, output, loopback = false, deviceName = deviceName)
        } catch (_: CancellationException) {
        } catch (e: Throwable) {
            listener.log("Connection error: ${e.message}")
        } finally {
            socket.close()
        }
    }

    /** Runs the SSWA/SSPC exchange. Returns the device name when the client may stream. */
    private suspend fun authenticate(
        input: ByteReadChannel,
        output: ByteWriteChannel,
        token: ByteArray,
    ): String? {
        val prefix = ByteArray(HandshakeCodec.FIXED_PREFIX_LENGTH)
        try {
            withTimeout(HANDSHAKE_TIMEOUT_MS) { input.readFully(prefix) }
        } catch (_: Throwable) {
            respond(output, HandshakeCodec.encodeResponse(HandshakeStatus.INVALID_MAGIC))
            return null
        }
        val pairing = HandshakeCodec.hasMagic(prefix, HandshakeCodec.PAIRING_REQUEST_MAGIC)
        if (!pairing && !HandshakeCodec.hasMagic(prefix, HandshakeCodec.REQUEST_MAGIC)) {
            respond(output, HandshakeCodec.encodeResponse(HandshakeStatus.INVALID_MAGIC))
            return null
        }
        val nameLen = HandshakeCodec.declaredNameLength(prefix)
        val full =
            nameLen?.let {
                val name = ByteArray(it)
                try {
                    withTimeout(HANDSHAKE_TIMEOUT_MS) { input.readFully(name) }
                    prefix + name
                } catch (_: Throwable) {
                    null
                }
            }

        if (pairing) {
            runPairingExchange(output, full)
            return null
        }
        if (full == null) {
            respond(output, HandshakeCodec.encodeResponse(HandshakeStatus.INVALID_NAME))
            return null
        }
        val parsed =
            try {
                HandshakeCodec.parseRequest(full)
            } catch (e: HandshakeException) {
                val status = if (e.error == HandshakeError.INVALID_NAME) HandshakeStatus.INVALID_NAME else HandshakeStatus.INVALID_MAGIC
                respond(output, HandshakeCodec.encodeResponse(status))
                return null
            }
        if (!AuthToken.validate(parsed.token, token)) {
            listener.log("Wireless auth rejected: token mismatch")
            respond(output, HandshakeCodec.encodeResponse(HandshakeStatus.INVALID_TOKEN))
            return null
        }
        listener.log("Wireless auth OK — device: ${parsed.deviceName}")
        respond(output, HandshakeCodec.encodeResponse(HandshakeStatus.OK))
        return parsed.deviceName
    }

    private sealed interface PairingOutcome {
        class Issued(val token: ByteArray) : PairingOutcome

        data object Rejected : PairingOutcome

        data object Exhausted : PairingOutcome
    }

    /** Pairing connections never stream: they get one reply and are closed. */
    private suspend fun runPairingExchange(
        output: ByteWriteChannel,
        full: ByteArray?,
    ) {
        val request =
            full?.let {
                try {
                    HandshakeCodec.parsePairingRequest(it)
                } catch (_: HandshakeException) {
                    null
                }
            }
        if (request == null) {
            // Malformed requests carry no code guess, so they don't touch the attempt budget.
            respond(output, HandshakeCodec.encodePairingResponse(PairingStatus.INVALID_CODE))
            return
        }
        val outcome =
            pairingMutex.withLock {
                val expected = pairingCode
                val token = expectedAuthToken
                when {
                    expected == null || token == null -> PairingOutcome.Rejected
                    PairingCode.validate(request.code, expected) -> {
                        pairingCode = null
                        pairingFailures = 0
                        PairingOutcome.Issued(token)
                    }
                    else -> {
                        pairingFailures++
                        if (pairingFailures >= MAX_PAIRING_ATTEMPTS) {
                            pairingCode = null
                            pairingFailures = 0
                            PairingOutcome.Exhausted
                        } else {
                            PairingOutcome.Rejected
                        }
                    }
                }
            }
        when (outcome) {
            is PairingOutcome.Issued -> {
                listener.log("Pairing code accepted — issuing token to ${request.deviceName}")
                respond(output, HandshakeCodec.encodePairingResponse(PairingStatus.OK, outcome.token, hostName))
                listener.onPairingSuccess(request.deviceName)
            }
            PairingOutcome.Rejected -> {
                listener.log("Pairing code rejected")
                respond(output, HandshakeCodec.encodePairingResponse(PairingStatus.INVALID_CODE))
            }
            PairingOutcome.Exhausted -> {
                listener.log("Pairing attempt budget exhausted — invalidating code")
                respond(output, HandshakeCodec.encodePairingResponse(PairingStatus.INVALID_CODE))
                listener.onPairingCodeExhausted()
            }
        }
    }

    private suspend fun respond(
        output: ByteWriteChannel,
        bytes: ByteArray,
    ) {
        try {
            output.writeFully(bytes)
            output.flush()
        } catch (_: Throwable) {
        }
    }

    private suspend fun runSession(
        socket: Socket,
        input: ByteReadChannel,
        output: ByteWriteChannel,
        loopback: Boolean,
        deviceName: String?,
    ) {
        val s = Session(socket, output, loopback, deviceName)
        sessionMutex.withLock {
            session?.let {
                listener.log("Replacing previous client")
                it.close()
            }
            session = s
        }
        try {
            s.run(input)
        } finally {
            val wasCurrent =
                sessionMutex.withLock {
                    (session === s).also { if (it) session = null }
                }
            s.close()
            if (wasCurrent) listener.onClientDisconnected()
        }
    }

    private inner class Session(
        private val socket: Socket,
        private val output: ByteWriteChannel,
        private val loopback: Boolean,
        private val deviceName: String?,
    ) {
        private val writeMutex = Mutex()
        private val startupMutex = Mutex()
        private val frames = Channel<Pair<AccessUnit, Long>>(FRAME_QUEUE_CAPACITY)
        private var capabilities = ClientCapabilities()

        @Volatile var ready = false
            private set

        @Volatile var waitingForKeyframe = true

        @Volatile private var closed = false
        private var startupDone = false

        // Stats (frame writer coroutine only)
        private var statsBytes = 0L
        private var statsFrames = 0
        private var statsDropped = 0
        private var statsMark = TimeSource.Monotonic.markNow()

        suspend fun run(input: ByteReadChannel) {
            val parser = ClientMessageParser(listener::log)
            val sessionScope = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))
            try {
                // Give new clients a short window to opt in before the first frame;
                // legacy clients send no capability message.
                sessionScope.launch {
                    delay(STARTUP_GRACE_MS)
                    finishStartup()
                }
                sessionScope.launch { writeFrames() }
                val buf = ByteArray(4096)
                while (!closed) {
                    val n = input.readAvailable(buf, 0, buf.size)
                    if (n < 0) break
                    if (n == 0) continue
                    for (msg in parser.feed(buf, 0, n)) handle(msg)
                }
            } finally {
                sessionScope.cancel()
            }
        }

        private suspend fun handle(msg: ClientMessage) {
            when (msg) {
                is ClientMessage.Touch -> if (touchEnabled && ready) listener.onTouch(msg)
                is ClientMessage.Ping -> write(HostMessages.pong(msg.timestamp))
                is ClientMessage.KeyframeRequest -> listener.onKeyframeRequested(msg.force)
                ClientMessage.SupportsFrameMetadata -> {
                    capabilities = capabilities.copy(frameMetadata = true)
                    finishStartup()
                }
                ClientMessage.AvcOnly -> {
                    if (!capabilities.avcOnly) listener.log("Client is AVC-only — will negotiate H.264")
                    capabilities = capabilities.copy(avcOnly = true)
                }
                is ClientMessage.DecoderLimits -> {
                    listener.log("Client decoder limit: ${msg.limit}")
                    capabilities = capabilities.copy(decoderLimit = msg.limit)
                    if (startupDone) renegotiate()
                }
                is ClientMessage.DecoderLimitTable -> {
                    listener.log("Client decoder limit table: ${msg.table.entries.joinToString { "${it.key}fps=${it.value}" }}")
                    capabilities = capabilities.copy(decoderLimitTable = msg.table)
                    if (startupDone) renegotiate()
                }
                ClientMessage.SupportsDesktopGeometry -> {
                    val late = !capabilities.desktopGeometry && startupDone
                    capabilities = capabilities.copy(desktopGeometry = true)
                    if (late) sendDesktopGeometry()
                }
            }
        }

        private suspend fun finishStartup() {
            startupMutex.withLock {
                if (startupDone || closed) return
                startupDone = true
            }
            val caps = capabilities
            if (caps.avcOnly) {
                // Safe to send: this client opted in via type 9. Must precede the
                // display config so the client knows the codec before sizing its decoder.
                write(HostMessages.codecSelected(StreamCodec.H264))
            }
            val encoded = listener.onNegotiate(caps)
            displayConfig = displayConfig.copy(encoded = encoded)
            sendDisplayConfig()
            waitingForKeyframe = true
            ready = true
            listener.log("Connection ready (metadata=${caps.frameMetadata}, codec=${caps.codec}, encode=$encoded)")
            listener.onClientConnected(caps, loopback, deviceName)
        }

        private suspend fun renegotiate() {
            listener.log("Decoder limit arrived after startup — re-negotiating")
            val before = displayConfig.encoded
            val encoded = listener.onNegotiate(capabilities)
            if (encoded != before) {
                displayConfig = displayConfig.copy(encoded = encoded)
                waitingForKeyframe = true
                sendDisplayConfig()
            }
        }

        suspend fun sendDisplayConfig() {
            val cfg = displayConfig
            write(HostMessages.displayConfig(cfg.encoded, cfg.transform.rotation, cfg.transform.flipHorizontal, cfg.transform.flipVertical))
            listener.log("Sent display config: ${cfg.encoded} @ ${cfg.transform.rotation}°")
            sendDesktopGeometry()
        }

        private suspend fun sendDesktopGeometry() {
            val desktop = displayConfig.desktop
            if (!capabilities.desktopGeometry || desktop.width <= 0 || desktop.height <= 0) return
            write(HostMessages.desktopGeometry(desktop))
        }

        fun offerFrame(
            unit: AccessUnit,
            timestampNs: Long,
        ) {
            if (!ready || closed) return
            // A fresh client (or one that lost frames) must restart on a keyframe:
            // P-frames before it would feed garbage to the decoder.
            if (waitingForKeyframe) {
                if (!unit.isKeyframe) return
                waitingForKeyframe = false
            }
            if (frames.trySend(unit to timestampNs).isFailure) {
                // Link can't keep up: drop, and resume at the next keyframe.
                statsDropped++
                waitingForKeyframe = true
                listener.onKeyframeRequested(true)
            }
        }

        private suspend fun writeFrames() {
            for ((unit, ts) in frames) {
                val header = HostMessages.frameHeader(unit.data.size, unit.isKeyframe, ts, capabilities.frameMetadata)
                writeMutex.withLock {
                    output.writeFully(header)
                    output.writeFully(unit.data)
                    output.flush()
                }
                statsBytes += unit.data.size
                statsFrames++
                val elapsed = statsMark.elapsedNow().inWholeMilliseconds
                if (elapsed >= 1000) {
                    val secs = elapsed / 1000.0
                    listener.onStats(statsFrames / secs, statsBytes * 8 / secs / 1_000_000)
                    if (statsDropped > 0) listener.log("Dropped $statsDropped frames (send queue full)")
                    statsBytes = 0
                    statsFrames = 0
                    statsDropped = 0
                    statsMark = TimeSource.Monotonic.markNow()
                }
            }
        }

        private suspend fun write(bytes: ByteArray) {
            if (closed) return
            writeMutex.withLock {
                output.writeFully(bytes)
                output.flush()
            }
        }

        fun close() {
            if (closed) return
            closed = true
            ready = false
            frames.close()
            socket.close()
        }
    }

    private fun isLoopback(socket: Socket): Boolean {
        val addr = socket.remoteAddress as? InetSocketAddress ?: return false
        val bytes =
            try {
                addr.resolveAddress()
            } catch (_: Throwable) {
                null
            } ?: return addr.hostname == "localhost"
        return when (bytes.size) {
            4 -> bytes[0] == 127.toByte()
            16 -> {
                val v6Loopback = (0 until 15).all { bytes[it] == 0.toByte() } && bytes[15] == 1.toByte()
                val mapped = (0 until 10).all { bytes[it] == 0.toByte() } && bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
                v6Loopback || (mapped && bytes[12] == 127.toByte())
            }
            else -> false
        }
    }

    companion object {
        const val DEFAULT_PORT = 54321
        private const val STARTUP_GRACE_MS = 100L
        private const val HANDSHAKE_TIMEOUT_MS = 5_000L
        private const val MAX_PAIRING_ATTEMPTS = 5
        private const val FRAME_QUEUE_CAPACITY = 8
    }
}
