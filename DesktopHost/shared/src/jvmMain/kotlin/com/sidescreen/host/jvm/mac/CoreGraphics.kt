package com.sidescreen.host.jvm.mac

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference

@Structure.FieldOrder("x", "y")
open class CGPoint(
    @JvmField var x: Double = 0.0,
    @JvmField var y: Double = 0.0,
) : Structure() {
    class ByValue(x: Double = 0.0, y: Double = 0.0) : CGPoint(x, y), Structure.ByValue
}

@Structure.FieldOrder("width", "height")
open class CGSize(
    @JvmField var width: Double = 0.0,
    @JvmField var height: Double = 0.0,
) : Structure()

@Structure.FieldOrder("origin", "size")
open class CGRect : Structure() {
    @JvmField var origin = CGPoint()

    @JvmField var size = CGSize()

    class ByValue : CGRect(), Structure.ByValue
}

/** The slice of CoreGraphics the host needs: display enumeration and synthetic events. */
@Suppress("FunctionName")
internal interface CoreGraphics : Library {
    fun CGGetActiveDisplayList(
        maxDisplays: Int,
        displays: IntArray?,
        count: IntByReference,
    ): Int

    fun CGMainDisplayID(): Int

    fun CGDisplayBounds(display: Int): CGRect.ByValue

    fun CGDisplayIsBuiltin(display: Int): Int

    fun CGDisplayVendorNumber(display: Int): Int

    fun CGDisplayCopyDisplayMode(display: Int): Pointer?

    fun CGDisplayModeGetPixelWidth(mode: Pointer): NativeLong

    fun CGDisplayModeGetPixelHeight(mode: Pointer): NativeLong

    fun CGDisplayModeRelease(mode: Pointer)

    fun CGEventSourceCreate(state: Int): Pointer?

    fun CGEventCreateMouseEvent(
        source: Pointer?,
        type: Int,
        position: CGPoint.ByValue,
        button: Int,
    ): Pointer?

    fun CGEventCreateScrollWheelEvent2(
        source: Pointer?,
        units: Int,
        wheelCount: Int,
        wheel1: Int,
        wheel2: Int,
        wheel3: Int,
    ): Pointer?

    fun CGEventSetLocation(
        event: Pointer,
        location: CGPoint.ByValue,
    )

    fun CGEventSetFlags(
        event: Pointer,
        flags: Long,
    )

    fun CGEventSetIntegerValueField(
        event: Pointer,
        field: Int,
        value: Long,
    )

    fun CGEventPost(
        tap: Int,
        event: Pointer,
    )

    fun CFRelease(ref: Pointer)

    companion object {
        val INSTANCE: CoreGraphics by lazy { Native.load("CoreGraphics", CoreGraphics::class.java) }

        const val EVENT_LEFT_DOWN = 1
        const val EVENT_LEFT_UP = 2
        const val EVENT_RIGHT_DOWN = 3
        const val EVENT_RIGHT_UP = 4
        const val EVENT_MOUSE_MOVED = 5
        const val EVENT_LEFT_DRAGGED = 6
        const val BUTTON_LEFT = 0
        const val BUTTON_RIGHT = 1
        const val HID_EVENT_TAP = 0
        const val SOURCE_HID_STATE = 1
        const val FIELD_CLICK_STATE = 1
        const val SCROLL_UNIT_PIXEL = 0
        const val FLAG_COMMAND = 0x00100000L
        const val VIRTUAL_DISPLAY_VENDOR = 0xEEEE
    }
}

@Suppress("FunctionName")
internal interface ApplicationServices : Library {
    fun AXIsProcessTrusted(): Byte

    companion object {
        val INSTANCE: ApplicationServices by lazy {
            Native.load("/System/Library/Frameworks/ApplicationServices.framework/ApplicationServices", ApplicationServices::class.java)
        }
    }
}

internal object Cg {
    private val cg get() = CoreGraphics.INSTANCE

    /** Active displays, in the order ffmpeg's avfoundation numbers "Capture screen N". */
    fun activeDisplays(): List<Int> {
        val count = IntByReference()
        if (cg.CGGetActiveDisplayList(0, null, count) != 0 || count.value == 0) return emptyList()
        val ids = IntArray(count.value)
        if (cg.CGGetActiveDisplayList(ids.size, ids, count) != 0) return emptyList()
        return ids.take(count.value)
    }

    fun bounds(display: Int): CGRect = cg.CGDisplayBounds(display)

    fun pixelSize(display: Int): Pair<Int, Int>? {
        val mode = cg.CGDisplayCopyDisplayMode(display) ?: return null
        return try {
            cg.CGDisplayModeGetPixelWidth(mode).toInt() to cg.CGDisplayModeGetPixelHeight(mode).toInt()
        } finally {
            cg.CGDisplayModeRelease(mode)
        }
    }
}
