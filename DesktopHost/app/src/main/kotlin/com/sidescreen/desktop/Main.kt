package com.sidescreen.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sidescreen.host.HostController
import com.sidescreen.host.jvm.JvmHostPlatform
import com.sidescreen.host.jvm.configDirectory
import com.sidescreen.host.jvm.prepareHostProcess
import org.jetbrains.skia.Image
import java.awt.SystemTray
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

val appVersion: String = System.getProperty("sidescreen.version") ?: "dev"

/** Recent log lines for the UI, mirrored to stdout and a log file. */
class LogBuffer(private val file: File) {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss")

    init {
        runCatching { if (file.length() > 2_000_000) file.delete() }
    }

    fun log(message: String) {
        val line = "${LocalTime.now().format(time)}  $message"
        println(line)
        _lines.update { (it + line).takeLast(500) }
        synchronized(this) { runCatching { file.appendText(line + "\n") } }
    }
}

private fun loadIcon(): BitmapPainter? =
    Thread.currentThread().contextClassLoader.getResourceAsStream("tray.png")?.use {
        BitmapPainter(Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap())
    }

fun main() {
    prepareHostProcess()
    val logs = LogBuffer(File(configDirectory(), "sidescreen.log"))
    val platform = JvmHostPlatform(logs::log)
    logs.log("Side Screen $appVersion on ${platform.osName} (${System.getProperty("os.arch")})")
    val controller = HostController(platform)
    platform.warmUp()
    if (controller.settings.value.autoStartStreaming) controller.start()

    Runtime.getRuntime().addShutdownHook(Thread { controller.shutdown() })

    application {
        val icon = remember { loadIcon() }
        val trayAvailable = remember { runCatching { SystemTray.isSupported() }.getOrDefault(false) }
        var visible by remember { mutableStateOf(true) }
        val state by controller.state.collectAsState()

        fun quit() {
            controller.shutdown()
            exitApplication()
        }

        if (trayAvailable && icon != null) {
            Tray(
                icon = icon,
                tooltip = if (state.clientConnected) "Side Screen — streaming" else "Side Screen",
                onAction = { visible = true },
                menu = {
                    Item("Open Side Screen", onClick = { visible = true })
                    if (state.running) {
                        Item("Stop", onClick = controller::stop)
                    } else {
                        Item("Start", enabled = !state.starting, onClick = controller::start)
                    }
                    Separator()
                    Item("Quit", onClick = ::quit)
                },
            )
        }

        Window(
            onCloseRequest = { if (trayAvailable) visible = false else quit() },
            visible = visible,
            title = "Side Screen",
            icon = icon,
            state = rememberWindowState(size = DpSize(980.dp, 760.dp)),
        ) {
            App(controller, logs)
        }
    }
}
