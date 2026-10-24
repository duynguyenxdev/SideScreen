package com.sidescreen.host.jvm.linux

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

internal data class XMonitor(val name: String, val rect: Rect, val primary: Boolean)

/** X11 displays via xrandr; Wayland sessions are reported as unsupported. */
object LinuxDisplays {
    private val monitorLine = Regex("""^\s*\d+:\s+\+?(\*?)(\S+)\s+(\d+)/\d+x(\d+)/\d+\+(-?\d+)\+(-?\d+)""")

    internal val x11Display: String get() = System.getenv("DISPLAY")?.takeIf { it.isNotBlank() } ?: ":0"

    /** Null when X11 capture and injection can work; otherwise why not. */
    fun sessionProblem(): String? =
        when {
            System.getenv("XDG_SESSION_TYPE").equals("wayland", ignoreCase = true) ->
                "Wayland sessions aren't supported yet — log in with an Xorg (X11) session"
            System.getenv("DISPLAY").isNullOrBlank() -> "No X11 display (DISPLAY is not set)"
            else -> null
        }

    internal fun xrandr(vararg args: String): Tools.Result? = Tools.find("xrandr")?.let { Tools.run(listOf(it, *args)) }

    internal fun monitors(): List<XMonitor> = parseMonitors(xrandr("--listmonitors")?.output.orEmpty())

    internal fun parseMonitors(listMonitors: String): List<XMonitor> =
        listMonitors.lines().mapNotNull { line ->
            monitorLine.find(line)?.destructured?.let { (star, name, w, h, x, y) ->
                XMonitor(name, Rect(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble()), star == "*")
            }
        }

    fun providers(log: (String) -> Unit): List<DisplayProvider> =
        listOf<DisplayProvider>(XrandrVirtualDisplayProvider(log)) +
            monitors().filterNot { it.name.startsWith(VIRTUAL_PREFIX) }.map { MonitorProvider(it) }

    internal const val VIRTUAL_PREFIX = "VIRTUAL"
}

private open class X11Display(
    val outputName: String,
    override val description: String,
) : ManagedDisplay, FfmpegCaptureSource {
    protected fun monitor(): XMonitor = LinuxDisplays.monitors().firstOrNull { it.name == outputName } ?: error("$outputName is no longer active")

    override val desktopSize: Size get() = monitor().rect.let { Size(it.width.toInt(), it.height.toInt()) }

    override fun inputBounds(): Rect = monitor().rect

    override fun captureSize(): Size = desktopSize

    override fun ffmpegInputArgs(fps: Int): List<String> {
        val r = monitor().rect
        return listOf(
            "-f", "x11grab", "-draw_mouse", "1", "-framerate", "$fps",
            "-video_size", "${r.width.toInt()}x${r.height.toInt()}",
            "-i", "${LinuxDisplays.x11Display}+${r.x.toInt()},${r.y.toInt()}",
        )
    }

    override fun close() {}
}

private class MonitorProvider(private val monitor: XMonitor) : DisplayProvider {
    override val id = "x11:${monitor.name}"
    override val label = "${monitor.name}${if (monitor.primary) " (primary)" else ""} — ${monitor.rect.width.toInt()}x${monitor.rect.height.toInt()} — mirror"
    override val isVirtual = false

    override fun unavailableReason(): String? =
        LinuxDisplays.sessionProblem() ?: if (LinuxDisplays.monitors().any { it.name == monitor.name }) null else "display disconnected"

    override suspend fun open(request: DisplayRequest): ManagedDisplay = X11Display(monitor.name, label)
}

/**
 * Extends the desktop through a disconnected VIRTUAL output — offered by the
 * intel driver with `Option "VirtualHeads"` and some others. A mode is created
 * with cvt, attached right of the primary monitor and removed on close.
 */
private class XrandrVirtualDisplayProvider(private val log: (String) -> Unit) : DisplayProvider {
    override val id = HostSettings.DISPLAY_TARGET_VIRTUAL
    override val label = "Virtual display (extend desktop)"
    override val isVirtual = true

    private fun freeVirtualOutput(): String? =
        LinuxDisplays.xrandr("--query")?.output.orEmpty().lines()
            .firstOrNull { it.startsWith(LinuxDisplays.VIRTUAL_PREFIX) && it.contains(" disconnected") }
            ?.substringBefore(' ')

    override fun unavailableReason(): String? =
        LinuxDisplays.sessionProblem()
            ?: if (Tools.find("xrandr") == null) {
                "xrandr is not installed"
            } else if (freeVirtualOutput() == null) {
                "no VIRTUAL output in xrandr (needs a driver with virtual heads, e.g. intel VirtualHeads) — pick an existing display"
            } else {
                null
            }

    override suspend fun open(request: DisplayRequest): ManagedDisplay =
        withContext(Dispatchers.IO) {
            val output = freeVirtualOutput() ?: error("No free VIRTUAL output")
            val cvt = Tools.find("cvt")?.let { Tools.run(listOf(it, "${request.width}", "${request.height}", "${request.refreshRate}")) }
            val modeline =
                cvt?.output?.lines()?.firstOrNull { it.startsWith("Modeline") }
                    ?.removePrefix("Modeline")?.trim()?.split(Regex("\\s+"))?.drop(1)
                    ?: error("cvt is needed to create a ${request.width}x${request.height} mode")
            val mode = "SideScreen_${request.width}x${request.height}_${request.refreshRate}"
            LinuxDisplays.xrandr("--newmode", mode, *modeline.toTypedArray())
            LinuxDisplays.xrandr("--addmode", output, mode)
            val primary = LinuxDisplays.monitors().firstOrNull { it.primary } ?: LinuxDisplays.monitors().firstOrNull()
            val placement = primary?.let { arrayOf("--right-of", it.name) } ?: emptyArray()
            val result = LinuxDisplays.xrandr("--output", output, "--mode", mode, *placement)
            if (result?.exitCode != 0) {
                cleanup(output, mode)
                error("xrandr could not enable $output: ${result?.output?.trim()}")
            }
            withTimeoutOrNull(5_000) { while (LinuxDisplays.monitors().none { it.name == output }) delay(100) }
            log("Virtual display $output enabled at ${request.width}x${request.height}@${request.refreshRate}")
            object : X11Display(output, "Virtual display ($output) ${request.width}x${request.height}") {
                override fun close() {
                    cleanup(output, mode)
                    log("Virtual display $output disabled")
                }
            }
        }

    private fun cleanup(
        output: String,
        mode: String,
    ) {
        LinuxDisplays.xrandr("--output", output, "--off")
        LinuxDisplays.xrandr("--delmode", output, mode)
        LinuxDisplays.xrandr("--rmmode", mode)
    }
}
