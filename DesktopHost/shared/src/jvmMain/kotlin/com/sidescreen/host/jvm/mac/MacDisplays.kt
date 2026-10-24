package com.sidescreen.host.jvm.mac

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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

object MacDisplays {
    const val HELPER = "sidescreen-vdisplay"

    fun providers(log: (String) -> Unit): List<DisplayProvider> =
        listOf<DisplayProvider>(VirtualDisplayProvider(log)) +
            Cg.activeDisplays()
                .filter { CoreGraphics.INSTANCE.CGDisplayVendorNumber(it) != CoreGraphics.VIRTUAL_DISPLAY_VENDOR }
                .map { MonitorProvider(it) }
}

/** A CoreGraphics display captured through ffmpeg's avfoundation screen devices. */
private open class CgDisplay(
    val displayId: Int,
    override val description: String,
) : ManagedDisplay, FfmpegCaptureSource {
    override val desktopSize: Size
        get() = Cg.bounds(displayId).size.let { Size(it.width.toInt(), it.height.toInt()) }

    override fun inputBounds(): Rect = Cg.bounds(displayId).let { Rect(it.origin.x, it.origin.y, it.size.width, it.size.height) }

    override fun captureSize(): Size = Cg.pixelSize(displayId)?.let { Size(it.first, it.second) } ?: desktopSize

    override fun ffmpegInputArgs(fps: Int): List<String> {
        val index = Cg.activeDisplays().indexOf(displayId)
        check(index >= 0) { "Display $displayId is no longer active" }
        return listOf(
            "-f", "avfoundation",
            "-capture_cursor", "1",
            "-capture_mouse_clicks", "0",
            "-pixel_format", "nv12",
            "-framerate", "$fps",
            "-i", "Capture screen $index:none",
        )
    }

    override fun close() {}
}

private class MonitorProvider(private val displayId: Int) : DisplayProvider {
    private val builtin = CoreGraphics.INSTANCE.CGDisplayIsBuiltin(displayId) != 0
    private val size = Cg.bounds(displayId).size

    override val id = "cg:$displayId"
    override val label = "${if (builtin) "Built-in display" else "Display $displayId"} (${size.width.toInt()}x${size.height.toInt()}) — mirror"
    override val isVirtual = false

    override fun unavailableReason(): String? = if (displayId in Cg.activeDisplays()) null else "display disconnected"

    override suspend fun open(request: DisplayRequest): ManagedDisplay = CgDisplay(displayId, label)
}

/**
 * Extends the desktop with a CGVirtualDisplay. The private API keeps the display
 * alive only as long as the object, so a small native helper owns it; closing the
 * helper's stdin (or the app dying) removes the display.
 */
private class VirtualDisplayProvider(private val log: (String) -> Unit) : DisplayProvider {
    override val id = HostSettings.DISPLAY_TARGET_VIRTUAL
    override val label = "Virtual display (extend desktop)"
    override val isVirtual = true

    private fun helper(): String? = Tools.find(MacDisplays.HELPER)

    override fun unavailableReason(): String? = if (helper() == null) "${MacDisplays.HELPER} helper not found next to the app" else null

    override suspend fun open(request: DisplayRequest): ManagedDisplay =
        withContext(Dispatchers.IO) {
            val command =
                listOfNotNull(
                    helper() ?: error("${MacDisplays.HELPER} helper not found"),
                    "--width", "${request.width}",
                    "--height", "${request.height}",
                    "--refresh", "${request.refreshRate}",
                    "--name", request.name,
                    if (request.hiDPI) "--hidpi" else null,
                )
            val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            val stdout = process.inputStream.bufferedReader()
            // readLine blocks uninterruptibly, so time it out from outside.
            val pending = CompletableFuture.supplyAsync { runCatching { stdout.readLine() }.getOrNull() }
            val line = runCatching { pending.get(10, TimeUnit.SECONDS) }.getOrNull()
            val displayId = line?.removePrefix("READY ")?.trim()?.toIntOrNull()
            if (line == null || !line.startsWith("READY ") || displayId == null) {
                process.destroyForcibly()
                throw IllegalStateException("Could not create virtual display: ${line?.removePrefix("ERROR ") ?: "helper timed out"}")
            }
            // WindowServer takes a moment to bring the new display online.
            withTimeoutOrNull(5_000) { while (displayId !in Cg.activeDisplays()) delay(50) }
                ?: log("Virtual display $displayId not yet in the active list; continuing")
            log("Virtual display $displayId created (${request.width}x${request.height}@${request.refreshRate}${if (request.hiDPI) " HiDPI" else ""})")
            VirtualDisplay(process, displayId, "Virtual display ${request.width}x${request.height}${if (request.hiDPI) " HiDPI" else ""}", log)
        }
}

private class VirtualDisplay(
    private val process: Process,
    displayId: Int,
    description: String,
    private val log: (String) -> Unit,
) : CgDisplay(displayId, description) {
    override fun close() {
        runCatching { process.outputStream.close() }
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        log("Virtual display $displayId removed")
    }
}
