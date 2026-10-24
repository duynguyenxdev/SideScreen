package com.sidescreen.host.jvm

import com.sidescreen.host.jvm.linux.LinuxDisplays
import com.sidescreen.host.jvm.linux.XTestInputInjector
import com.sidescreen.host.jvm.mac.MacDisplays
import com.sidescreen.host.jvm.mac.MacInputInjector
import com.sidescreen.host.jvm.video.FfmpegPipelineFactory
import com.sidescreen.host.jvm.windows.WindowsDisplays
import com.sidescreen.host.jvm.windows.WindowsInputInjector
import com.sidescreen.host.platform.DisplayProvider
import com.sidescreen.host.platform.HostPlatform
import com.sidescreen.host.platform.InputInjector
import com.sidescreen.host.platform.UsbStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** [HostPlatform] for macOS, Windows and Linux on the JVM; OS specifics are picked at runtime. */
class JvmHostPlatform(
    private val logger: (String) -> Unit = ::println,
) : HostPlatform {
    override val osName: String =
        when (currentOs) {
            Os.MAC -> "macOS"
            Os.WINDOWS -> "Windows"
            Os.LINUX -> "Linux"
        }

    override val hostName: String =
        (System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME") ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull())
            ?.removeSuffix(".local")
            ?.takeIf { it.isNotBlank() }
            ?: "SideScreen Host"

    override val store = JsonFileStore()

    override val pipelines = FfmpegPipelineFactory({ Tools.ffmpeg(store.loadSettings().ffmpegPath) }, ::log)

    override val supportsHiDPI: Boolean = currentOs == Os.MAC

    override fun displayProviders(): List<DisplayProvider> =
        when (currentOs) {
            Os.MAC -> MacDisplays.providers(::log)
            Os.WINDOWS -> WindowsDisplays.providers(::log) { Tools.ffmpeg(store.loadSettings().ffmpegPath) }
            Os.LINUX -> LinuxDisplays.providers(::log)
        }

    override fun createInputInjector(): InputInjector =
        when (currentOs) {
            Os.MAC -> MacInputInjector()
            Os.WINDOWS -> WindowsInputInjector()
            Os.LINUX -> XTestInputInjector()
        }

    override suspend fun setupUsbReverse(port: Int): UsbStatus =
        withContext(Dispatchers.IO) {
            val adb = Tools.adb(store.loadSettings().adbPath) ?: return@withContext UsbStatus.AdbMissing
            val result =
                Tools.run(listOf(adb, "reverse", "tcp:$port", "tcp:$port"), timeoutSeconds = 15)
                    ?: return@withContext UsbStatus.Failed("adb did not respond")
            if (result.exitCode == 0) {
                log("adb reverse tcp:$port ready")
                UsbStatus.Ready
            } else {
                val reason = result.output.lines().firstOrNull { it.isNotBlank() }?.removePrefix("adb: ")?.trim() ?: "exit ${result.exitCode}"
                log("adb reverse failed: $reason")
                UsbStatus.Failed(reason)
            }
        }

    override fun lanAddress(): String? = primaryLanAddress()

    override fun log(message: String) = logger(message)

    fun warmUp() = pipelines.warmUp()
}

/** First private IPv4 on a physical-looking interface; same preference as the macOS host. */
internal fun primaryLanAddress(): String? {
    val virtualPrefixes = listOf("docker", "br-", "veth", "vbox", "vmnet", "virbr", "utun", "tun", "tap", "zt", "tailscale", "wg", "llw", "awdl", "bridge")
    val candidates =
        runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
            .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
            .filter { nic -> virtualPrefixes.none { nic.name.lowercase().startsWith(it) } }
            .filter { nic -> listOf("virtual", "hyper-v", "vmware", "virtualbox", "wsl").none { nic.displayName.orEmpty().lowercase().contains(it) } }
            .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nic.name to it } }
            .filter { (_, ip) -> !ip.isLoopbackAddress && !ip.isLinkLocalAddress }
    val preferred =
        candidates.firstOrNull { it.first == "en0" }
            ?: candidates.firstOrNull { it.second.isSiteLocalAddress && listOf("en", "eth", "wl", "wlan").any(it.first::startsWith) }
            ?: candidates.firstOrNull { it.second.isSiteLocalAddress }
            ?: candidates.firstOrNull()
    return preferred?.second?.hostAddress
}

/**
 * Call first thing in main(), before any UI toolkit loads: bundled tools live in
 * the packaged app's resources dir, and on Windows the process must be
 * per-monitor DPI aware for monitor rects and cursor positions to be physical pixels.
 */
fun prepareHostProcess() {
    if (System.getProperty("sidescreen.toolsDir") == null) {
        System.getProperty("compose.application.resources.dir")?.let { System.setProperty("sidescreen.toolsDir", it) }
    }
    if (currentOs == Os.WINDOWS) com.sidescreen.host.jvm.windows.Win32.enablePerMonitorDpiAwareness()
}
