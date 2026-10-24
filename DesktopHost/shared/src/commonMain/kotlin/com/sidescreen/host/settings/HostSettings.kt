package com.sidescreen.host.settings

import com.sidescreen.host.protocol.Size
import com.sidescreen.host.server.StreamingServer
import kotlinx.serialization.Serializable

@Serializable
enum class ConnectionMode { USB, WIRELESS }

/** Encoder speed/quality trade-off. */
@Serializable
enum class Quality(val label: String) {
    ULTRALOW("Ultra low latency"),
    LOW("Low latency"),
    MEDIUM("Balanced"),
    HIGH("High quality"),
}

@Serializable
data class HostSettings(
    val resolution: String = "1920x1200",
    val refreshRate: Int = 60,
    /** macOS only: render the virtual display at 2x (Retina). */
    val hiDPI: Boolean = false,
    val bitrateMbps: Int = 50,
    val quality: Quality = Quality.ULTRALOW,
    val gamingBoost: Boolean = false,
    val port: Int = StreamingServer.DEFAULT_PORT,
    val rotation: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val touchEnabled: Boolean = true,
    val connectionMode: ConnectionMode = ConnectionMode.USB,
    val autoStartStreaming: Boolean = false,
    /** [DISPLAY_TARGET_VIRTUAL] or the id of an existing monitor to stream instead. */
    val displayTarget: String = DISPLAY_TARGET_VIRTUAL,
    /** "auto" or an ffmpeg encoder name, e.g. "hevc_nvenc". */
    val encoder: String = ENCODER_AUTO,
    /** Seconds between keyframes. A new client waits for one before its first picture. */
    val keyframeIntervalSeconds: Double = 1.0,
    /** Empty = look on PATH and in the usual install locations. */
    val ffmpegPath: String = "",
    val adbPath: String = "",
) {
    val effectiveBitrateMbps: Int get() = if (gamingBoost) maxOf(bitrateMbps, 50) else bitrateMbps
    val effectiveQuality: Quality get() = if (gamingBoost) Quality.ULTRALOW else quality
    val effectiveRefreshRate: Int get() = if (gamingBoost) 120 else refreshRate

    /** Requested desktop size, swapped for portrait rotations. */
    val resolutionSize: Size
        get() {
            val parts = resolution.split('x')
            val w = parts.getOrNull(0)?.toIntOrNull() ?: 1920
            val h = parts.getOrNull(1)?.toIntOrNull() ?: 1200
            return if (rotation == 90 || rotation == 270) Size(h, w) else Size(w, h)
        }

    companion object {
        const val DISPLAY_TARGET_VIRTUAL = "virtual"
        const val ENCODER_AUTO = "auto"

        fun isValidCustomResolution(
            width: Int,
            height: Int,
        ): Boolean = width in 640..7680 && height in 480..4320

        val resolutionGroups: List<Pair<String, List<String>>> =
            listOf(
                "16:10" to listOf("1280x800", "1440x900", "1680x1050", "1920x1200", "2304x1440", "2560x1600"),
                "16:9" to listOf("1280x720", "1366x768", "1600x900", "1920x1080", "2560x1440", "3840x2160"),
                "4:3" to listOf("1024x768", "1280x960", "1600x1200", "2048x1536", "2224x1668", "2388x1668", "2732x2048"),
                "3:2" to listOf("1920x1280", "2160x1440", "2736x1824"),
                "5:3" to listOf("2000x1200", "2560x1536", "2800x1680"),
            )

        val refreshRates = listOf(30, 60, 90, 120)
        val rotations = listOf(0, 90, 180, 270)
    }
}

@Serializable
data class PairedDevice(val name: String, val lastConnectedEpochMs: Long)

/** Persistence the host needs; implemented per platform. */
interface HostStore {
    fun loadSettings(): HostSettings

    fun saveSettings(settings: HostSettings)

    fun loadAuthToken(): ByteArray?

    fun saveAuthToken(token: ByteArray?)

    fun loadPairedDevices(): List<PairedDevice>

    fun savePairedDevices(devices: List<PairedDevice>)
}

/** Most-recent-first list of wireless devices that have connected. */
class PairedDeviceRegistry(private val store: HostStore) {
    fun all(): List<PairedDevice> = store.loadPairedDevices().sortedByDescending { it.lastConnectedEpochMs }

    fun upsert(
        name: String,
        epochMs: Long,
    ) = store.savePairedDevices(all().filter { it.name != name } + PairedDevice(name, epochMs))

    fun forget(name: String) = store.savePairedDevices(all().filter { it.name != name })

    fun clear() = store.savePairedDevices(emptyList())
}
