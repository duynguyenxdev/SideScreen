package com.sidescreen.host.jvm.mac

import com.sidescreen.host.input.Point
import com.sidescreen.host.platform.InputInjector
import com.sun.jna.Pointer

/** CGEvent injection, matching the original macOS host. Needs Accessibility permission. */
class MacInputInjector : InputInjector {
    private val cg = CoreGraphics.INSTANCE
    private val source: Pointer? = cg.CGEventSourceCreate(CoreGraphics.SOURCE_HID_STATE)

    override val name = "CoreGraphics events"

    override fun permissionProblem(): String? =
        if (runCatching { ApplicationServices.INSTANCE.AXIsProcessTrusted().toInt() != 0 }.getOrDefault(true)) {
            null
        } else {
            "Grant Accessibility access in System Settings → Privacy & Security → Accessibility so touches can control the cursor"
        }

    private fun mouse(
        type: Int,
        p: Point,
        button: Int = CoreGraphics.BUTTON_LEFT,
        clickState: Long = 0,
    ) {
        val event = cg.CGEventCreateMouseEvent(source, type, CGPoint.ByValue(p.x, p.y), button) ?: return
        try {
            if (clickState > 0) cg.CGEventSetIntegerValueField(event, CoreGraphics.FIELD_CLICK_STATE, clickState)
            cg.CGEventPost(CoreGraphics.HID_EVENT_TAP, event)
        } finally {
            cg.CFRelease(event)
        }
    }

    private fun wheel(
        p: Point,
        vertical: Int,
        horizontal: Int,
        flags: Long = 0,
    ) {
        val event = cg.CGEventCreateScrollWheelEvent2(source, CoreGraphics.SCROLL_UNIT_PIXEL, 2, vertical, horizontal, 0) ?: return
        try {
            cg.CGEventSetLocation(event, CGPoint.ByValue(p.x, p.y))
            if (flags != 0L) cg.CGEventSetFlags(event, flags)
            cg.CGEventPost(CoreGraphics.HID_EVENT_TAP, event)
        } finally {
            cg.CFRelease(event)
        }
    }

    override fun moveCursor(p: Point) = mouse(CoreGraphics.EVENT_MOUSE_MOVED, p)

    override fun click(
        p: Point,
        clickCount: Int,
    ) {
        mouse(CoreGraphics.EVENT_LEFT_DOWN, p, clickState = clickCount.toLong())
        mouse(CoreGraphics.EVENT_LEFT_UP, p, clickState = clickCount.toLong())
    }

    override fun rightClick(p: Point) {
        mouse(CoreGraphics.EVENT_RIGHT_DOWN, p, CoreGraphics.BUTTON_RIGHT)
        mouse(CoreGraphics.EVENT_RIGHT_UP, p, CoreGraphics.BUTTON_RIGHT)
    }

    override fun leftDown(p: Point) = mouse(CoreGraphics.EVENT_LEFT_DOWN, p, clickState = 1)

    override fun leftDrag(p: Point) = mouse(CoreGraphics.EVENT_LEFT_DRAGGED, p)

    override fun leftUp(p: Point) = mouse(CoreGraphics.EVENT_LEFT_UP, p, clickState = 1)

    override fun scroll(
        p: Point,
        dx: Double,
        dy: Double,
    ) = wheel(p, dy.toInt(), dx.toInt())

    // Cmd + scroll zooms in most Mac apps.
    override fun zoom(
        p: Point,
        delta: Int,
    ) = wheel(p, delta, 0, CoreGraphics.FLAG_COMMAND)

    override fun close() {
        source?.let(cg::CFRelease)
    }
}
