package com.sidescreen.host.jvm.windows

import com.sidescreen.host.input.Point
import com.sidescreen.host.platform.InputInjector
import kotlin.math.roundToInt

/**
 * SetCursorPos + mouse_event. Coordinates are physical pixels on the virtual
 * desktop, which is what [Win32.monitors] reports for a DPI-aware process.
 * Windows detects double clicks by timing, so clickCount needs no special handling.
 */
class WindowsInputInjector : InputInjector {
    private val user32 = User32Ex.INSTANCE
    private var wheelRemainderX = 0.0
    private var wheelRemainderY = 0.0
    private var zoomRemainder = 0

    override val name = "Win32 SendInput"

    override fun permissionProblem(): String? = null

    private fun at(p: Point) {
        user32.SetCursorPos(p.x.roundToInt(), p.y.roundToInt())
    }

    private fun button(flags: Int) = user32.mouse_event(flags, 0, 0, 0, null)

    override fun moveCursor(p: Point) = at(p)

    override fun click(
        p: Point,
        clickCount: Int,
    ) {
        at(p)
        button(User32Ex.MOUSE_LEFT_DOWN)
        button(User32Ex.MOUSE_LEFT_UP)
    }

    override fun rightClick(p: Point) {
        at(p)
        button(User32Ex.MOUSE_RIGHT_DOWN)
        button(User32Ex.MOUSE_RIGHT_UP)
    }

    override fun leftDown(p: Point) {
        at(p)
        button(User32Ex.MOUSE_LEFT_DOWN)
    }

    override fun leftDrag(p: Point) = at(p)

    override fun leftUp(p: Point) {
        at(p)
        button(User32Ex.MOUSE_LEFT_UP)
    }

    override fun scroll(
        p: Point,
        dx: Double,
        dy: Double,
    ) {
        at(p)
        // Sub-notch wheel deltas give smooth scrolling in apps that support it.
        // Finger down (dy > 0) should move content down: a positive (upward) wheel.
        wheelRemainderY += dy * WHEEL_PER_PIXEL
        wheelRemainderX -= dx * WHEEL_PER_PIXEL
        val wy = wheelRemainderY.toInt()
        val wx = wheelRemainderX.toInt()
        if (wy != 0) {
            user32.mouse_event(User32Ex.MOUSE_WHEEL, 0, 0, wy, null)
            wheelRemainderY -= wy
        }
        if (wx != 0) {
            user32.mouse_event(User32Ex.MOUSE_HWHEEL, 0, 0, wx, null)
            wheelRemainderX -= wx
        }
    }

    // Ctrl + wheel zooms in most Windows apps; whole notches only, so apps don't zoom per event.
    override fun zoom(
        p: Point,
        delta: Int,
    ) {
        zoomRemainder += delta * ZOOM_PER_STEP
        val notches = zoomRemainder / User32Ex.WHEEL_DELTA
        if (notches == 0) return
        zoomRemainder -= notches * User32Ex.WHEEL_DELTA
        at(p)
        user32.keybd_event(User32Ex.VK_CONTROL, 0, 0, null)
        user32.mouse_event(User32Ex.MOUSE_WHEEL, 0, 0, notches * User32Ex.WHEEL_DELTA, null)
        user32.keybd_event(User32Ex.VK_CONTROL, 0, User32Ex.KEY_UP, null)
    }

    private companion object {
        const val WHEEL_PER_PIXEL = 2.0
        const val ZOOM_PER_STEP = 6
    }
}
