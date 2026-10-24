package com.sidescreen.host.jvm.video

import com.sidescreen.host.jvm.Os
import com.sidescreen.host.jvm.Tools
import com.sidescreen.host.jvm.currentOs
import com.sidescreen.host.platform.EncoderConfig
import com.sidescreen.host.platform.FrameSink
import com.sidescreen.host.platform.ManagedDisplay
import com.sidescreen.host.platform.VideoPipeline
import com.sidescreen.host.platform.VideoPipelineFactory
import com.sidescreen.host.protocol.StreamCodec
import com.sidescreen.host.settings.Quality
import com.sidescreen.host.video.AnnexBFramer
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.LockSupport
import kotlin.math.max
import kotlin.math.roundToInt

/** A display ffmpeg can capture. */
interface FfmpegCaptureSource {
    /** Global options that must precede the input (e.g. hardware device setup). */
    fun ffmpegPreInputArgs(): List<String> = emptyList()

    /** Arguments opening the capture input, ending with `-i <url>`. */
    fun ffmpegInputArgs(fps: Int): List<String>
}

/** How one ffmpeg encoder is driven. */
internal data class EncoderSpec(
    val name: String,
    val codec: StreamCodec,
    val hardware: Boolean,
    /** Upload step appended to the filter chain (hardware frames). */
    val upload: String? = null,
    val preInput: List<String> = emptyList(),
    val options: (Quality) -> List<String>,
)

internal object Encoders {
    private fun vt(
        name: String,
        codec: StreamCodec,
    ) = EncoderSpec(name, codec, hardware = true) { q ->
        listOf("-realtime", "1", "-prio_speed", if (q <= Quality.LOW) "1" else "0", "-profile:v", "main")
    }

    private fun nvenc(
        name: String,
        codec: StreamCodec,
    ) = EncoderSpec(name, codec, hardware = true) { q ->
        val preset = mapOf(Quality.ULTRALOW to "p1", Quality.LOW to "p2", Quality.MEDIUM to "p4", Quality.HIGH to "p5").getValue(q)
        listOf("-preset", preset, "-tune", "ull", "-zerolatency", "1", "-delay", "0", "-rc", "vbr", "-profile:v", "main")
    }

    private fun qsv(
        name: String,
        codec: StreamCodec,
    ) = EncoderSpec(name, codec, hardware = true) { q ->
        val preset = mapOf(Quality.ULTRALOW to "veryfast", Quality.LOW to "faster", Quality.MEDIUM to "medium", Quality.HIGH to "slow").getValue(q)
        listOf("-preset", preset, "-look_ahead", "0", "-async_depth", "1", "-profile:v", "main")
    }

    private fun amf(
        name: String,
        codec: StreamCodec,
    ) = EncoderSpec(name, codec, hardware = true) { q ->
        val quality = mapOf(Quality.ULTRALOW to "speed", Quality.LOW to "speed", Quality.MEDIUM to "balanced", Quality.HIGH to "quality").getValue(q)
        listOf("-usage", "ultralowlatency", "-quality", quality, "-rc", "vbr_peak")
    }

    private fun vaapi(
        name: String,
        codec: StreamCodec,
    ) = EncoderSpec(
        name,
        codec,
        hardware = true,
        upload = "hwupload",
        preInput = listOf("-vaapi_device", System.getenv("SIDESCREEN_VAAPI_DEVICE") ?: "/dev/dri/renderD128"),
    ) { _ -> listOf("-profile:v", "main") }

    private fun x265() =
        EncoderSpec("libx265", StreamCodec.HEVC, hardware = false) { q ->
            val preset = mapOf(Quality.ULTRALOW to "ultrafast", Quality.LOW to "superfast", Quality.MEDIUM to "veryfast", Quality.HIGH to "faster").getValue(q)
            listOf("-preset", preset, "-tune", "zerolatency", "-x265-params", "log-level=error:open-gop=0:bframes=0")
        }

    private fun x264() =
        EncoderSpec("libx264", StreamCodec.H264, hardware = false) { q ->
            val preset = mapOf(Quality.ULTRALOW to "ultrafast", Quality.LOW to "superfast", Quality.MEDIUM to "veryfast", Quality.HIGH to "faster").getValue(q)
            listOf("-preset", preset, "-tune", "zerolatency", "-profile:v", "main")
        }

    /** Preference order per OS: hardware first, software last. */
    fun candidates(codec: StreamCodec): List<EncoderSpec> {
        val prefix = if (codec == StreamCodec.HEVC) "hevc" else "h264"
        val hw =
            when (currentOs) {
                Os.MAC -> listOf(vt("${prefix}_videotoolbox", codec))
                Os.WINDOWS -> listOf(nvenc("${prefix}_nvenc", codec), qsv("${prefix}_qsv", codec), amf("${prefix}_amf", codec))
                Os.LINUX -> listOf(nvenc("${prefix}_nvenc", codec), vaapi("${prefix}_vaapi", codec), qsv("${prefix}_qsv", codec))
            }
        return hw + if (codec == StreamCodec.HEVC) x265() else x264()
    }
}

class FfmpegPipelineFactory(
    private val ffmpegPath: () -> String?,
    private val log: (String) -> Unit,
) : VideoPipelineFactory {
    /** encoder name → works on this machine. Probing a hardware encoder opens a real session, so cache it. */
    private val probed = ConcurrentHashMap<String, Boolean>()

    override fun availableEncoders(): List<String> {
        val ffmpeg = ffmpegPath() ?: return emptyList()
        return (Encoders.candidates(StreamCodec.HEVC) + Encoders.candidates(StreamCodec.H264))
            .filter { works(ffmpeg, it) }
            .map { it.name }
    }

    /** Probe every candidate in the background so the first start is quick. */
    fun warmUp() {
        Thread({ runCatching { availableEncoders() } }, "encoder-probe").apply { isDaemon = true }.start()
    }

    private fun works(
        ffmpeg: String,
        spec: EncoderSpec,
    ): Boolean =
        probed.getOrPut(spec.name) {
            val args =
                listOf(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin") + spec.preInput +
                    listOf("-f", "lavfi", "-i", "testsrc2=s=640x360:r=30", "-frames:v", "5", "-vf", filterChain(640, 360, spec)) +
                    listOf("-c:v", spec.name) + spec.options(Quality.ULTRALOW) + listOf("-b:v", "5M", "-f", "null", "-")
            val ok = Tools.run(args, timeoutSeconds = 15)?.exitCode == 0
            log("Encoder probe ${spec.name}: ${if (ok) "available" else "unavailable"}")
            ok
        }

    private fun choose(
        ffmpeg: String,
        config: EncoderConfig,
    ): EncoderSpec {
        val candidates = Encoders.candidates(config.codec)
        if (config.encoder != "auto") {
            candidates.firstOrNull { it.name == config.encoder }?.let { return it }
            log("Encoder ${config.encoder} cannot produce ${config.codec}; choosing automatically")
        }
        return candidates.firstOrNull { works(ffmpeg, it) }
            ?: throw IllegalStateException("No working ${config.codec} encoder found in ffmpeg ($ffmpeg)")
    }

    override fun start(
        display: ManagedDisplay,
        config: EncoderConfig,
        sink: FrameSink,
        onFailure: (String) -> Unit,
    ): VideoPipeline {
        val source = display as? FfmpegCaptureSource ?: throw IllegalArgumentException("${display.description} cannot be captured with ffmpeg")
        val ffmpeg = ffmpegPath() ?: throw IllegalStateException("ffmpeg not found — install it or set its path in Advanced settings")
        val spec = choose(ffmpeg, config)
        val w = config.size.width and 1.inv()
        val h = config.size.height and 1.inv()
        val gop = max(1, (config.fps * config.keyframeIntervalSeconds).roundToInt())
        val bitrate = "${config.bitrateMbps}M"
        // Roughly two frames of buffer: enough to absorb a keyframe, small enough to stay low-latency.
        val bufsize = "${max(1, config.bitrateMbps * 2 / config.fps)}M"
        val format = if (config.codec == StreamCodec.HEVC) "hevc" else "h264"

        val command =
            buildList {
                addAll(listOf(ffmpeg, "-hide_banner", "-loglevel", "warning", "-nostdin"))
                addAll(spec.preInput)
                addAll(source.ffmpegPreInputArgs())
                addAll(listOf("-fflags", "nobuffer", "-probesize", "32", "-analyzeduration", "0"))
                addAll(source.ffmpegInputArgs(config.fps))
                addAll(listOf("-an", "-sn", "-vf", filterChain(w, h, spec), "-c:v", spec.name))
                addAll(spec.options(config.quality))
                addAll(listOf("-g", "$gop", "-keyint_min", "$gop", "-bf", "0"))
                addAll(listOf("-b:v", bitrate, "-maxrate", bitrate, "-bufsize", bufsize))
                addAll(listOf("-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-color_range", "tv"))
                // Parameter sets travel out of band, then dump_extra puts them in front of
                // every keyframe — the same for every encoder.
                addAll(listOf("-flags", "+global_header", "-bsf:v", "dump_extra=freq=keyframe"))
                addAll(listOf("-flush_packets", "1", "-avioflags", "direct", "-f", format, "pipe:1"))
            }
        val description = "${spec.name}${if (spec.hardware) "" else " (software)"} ${w}x$h@${config.fps}, ${config.bitrateMbps} Mbps"
        log("Starting ffmpeg: ${command.joinToString(" ")}")
        return FfmpegProcess(command, config.codec, description, sink, onFailure, log)
    }

    companion object {
        /** Scale + BT.709 limited-range conversion, so every vendor's decoder shows the same colours (#55). */
        internal fun filterChain(
            w: Int,
            h: Int,
            spec: EncoderSpec,
        ): String {
            val base = "scale=$w:$h:flags=bilinear:out_color_matrix=bt709:out_range=tv,format=nv12"
            return spec.upload?.let { "$base,$it" } ?: base
        }
    }
}

private class FfmpegProcess(
    command: List<String>,
    codec: StreamCodec,
    override val description: String,
    private val sink: FrameSink,
    private val onFailure: (String) -> Unit,
    private val log: (String) -> Unit,
) : VideoPipeline {
    private val process: Process = ProcessBuilder(command).start()

    @Volatile private var stopping = false
    private val framer = AnnexBFramer(codec)
    private val recentErrors = ArrayDeque<String>()

    init {
        process.outputStream.close()
        Thread({ pumpStderr() }, "ffmpeg-stderr").apply { isDaemon = true }.start()
        Thread({ pumpFrames(process.inputStream) }, "ffmpeg-frames").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }.start()
    }

    private fun pumpFrames(input: InputStream) {
        val buf = ByteArray(1 shl 20)
        var lastData = System.nanoTime()
        var waitForKeyframe = false
        fun deliver(units: List<com.sidescreen.host.video.AccessUnit>) {
            if (framer.takeDesync()) {
                log("Encoder output stalled mid-frame; holding until the next keyframe")
                waitForKeyframe = true
            }
            val now = System.nanoTime()
            for (u in units) {
                if (waitForKeyframe && !u.isKeyframe) continue
                waitForKeyframe = false
                sink.onAccessUnit(u, now)
            }
        }
        try {
            while (!stopping) {
                val available = input.available()
                if (available > 0 || !framer.hasPendingPicture) {
                    // Nothing in flight (or bytes ready): a blocking read is free.
                    val n = input.read(buf, 0, if (available > 0) minOf(available, buf.size) else buf.size)
                    if (n < 0) break
                    deliver(framer.push(buf, 0, n))
                    lastData = System.nanoTime()
                } else if (System.nanoTime() - lastData >= IDLE_FLUSH_NS) {
                    // ffmpeg writes each packet in one burst: a pause means the picture is complete.
                    deliver(framer.flushIdle())
                } else {
                    LockSupport.parkNanos(POLL_NS)
                }
            }
        } catch (e: Exception) {
            if (!stopping) log("ffmpeg read error: ${e.message}")
        }
        if (!stopping) {
            val code = runCatching { process.waitFor() }.getOrNull()
            val detail = synchronized(recentErrors) { recentErrors.joinToString(" | ") }
            onFailure("ffmpeg exited (code $code)${if (detail.isNotEmpty()) ": $detail" else ""}")
        }
    }

    private fun pumpStderr() {
        try {
            process.errorStream.bufferedReader().forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                synchronized(recentErrors) {
                    recentErrors.addLast(line.trim())
                    while (recentErrors.size > 5) recentErrors.removeFirst()
                }
                log("ffmpeg: $line")
            }
        } catch (_: Exception) {
        }
    }

    override fun stop() {
        stopping = true
        process.destroy()
        if (!runCatching { process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false)) {
            process.destroyForcibly()
        }
    }

    companion object {
        private const val IDLE_FLUSH_NS = 1_500_000L
        private const val POLL_NS = 200_000L
    }
}
