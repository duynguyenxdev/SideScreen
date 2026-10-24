package com.sidescreen.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.sidescreen.host.HostController
import com.sidescreen.host.HostState
import com.sidescreen.host.platform.DisplayProvider
import com.sidescreen.host.platform.UsbStatus
import com.sidescreen.host.protocol.PairingCode
import com.sidescreen.host.settings.ConnectionMode
import com.sidescreen.host.settings.HostSettings
import com.sidescreen.host.settings.Quality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val Green = Color(0xFF2E9E5B)
private val Amber = Color(0xFFE0A100)

@Composable
fun App(
    controller: HostController,
    logs: LogBuffer,
) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val state by controller.state.collectAsState()
            val settings by controller.settings.collectAsState()
            var showLogs by remember { mutableStateOf(false) }

            Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(
                    Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatusCard(controller, state, settings)
                    if (settings.connectionMode == ConnectionMode.WIRELESS) PairingCard(controller, state)
                    TextButton(onClick = { showLogs = true }) { Text("Show log") }
                    Text("Side Screen $appVersion", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SettingsPanel(controller, settings)
                }
            }
            if (showLogs) LogDialog(logs) { showLogs = false }
        }
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun StatusCard(
    controller: HostController,
    state: HostState,
    settings: HostSettings,
) {
    Section("Status") {
        val (color, label) =
            when {
                state.clientConnected -> Green to "Streaming to ${state.clientName ?: "tablet"}"
                state.running -> Amber to "Waiting for tablet"
                state.starting -> Amber to "Starting…"
                else -> MaterialTheme.colorScheme.outline to "Stopped"
            }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
        if (state.running) {
            Button(onClick = controller::stop, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
        } else {
            Button(onClick = controller::start, enabled = !state.starting, modifier = Modifier.fillMaxWidth()) { Text("Start") }
        }
        if (state.clientConnected) {
            Info("Throughput", "%.0f fps · %.1f Mbps".format(state.fps, state.mbps))
        }
        state.displayDescription?.let { Info("Display", it) }
        state.pipelineDescription?.let { Info("Encoder", "$it${state.codec?.let { c -> " · $c" } ?: ""}") }
        if (state.running && settings.connectionMode == ConnectionMode.USB) {
            val usb =
                when (val s = state.usbStatus) {
                    UsbStatus.Ready -> "Ready — open Side Screen on the tablet"
                    UsbStatus.AdbMissing -> "adb not found — install Android platform-tools or set its path"
                    is UsbStatus.Failed -> "adb reverse failed: ${s.reason}. Connect the tablet with USB debugging on, then restart."
                    null -> "…"
                }
            Info("USB", usb)
        }
        state.inputProblem?.let { Problem(it) }
        state.error?.let { Problem(it) }
    }
}

@Composable
private fun Info(
    label: String,
    value: String,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Problem(text: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
        Text(text, Modifier.padding(10.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PairingCard(
    controller: HostController,
    state: HostState,
) {
    val paired by controller.paired.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }
    Section("Wireless pairing") {
        if (!state.running) {
            Text("Start the host to show the pairing QR code.", style = MaterialTheme.typography.bodySmall)
        }
        state.pairingUrl?.let { url ->
            Text("Scan with the Side Screen app on your tablet:", style = MaterialTheme.typography.bodySmall)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrCode(url, Modifier.size(220.dp)) }
        }
        state.pairingCode?.let {
            Info("Or enter the code", PairingCode.display(it))
        }
        state.lanAddress?.let { Info("Address", it) }
        if (paired.isNotEmpty()) {
            HorizontalDivider()
            Text("Paired devices", style = MaterialTheme.typography.labelLarge)
            val format = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
            paired.forEach { device ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(device.name, style = MaterialTheme.typography.bodyMedium)
                        Text(format.format(Date(device.lastConnectedEpochMs)), style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { controller.forgetDevice(device.name) }) { Text("Forget") }
                }
            }
        }
        OutlinedButton(onClick = { confirmReset = true }) { Text("Unpair all devices") }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Unpair all devices?") },
            text = { Text("Every tablet will need to scan the QR code again.") },
            confirmButton = {
                TextButton(onClick = {
                    controller.resetAuthToken()
                    confirmReset = false
                }) { Text("Unpair") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun QrCode(
    text: String,
    modifier: Modifier,
) {
    val matrix = remember(text) { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2)) }
    Canvas(modifier.background(Color.White)) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

@Composable
private fun <T> Choice(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(150.dp), style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(options.firstOrNull { it.first == selected }?.second ?: selected.toString(), Modifier.weight(1f))
                Text("▾")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = {
                        open = false
                        onSelect(value)
                    })
                }
            }
        }
    }
}

@Composable
private fun Toggle(
    label: String,
    checked: Boolean,
    hint: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            hint?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingsPanel(
    controller: HostController,
    settings: HostSettings,
) {
    var providers by remember { mutableStateOf<List<Pair<DisplayProvider, String?>>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var encoders by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(refresh) {
        providers = withContext(Dispatchers.IO) { controller.displayProviders().map { it to it.unavailableReason() } }
    }
    LaunchedEffect(settings.ffmpegPath) {
        encoders = withContext(Dispatchers.IO) { controller.availableEncoders() }
    }
    fun update(transform: (HostSettings) -> HostSettings) = controller.updateSettings(transform)

    Section("Connection") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ConnectionMode.entries.forEachIndexed { i, mode ->
                SegmentedButton(
                    selected = settings.connectionMode == mode,
                    onClick = { update { it.copy(connectionMode = mode) } },
                    shape = SegmentedButtonDefaults.itemShape(i, ConnectionMode.entries.size),
                ) { Text(if (mode == ConnectionMode.USB) "USB cable" else "Wi-Fi") }
            }
        }
        Text(
            if (settings.connectionMode == ConnectionMode.USB) {
                "Lowest latency. Needs adb (Android platform-tools) and USB debugging on the tablet."
            } else {
                "Tablet and computer on the same network. Pair once with the QR code."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Toggle("Start streaming when the app opens", settings.autoStartStreaming) { v -> update { it.copy(autoStartStreaming = v) } }
    }

    Section("Display") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Choice(
                    "Screen",
                    providers.map { (p, reason) -> p.id to (p.label + (reason?.let { " — unavailable" } ?: "")) },
                    settings.displayTarget,
                ) { id -> update { it.copy(displayTarget = id) } }
            }
            TextButton(onClick = { refresh++ }) { Text("Refresh") }
        }
        providers.firstOrNull { it.first.id == settings.displayTarget }?.second?.let { Problem(it.replaceFirstChar(Char::uppercase)) }
        val isVirtual = providers.firstOrNull { it.first.id == settings.displayTarget }?.first?.isVirtual ?: true
        if (isVirtual) {
            val resolutions =
                HostSettings.resolutionGroups.flatMap { (group, list) -> list.map { it to "$it  ($group)" } }.let { list ->
                    if (list.none { it.first == settings.resolution }) list + (settings.resolution to "${settings.resolution}  (custom)") else list
                }
            Choice("Resolution", resolutions, settings.resolution) { r -> update { it.copy(resolution = r) } }
            CustomResolution(settings) { r -> update { it.copy(resolution = r) } }
            Choice("Refresh rate", HostSettings.refreshRates.map { it to "$it Hz" }, settings.refreshRate, enabled = !settings.gamingBoost) { r ->
                update { it.copy(refreshRate = r) }
            }
            if (controller.supportsHiDPI) {
                Toggle("HiDPI (Retina)", settings.hiDPI, "Sharper text; renders at twice the resolution") { v -> update { it.copy(hiDPI = v) } }
            }
        }
        Choice("Rotation", HostSettings.rotations.map { it to "$it°" }, settings.rotation) { r -> update { it.copy(rotation = r) } }
        Toggle("Flip horizontally", settings.flipHorizontal) { v -> update { it.copy(flipHorizontal = v) } }
        Toggle("Flip vertically", settings.flipVertical) { v -> update { it.copy(flipVertical = v) } }
    }

    Section("Video") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Bitrate", Modifier.width(150.dp), style = MaterialTheme.typography.bodyMedium)
            var bitrate by remember(settings.bitrateMbps) { mutableStateOf(settings.bitrateMbps.toFloat()) }
            Slider(
                value = bitrate,
                onValueChange = { bitrate = it },
                onValueChangeFinished = { update { s -> s.copy(bitrateMbps = bitrate.toInt()) } },
                valueRange = 5f..150f,
                modifier = Modifier.weight(1f),
                enabled = !settings.gamingBoost,
            )
            Text("${bitrate.toInt()} Mbps", Modifier.width(80.dp), style = MaterialTheme.typography.bodyMedium)
        }
        Choice("Quality", Quality.entries.map { it to it.label }, settings.quality, enabled = !settings.gamingBoost) { q -> update { it.copy(quality = q) } }
        Toggle("Gaming boost", settings.gamingBoost, "120 Hz, ultra-low latency, at least 50 Mbps") { v -> update { it.copy(gamingBoost = v) } }
        val encoderOptions =
            listOf(HostSettings.ENCODER_AUTO to "Automatic (hardware first)") +
                (encoders ?: emptyList()).map { it to it } +
                (if (encoders == null) listOf("…" to "Detecting encoders…") else emptyList())
        Choice("Encoder", encoderOptions, settings.encoder) { e -> if (e != "…") update { it.copy(encoder = e) } }
        if (encoders?.isEmpty() == true) Problem("ffmpeg not found or no encoder works. Install ffmpeg or set its path below.")
        Choice("Keyframe interval", listOf(0.5, 1.0, 2.0, 3.0, 5.0).map { it to "$it s" }, settings.keyframeIntervalSeconds) { k ->
            update { it.copy(keyframeIntervalSeconds = k) }
        }
    }

    Section("Input") {
        Toggle("Touch control", settings.touchEnabled, "Taps click, drag scrolls, hold for right-click, pinch zooms") { v ->
            update { it.copy(touchEnabled = v) }
        }
    }

    Section("Advanced") {
        PathField("ffmpeg path", settings.ffmpegPath) { p -> update { it.copy(ffmpegPath = p) } }
        PathField("adb path", settings.adbPath) { p -> update { it.copy(adbPath = p) } }
        PortField(settings.port) { p -> update { it.copy(port = p) } }
    }
}

@Composable
private fun CustomResolution(
    settings: HostSettings,
    onApply: (String) -> Unit,
) {
    var w by remember { mutableStateOf("") }
    var h by remember { mutableStateOf("") }
    val valid = w.toIntOrNull()?.let { wi -> h.toIntOrNull()?.let { hi -> HostSettings.isValidCustomResolution(wi, hi) } } == true
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Custom", Modifier.width(142.dp), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(w, { w = it.filter(Char::isDigit).take(4) }, Modifier.width(100.dp), placeholder = { Text("width") }, singleLine = true)
        Text("×")
        OutlinedTextField(h, { h = it.filter(Char::isDigit).take(4) }, Modifier.width(100.dp), placeholder = { Text("height") }, singleLine = true)
        TextButton(onClick = { onApply("${w}x$h") }, enabled = valid && "${w}x$h" != settings.resolution) { Text("Apply") }
    }
}

@Composable
private fun PathField(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            text,
            { text = it },
            Modifier.weight(1f),
            label = { Text(label) },
            placeholder = { Text("Automatic") },
            singleLine = true,
        )
        TextButton(onClick = { onCommit(text.trim()) }, enabled = text.trim() != value) { Text("Save") }
    }
}

@Composable
private fun PortField(
    port: Int,
    onCommit: (Int) -> Unit,
) {
    var text by remember(port) { mutableStateOf(port.toString()) }
    val parsed = text.toIntOrNull()?.takeIf { it in 1024..65535 }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(text, { text = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f), label = { Text("Port") }, singleLine = true)
        TextButton(onClick = { parsed?.let(onCommit) }, enabled = parsed != null && parsed != port) { Text("Save") }
    }
}

@Composable
private fun LogDialog(
    logs: LogBuffer,
    onDismiss: () -> Unit,
) {
    val lines by logs.lines.collectAsState()
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log") },
        text = {
            SelectionContainer {
                LazyColumn(Modifier.width(760.dp).height(460.dp), state = list) {
                    items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
