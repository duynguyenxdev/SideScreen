package com.sidescreen.host.jvm

import com.sidescreen.host.protocol.PairingUrl
import com.sidescreen.host.settings.HostSettings
import com.sidescreen.host.settings.HostStore
import com.sidescreen.host.settings.PairedDevice
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/** Per-user config directory: Application Support, %APPDATA% or $XDG_CONFIG_HOME. */
fun configDirectory(): File {
    val home = System.getProperty("user.home")
    val dir =
        when (currentOs) {
            Os.MAC -> File(home, "Library/Application Support/SideScreen")
            Os.WINDOWS -> File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", "SideScreen")
            Os.LINUX -> File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config", "sidescreen")
        }
    dir.mkdirs()
    return dir
}

@Serializable
private data class StoredState(
    val settings: HostSettings = HostSettings(),
    /** Base64url, no padding. */
    val authToken: String? = null,
    val pairedDevices: List<PairedDevice> = emptyList(),
)

/** Everything in one small JSON file, written atomically; reads come from memory. */
class JsonFileStore(private val file: File = File(configDirectory(), "settings.json")) : HostStore {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
    private val lock = Any()
    private var state: StoredState = load()

    private fun load(): StoredState =
        try {
            if (file.isFile) json.decodeFromString<StoredState>(file.readText()) else StoredState()
        } catch (_: Exception) {
            // A corrupt file shouldn't stop the app from starting; keep a copy for inspection.
            runCatching { file.copyTo(File(file.path + ".bad"), overwrite = true) }
            StoredState()
        }

    private fun update(transform: (StoredState) -> StoredState): Unit =
        synchronized(lock) {
            state = transform(state)
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(StoredState.serializer(), state))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }

    override fun loadSettings(): HostSettings = synchronized(lock) { state.settings }

    override fun saveSettings(settings: HostSettings) = update { it.copy(settings = settings) }

    override fun loadAuthToken(): ByteArray? =
        synchronized(lock) { state.authToken }?.let { runCatching { Base64.getUrlDecoder().decode(it) }.getOrNull() }

    override fun saveAuthToken(token: ByteArray?) = update { it.copy(authToken = token?.let(PairingUrl::base64UrlEncode)) }

    override fun loadPairedDevices(): List<PairedDevice> = synchronized(lock) { state.pairedDevices }

    override fun savePairedDevices(devices: List<PairedDevice>) = update { it.copy(pairedDevices = devices) }
}
