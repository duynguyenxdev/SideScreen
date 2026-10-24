package com.sidescreen.host.jvm.windows

import com.sidescreen.host.input.Rect
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference

@Suppress("FunctionName")
internal interface User32Ex : Library {
    fun SetCursorPos(
        x: Int,
        y: Int,
    ): Boolean

    fun mouse_event(
        flags: Int,
        dx: Int,
        dy: Int,
        data: Int,
        extraInfo: Pointer?,
    )

    fun keybd_event(
        vk: Byte,
        scan: Byte,
        flags: Int,
        extraInfo: Pointer?,
    )

    fun SetProcessDpiAwarenessContext(context: Pointer): Boolean

    fun ChangeDisplaySettingsExW(
        deviceName: WString,
        devMode: Pointer?,
        hwnd: Pointer?,
        flags: Int,
        param: Pointer?,
    ): Int

    companion object {
        val INSTANCE: User32Ex by lazy { Native.load("user32", User32Ex::class.java) }

        const val MOUSE_LEFT_DOWN = 0x0002
        const val MOUSE_LEFT_UP = 0x0004
        const val MOUSE_RIGHT_DOWN = 0x0008
        const val MOUSE_RIGHT_UP = 0x0010
        const val MOUSE_WHEEL = 0x0800
        const val MOUSE_HWHEEL = 0x1000
        const val VK_CONTROL: Byte = 0x11
        const val KEY_UP = 0x0002
        const val WHEEL_DELTA = 120
    }
}

@Suppress("FunctionName")
internal interface Kernel32Ex : Library {
    fun CreateFileW(
        name: WString,
        access: Int,
        share: Int,
        security: Pointer?,
        disposition: Int,
        flags: Int,
        template: Pointer?,
    ): Pointer?

    fun DeviceIoControl(
        device: Pointer,
        code: Int,
        input: Pointer?,
        inputSize: Int,
        output: Pointer?,
        outputSize: Int,
        returned: IntByReference?,
        overlapped: Pointer?,
    ): Boolean

    fun GetOverlappedResultEx(
        device: Pointer,
        overlapped: Pointer,
        transferred: IntByReference,
        timeoutMs: Int,
        alertable: Boolean,
    ): Boolean

    fun CancelIoEx(
        device: Pointer,
        overlapped: Pointer?,
    ): Boolean

    fun CreateEventW(
        security: Pointer?,
        manualReset: Boolean,
        initialState: Boolean,
        name: WString?,
    ): Pointer?

    fun CloseHandle(handle: Pointer): Boolean

    companion object {
        val INSTANCE: Kernel32Ex by lazy { Native.load("kernel32", Kernel32Ex::class.java) }
    }
}

@Structure.FieldOrder("cbSize", "interfaceClassGuid", "flags", "reserved")
internal class SpDeviceInterfaceData : Structure() {
    @JvmField var cbSize: Int = 0

    @JvmField var interfaceClassGuid: Guid.GUID = Guid.GUID()

    @JvmField var flags: Int = 0

    @JvmField var reserved: Pointer? = null

    init {
        cbSize = size()
    }
}

@Suppress("FunctionName")
internal interface SetupApiEx : Library {
    fun SetupDiGetClassDevsW(
        guid: Guid.GUID,
        enumerator: Pointer?,
        hwnd: Pointer?,
        flags: Int,
    ): Pointer

    fun SetupDiEnumDeviceInterfaces(
        devices: Pointer,
        deviceInfo: Pointer?,
        guid: Guid.GUID,
        index: Int,
        data: SpDeviceInterfaceData,
    ): Boolean

    fun SetupDiGetDeviceInterfaceDetailW(
        devices: Pointer,
        data: SpDeviceInterfaceData,
        detail: Pointer?,
        detailSize: Int,
        requiredSize: IntByReference?,
        deviceInfo: Pointer?,
    ): Boolean

    fun SetupDiDestroyDeviceInfoList(devices: Pointer): Boolean

    companion object {
        val INSTANCE: SetupApiEx by lazy { Native.load("setupapi", SetupApiEx::class.java) }
        const val DIGCF_PRESENT = 0x2
        const val DIGCF_DEVICEINTERFACE = 0x10
    }
}

internal data class Monitor(val deviceName: String, val rect: Rect, val primary: Boolean)

internal object Win32 {
    /** Monitors in physical pixels (the process is per-monitor DPI aware). */
    fun monitors(): List<Monitor> {
        val result = mutableListOf<Monitor>()
        val callback =
            WinUser.MONITORENUMPROC { monitor, _, _, _ ->
                val info = WinUser.MONITORINFOEX()
                if (User32.INSTANCE.GetMonitorInfo(monitor, info).booleanValue()) {
                    val r = info.rcMonitor
                    result +=
                        Monitor(
                            Native.toString(info.szDevice),
                            Rect(r.left.toDouble(), r.top.toDouble(), (r.right - r.left).toDouble(), (r.bottom - r.top).toDouble()),
                            info.dwFlags and WinUser.MONITORINFOF_PRIMARY != 0,
                        )
                }
                1
            }
        User32.INSTANCE.EnumDisplayMonitors(null, null, callback, WinDef.LPARAM(0))
        return result
    }

    /**
     * Sets a display's mode without persisting it. DEVMODEW is written by offset:
     * dmSize @68, dmFields @72, dmPelsWidth @172, dmPelsHeight @176, dmDisplayFrequency @184.
     */
    fun setMode(
        deviceName: String,
        width: Int,
        height: Int,
        refreshRate: Int,
    ): Int {
        val devMode = Memory(220).apply { clear() }
        devMode.setShort(68, 220)
        devMode.setInt(72, DM_PELSWIDTH or DM_PELSHEIGHT or DM_DISPLAYFREQUENCY)
        devMode.setInt(172, width)
        devMode.setInt(176, height)
        devMode.setInt(184, refreshRate)
        return User32Ex.INSTANCE.ChangeDisplaySettingsExW(WString(deviceName), devMode, null, 0, null)
    }

    /** Must run before AWT initialises so monitor rects and cursor positions are physical pixels. */
    fun enablePerMonitorDpiAwareness() {
        runCatching { User32Ex.INSTANCE.SetProcessDpiAwarenessContext(Pointer(-4)) }
    }

    private const val DM_PELSWIDTH = 0x00080000
    private const val DM_PELSHEIGHT = 0x00100000
    private const val DM_DISPLAYFREQUENCY = 0x00400000
}
