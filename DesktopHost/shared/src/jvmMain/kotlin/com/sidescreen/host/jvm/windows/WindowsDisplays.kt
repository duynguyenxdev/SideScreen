package com.sidescreen.host.jvm.windows

import com.sidescreen.host.input.Rect
import com.sidescreen.host.jvm.Tools
import com.sidescreen.host.jvm.video.FfmpegCaptureSource
import com.sidescreen.host.platform.DisplayProvider
import com.sidescreen.host.platform.DisplayRequest
import com.sidescreen.host.platform.ManagedDisplay
import com.sidescreen.host.protocol.Size
import com.sidescreen.host.settings.HostSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object WindowsDisplays {
    fun providers(
        log: (String) -> Unit,
        ffmpeg: () -> String?,
    ): List<DisplayProvider> {
        val monitors = Win32.monitors()
        return listOf<DisplayProvider>(ParsecVirtualDisplayProvider(log, ffmpeg)) +
            monitors.mapIndexed { i, m -> MonitorProvider(m, i + 1, log, ffmpeg) }
    }
}

/**
 * Maps monitors to Desktop Duplication (ddagrab) output indices. DXGI numbers
 * outputs independently of GDI, so each index is opened once and matched by the
 * desktop rectangle or, failing that, a unique size.
 */
internal object DdagrabOutputs {
    private val coordinates = Regex("""(-?\d+)\s*,\s*(-?\d+)\s*->\s*(-?\d+)\s*,\s*(-?\d+)""")
    private val streamSize = Regex("""Video: .*?(\d{3,5})x(\d{3,5})""")
    private var cache: Pair<List<Rect>, Map<Int, Rect?>>? = null

    @Synchronized
    fun indexOf(
        target: Rect,
        ffmpeg: String,
        log: (String) -> Unit,
    ): Int? {
        val monitors = Win32.monitors().map { it.rect }
        val outputs =
            cache?.takeIf { it.first == monitors }?.second
                ?: probe(ffmpeg, monitors.size).also {
                    cache = monitors to it
                    log("ddagrab outputs: $it")
                }
        outputs.entries.firstOrNull { it.value == target }?.let { return it.key }
        // No coordinates in the log: fall back to matching by size when that is unambiguous.
        val sameSize = monitors.filter { it.width == target.width && it.height == target.height }
        val candidates = outputs.filter { (_, r) -> r != null && r.width == target.width && r.height == target.height }
        return if (sameSize.size == 1 && candidates.size == 1) candidates.keys.first() else null
    }

    private fun probe(
        ffmpeg: String,
        expected: Int,
    ): Map<Int, Rect?> {
        val found = mutableMapOf<Int, Rect?>()
        for (index in 0 until maxOf(expected + 2, 4)) {
            val result =
                Tools.run(
                    listOf(
                        ffmpeg, "-hide_banner", "-v", "verbose", "-nostdin",
                        "-f", "lavfi", "-i", "ddagrab=output_idx=$index:framerate=5",
                        "-frames:v", "1", "-f", "null", "-",
                    ),
                    timeoutSeconds = 10,
                ) ?: continue
            if (result.exitCode != 0) continue
            val rect =
                coordinates.find(result.output)?.destructured?.let { (l, t, r, b) ->
                    Rect(l.toDouble(), t.toDouble(), (r.toInt() - l.toInt()).toDouble(), (b.toInt() - t.toInt()).toDouble())
                } ?: streamSize.find(result.output)?.destructured?.let { (w, h) -> Rect(Double.NaN, Double.NaN, w.toDouble(), h.toDouble()) }
            found[index] = rect
        }
        return found
    }
}

/** A Windows monitor captured with ddagrab (GPU desktop duplication) or, if unmappable, gdigrab. */
private open class WinDisplay(
    val deviceName: String,
    override val description: String,
    private val log: (String) -> Unit,
    private val ffmpeg: () -> String?,
) : ManagedDisplay, FfmpegCaptureSource {
    protected fun monitor(): Monitor = Win32.monitors().firstOrNull { it.deviceName == deviceName } ?: error("$deviceName is no longer connected")

    override val desktopSize: Size get() = monitor().rect.let { Size(it.width.toInt(), it.height.toInt()) }

    override fun inputBounds(): Rect = monitor().rect

    override fun captureSize(): Size = desktopSize

    override fun ffmpegInputArgs(fps: Int): List<String> {
        val rect = monitor().rect
        val index = ffmpeg()?.let { DdagrabOutputs.indexOf(rect, it, log) }
        return if (index != null) {
            listOf("-f", "lavfi", "-i", "ddagrab=output_idx=$index:framerate=$fps:draw_mouse=1,hwdownload,format=bgra")
        } else {
            log("No Desktop Duplication output matches $deviceName; using GDI capture (slower)")
            listOf(
                "-f", "gdigrab", "-draw_mouse", "1", "-framerate", "$fps",
                "-offset_x", "${rect.x.toInt()}", "-offset_y", "${rect.y.toInt()}",
                "-video_size", "${rect.width.toInt()}x${rect.height.toInt()}",
                "-i", "desktop",
            )
        }
    }

    override fun close() {}
}

private class MonitorProvider(
    private val monitor: Monitor,
    number: Int,
    private val log: (String) -> Unit,
    private val ffmpeg: () -> String?,
) : DisplayProvider {
    override val id = "win:${monitor.deviceName}"
    override val label =
        "Display $number${if (monitor.primary) " (primary)" else ""} — ${monitor.rect.width.toInt()}x${monitor.rect.height.toInt()} — mirror"
    override val isVirtual = false

    override fun unavailableReason(): String? = if (Win32.monitors().any { it.deviceName == monitor.deviceName }) null else "display disconnected"

    override suspend fun open(request: DisplayRequest): ManagedDisplay = WinDisplay(monitor.deviceName, label, log, ffmpeg)
}

private class ParsecVirtualDisplayProvider(
    private val log: (String) -> Unit,
    private val ffmpeg: () -> String?,
) : DisplayProvider {
    override val id = HostSettings.DISPLAY_TARGET_VIRTUAL
    override val label = "Virtual display (extend desktop)"
    override val isVirtual = true

    override fun unavailableReason(): String? =
        if (ParsecVdd.devicePath() == null) {
            "install the Parsec Virtual Display Driver (github.com/nomi-san/parsec-vdd) to extend the desktop, or pick an existing display"
        } else {
            null
        }

    override suspend fun open(request: DisplayRequest): ManagedDisplay =
        withContext(Dispatchers.IO) {
            val vdd = ParsecVdd.open() ?: error("Could not open the Parsec Virtual Display Driver")
            val before = Win32.monitors().map { it.deviceName }.toSet()
            val index = vdd.addDisplay()
            if (index < 0) {
                vdd.close()
                error("Parsec VDD refused to add a display")
            }
            val added =
                withTimeoutOrNull(8_000) {
                    var m: Monitor? = null
                    while (m == null) {
                        m = Win32.monitors().firstOrNull { it.deviceName !in before }
                        if (m == null) delay(100)
                    }
                    m
                }
            if (added == null) {
                vdd.removeDisplay(index)
                vdd.close()
                error("The virtual display did not appear")
            }
            val result = Win32.setMode(added.deviceName, request.width, request.height, request.refreshRate)
            if (result != 0) {
                // Parsec VDD offers a fixed mode list (plus custom modes from its registry key).
                log("Virtual display: ${request.width}x${request.height}@${request.refreshRate} not supported (code $result); keeping driver default")
            }
            delay(300)
            log("Virtual display ${added.deviceName} added (Parsec VDD index $index)")
            VirtualDisplay(vdd, index, added.deviceName, log, ffmpeg)
        }
}

private class VirtualDisplay(
    private val vdd: ParsecVdd,
    private val index: Int,
    deviceName: String,
    private val log: (String) -> Unit,
    ffmpeg: () -> String?,
) : WinDisplay(deviceName, "Virtual display", log, ffmpeg) {
    override val description: String get() = "Virtual display ${desktopSize}"

    override fun close() {
        runCatching { vdd.removeDisplay(index) }
        vdd.close()
        log("Virtual display removed")
    }
}
