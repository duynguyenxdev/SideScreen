package com.sidescreen.host.platform

import com.sidescreen.host.input.InputSink
import com.sidescreen.host.input.Rect
import com.sidescreen.host.protocol.Size
import com.sidescreen.host.protocol.StreamCodec
import com.sidescreen.host.settings.HostStore
import com.sidescreen.host.settings.Quality
import com.sidescreen.host.video.AccessUnit

/** Wall-clock milliseconds since the epoch. */
expect fun currentTimeMillis(): Long

/** Monotonic nanoseconds, for gesture timing and frame timestamps. */
expect fun monotonicNanos(): Long

data class DisplayRequest(
    val width: Int,
    val height: Int,
    val refreshRate: Int,
    val hiDPI: Boolean,
    val name: String = "SideScreen",
)

/** A display being streamed: either a virtual one we created, or an existing monitor. */
interface ManagedDisplay : AutoCloseable {
    val description: String

    /** Logical desktop size, shown on the tablet for information. */
    val desktopSize: Size

    /** Where the display sits in global desktop coordinates — what input is mapped into. Re-read on every touch: the user may rearrange displays. */
    fun inputBounds(): Rect

    /** Pixel size the capture delivers before any scaling. */
    fun captureSize(): Size
}

/** Something that can produce a [ManagedDisplay]: the platform's virtual display driver, or a physical monitor. */
interface DisplayProvider {
    val id: String
    val label: String
    val isVirtual: Boolean

    /** Null when usable; otherwise a human-readable reason (e.g. "driver not installed"). */
    fun unavailableReason(): String?

    suspend fun open(request: DisplayRequest): ManagedDisplay
}

data class EncoderConfig(
    val codec: StreamCodec,
    val size: Size,
    val fps: Int,
    val bitrateMbps: Int,
    val quality: Quality,
    val keyframeIntervalSeconds: Double,
    /** "auto" or a specific encoder name. */
    val encoder: String,
)

interface VideoPipeline {
    val description: String

    fun stop()
}

fun interface FrameSink {
    fun onAccessUnit(
        unit: AccessUnit,
        timestampNs: Long,
    )
}

interface VideoPipelineFactory {
    /** Starts capture+encode of [display]. [onFailure] fires if the pipeline dies on its own. */
    fun start(
        display: ManagedDisplay,
        config: EncoderConfig,
        sink: FrameSink,
        onFailure: (String) -> Unit,
    ): VideoPipeline

    /** Encoders usable on this machine, in preference order (for the settings UI). */
    fun availableEncoders(): List<String>
}

interface InputInjector : InputSink {
    val name: String

    /** Null when input can be injected; otherwise what the user must do (e.g. grant Accessibility). */
    fun permissionProblem(): String?

    fun close() {}
}

sealed interface UsbStatus {
    data object Ready : UsbStatus

    data class Failed(val reason: String) : UsbStatus

    data object AdbMissing : UsbStatus
}

interface HostPlatform {
    val osName: String
    val hostName: String
    val store: HostStore
    val pipelines: VideoPipelineFactory

    /** Virtual display first (when the OS supports one), then each existing monitor. */
    fun displayProviders(): List<DisplayProvider>

    fun createInputInjector(): InputInjector

    /** `adb reverse tcp:port tcp:port`, so a USB-connected tablet reaches us on loopback. */
    suspend fun setupUsbReverse(port: Int): UsbStatus

    /** Best LAN IPv4 address for the pairing QR. */
    fun lanAddress(): String?

    /** Features the settings UI should only show where they work. */
    val supportsHiDPI: Boolean

    fun log(message: String)
}
