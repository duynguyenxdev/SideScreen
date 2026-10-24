package com.sidescreen.host.jvm.windows

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.ptr.IntByReference

/**
 * Client for the Parsec Virtual Display Driver (github.com/nomi-san/parsec-vdd):
 * adds and removes virtual monitors at runtime without admin rights once the
 * driver is installed. The driver drops displays that stop receiving UPDATE
 * pings for about a second, so [ParsecVdd] pings from a daemon thread — and a
 * crashed host never leaves a stray monitor behind.
 */
internal class ParsecVdd private constructor(private val handle: Pointer) : AutoCloseable {
    private val k32 = Kernel32Ex.INSTANCE
    private val lock = Any()

    @Volatile private var closed = false
    private val keepAlive =
        Thread({
            while (!closed) {
                runCatching { update() }
                Thread.sleep(100)
            }
        }, "parsec-vdd-keepalive").apply {
            isDaemon = true
            start()
        }

    /** Adds a display and returns the driver's index for it, or -1. */
    fun addDisplay(): Int = synchronized(lock) { ioctl(IOCTL_ADD, null).also { ioctl(IOCTL_UPDATE, null) } }

    fun removeDisplay(index: Int) =
        synchronized(lock) {
            // The driver expects the index as a big-endian UINT16.
            ioctl(IOCTL_REMOVE, byteArrayOf(((index shr 8) and 0xFF).toByte(), (index and 0xFF).toByte()))
            ioctl(IOCTL_UPDATE, null)
        }

    private fun update() = synchronized(lock) { ioctl(IOCTL_UPDATE, null) }

    private fun ioctl(
        code: Int,
        data: ByteArray?,
    ): Int {
        val input = Memory(32).apply { clear() }
        data?.let { input.write(0, it, 0, minOf(it.size, 32)) }
        val output = Memory(4).apply { clear() }
        // OVERLAPPED on x64: Internal, InternalHigh, Offset/OffsetHigh, hEvent — 8 bytes each.
        val overlapped = Memory(32).apply { clear() }
        val event = k32.CreateEventW(null, true, false, null) ?: return -1
        try {
            overlapped.setPointer(24, event)
            k32.DeviceIoControl(handle, code, input, 32, output, 4, null, overlapped)
            val transferred = IntByReference()
            if (!k32.GetOverlappedResultEx(handle, overlapped, transferred, 5000, false)) {
                k32.CancelIoEx(handle, overlapped)
                k32.GetOverlappedResultEx(handle, overlapped, transferred, 1000, false)
                return -1
            }
            return output.getInt(0)
        } finally {
            k32.CloseHandle(event)
        }
    }

    override fun close() {
        closed = true
        keepAlive.join(500)
        k32.CloseHandle(handle)
    }

    companion object {
        private val ADAPTER_GUID = Guid.GUID.fromString("{00b41627-04c4-429e-a26e-0265cf50c8fa}")
        private const val IOCTL_ADD = 0x0022e004
        private const val IOCTL_REMOVE = 0x0022a008
        private const val IOCTL_UPDATE = 0x0022a00c

        private const val GENERIC_READ = -0x80000000
        private const val GENERIC_WRITE = 0x40000000
        private const val FILE_SHARE_READ_WRITE = 0x3
        private const val OPEN_EXISTING = 3
        private const val FILE_FLAGS = 0x80 or 0x20000000 or 0x40000000 or -0x80000000 // NORMAL | NO_BUFFERING | OVERLAPPED | WRITE_THROUGH

        /** Device path of the installed driver, or null if it isn't installed. */
        fun devicePath(): String? =
            runCatching {
                val setup = SetupApiEx.INSTANCE
                val devices = setup.SetupDiGetClassDevsW(ADAPTER_GUID, null, null, SetupApiEx.DIGCF_PRESENT or SetupApiEx.DIGCF_DEVICEINTERFACE)
                if (Pointer.nativeValue(devices) == -1L) return null
                try {
                    val data = SpDeviceInterfaceData()
                    if (!setup.SetupDiEnumDeviceInterfaces(devices, null, ADAPTER_GUID, 0, data)) return null
                    val required = IntByReference()
                    setup.SetupDiGetDeviceInterfaceDetailW(devices, data, null, 0, required, null)
                    if (required.value <= 0) return null
                    // SP_DEVICE_INTERFACE_DETAIL_DATA_W: DWORD cbSize (8 on x64, counting alignment) + WCHAR path[].
                    val detail = Memory(required.value.toLong()).apply { clear() }
                    detail.setInt(0, if (Native.POINTER_SIZE == 8) 8 else 6)
                    if (!setup.SetupDiGetDeviceInterfaceDetailW(devices, data, detail, required.value, null, null)) return null
                    detail.getWideString(4)
                } finally {
                    setup.SetupDiDestroyDeviceInfoList(devices)
                }
            }.getOrNull()

        fun open(): ParsecVdd? {
            val path = devicePath() ?: return null
            val handle =
                Kernel32Ex.INSTANCE.CreateFileW(WString(path), GENERIC_READ or GENERIC_WRITE, FILE_SHARE_READ_WRITE, null, OPEN_EXISTING, FILE_FLAGS, null)
                    ?: return null
            if (Pointer.nativeValue(handle) == -1L) return null
            return ParsecVdd(handle)
        }
    }
}
