package com.sidescreen.host.jvm

import com.sidescreen.host.input.Rect
import com.sidescreen.host.jvm.linux.LinuxDisplays
import com.sidescreen.host.settings.ConnectionMode
import com.sidescreen.host.settings.PairedDevice
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JvmPlatformTest {
    @Test
    fun parsesXrandrMonitors() {
        val output =
            """
            Monitors: 3
             0: +*eDP-1 1920/344x1080/194+0+0  eDP-1
             1: +HDMI-1 2560/597x1440/336+1920+0  HDMI-1
             2: +DP-2 1280/300x1024/240+-1280+56  DP-2
            """.trimIndent()
        val monitors = LinuxDisplays.parseMonitors(output)
        assertEquals(listOf("eDP-1", "HDMI-1", "DP-2"), monitors.map { it.name })
        assertEquals(listOf(true, false, false), monitors.map { it.primary })
        assertEquals(Rect(1920.0, 0.0, 2560.0, 1440.0), monitors[1].rect)
        assertEquals(Rect(-1280.0, 56.0, 1280.0, 1024.0), monitors[2].rect)
    }

    @Test
    fun storeRoundTripsAndSurvivesCorruption() {
        val dir = Files.createTempDirectory("sidescreen").toFile()
        val file = File(dir, "settings.json")
        val store = JsonFileStore(file)
        assertNull(store.loadAuthToken())
        val token = ByteArray(32) { it.toByte() }
        store.saveSettings(store.loadSettings().copy(connectionMode = ConnectionMode.WIRELESS, bitrateMbps = 80))
        store.saveAuthToken(token)
        store.savePairedDevices(listOf(PairedDevice("Tab S9", 42)))

        val reloaded = JsonFileStore(file)
        assertEquals(ConnectionMode.WIRELESS, reloaded.loadSettings().connectionMode)
        assertEquals(80, reloaded.loadSettings().bitrateMbps)
        assertContentEquals(token, reloaded.loadAuthToken())
        assertEquals(listOf(PairedDevice("Tab S9", 42)), reloaded.loadPairedDevices())

        file.writeText("{not json")
        assertEquals(50, JsonFileStore(file).loadSettings().bitrateMbps)
        dir.deleteRecursively()
    }
}
