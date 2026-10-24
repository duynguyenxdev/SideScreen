package com.sidescreen.host.jvm.linux

import com.sidescreen.host.input.Point
import com.sidescreen.host.platform.InputInjector
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import kotlin.math.roundToInt

@Suppress("FunctionName")
private interface X11 : Library {
    fun XOpenDisplay(name: String?): Pointer?

    fun XCloseDisplay(display: Pointer): Int

    fun XFlush(display: Pointer): Int

    fun XKeysymToKeycode(
        display: Pointer,
        keysym: NativeLong,
    ): Byte
}

@Suppress("FunctionName")
private interface XTest : Library {
    fun XTestFakeMotionEvent(
        display: Pointer,
        screen: Int,
        x: Int,
        y: Int,
        delay: NativeLong,
    ): Int

    fun XTestFakeButtonEvent(
        display: Pointer,
        button: Int,
        press: Int,
        delay: NativeLong,
    ): Int

    fun XTestFakeKeyEvent(
        display: Pointer,
        keycode: Int,
        press: Int,
        delay: NativeLong,
    ): Int
}

/** XTest injection for X11 sessions. Scrolling is whole wheel clicks (buttons 4–7). */
class XTestInputInjector : InputInjector {
    private val x11: X11? = runCatching { Native.load("X11", X11::class.java) }.getOrNull()
    private val xtest: XTest? = runCatching { Native.load("Xtst", XTest::class.java) }.getOrNull()
    private val display: Pointer? = x11?.XOpenDisplay(LinuxDisplays.x11Display)
    private val noDelay = NativeLong(0)
    private var scrollRemainderX = 0.0
    private var scrollRemainderY = 0.0
    private var zoomRemainder = 0

    override val name = "XTest"

    override fun permissionProblem(): String? =
        LinuxDisplays.sessionProblem()
            ?: when {
                xtest == null -> "libXtst is missing — install libxtst6 (Debian/Ubuntu) or libXtst (Fedora)"
                display == null -> "Could not open X display ${LinuxDisplays.x11Display}"
                else -> null
            }

    private inline fun withDisplay(block: (Pointer, XTest) -> Unit) {
        val d = display ?: return
        val t = xtest ?: return
        block(d, t)
        x11?.XFlush(d)
    }

    private fun move(
        d: Pointer,
        t: XTest,
        p: Point,
    ) = t.XTestFakeMotionEvent(d, -1, p.x.roundToInt(), p.y.roundToInt(), noDelay)

    private fun press(
        d: Pointer,
        t: XTest,
        button: Int,
        down: Boolean,
    ) = t.XTestFakeButtonEvent(d, button, if (down) 1 else 0, noDelay)

    override fun moveCursor(p: Point) = withDisplay { d, t -> move(d, t, p) }

    override fun click(
        p: Point,
        clickCount: Int,
    ) = withDisplay { d, t ->
        move(d, t, p)
        press(d, t, BUTTON_LEFT, true)
        press(d, t, BUTTON_LEFT, false)
    }

    override fun rightClick(p: Point) =
        withDisplay { d, t ->
            move(d, t, p)
            press(d, t, BUTTON_RIGHT, true)
            press(d, t, BUTTON_RIGHT, false)
        }

    override fun leftDown(p: Point) =
        withDisplay { d, t ->
            move(d, t, p)
            press(d, t, BUTTON_LEFT, true)
        }

    override fun leftDrag(p: Point) = moveCursor(p)

    override fun leftUp(p: Point) =
        withDisplay { d, t ->
            move(d, t, p)
            press(d, t, BUTTON_LEFT, false)
        }

    private fun clicks(
        d: Pointer,
        t: XTest,
        button: Int,
        count: Int,
    ) = repeat(count) {
        press(d, t, button, true)
        press(d, t, button, false)
    }

    override fun scroll(
        p: Point,
        dx: Double,
        dy: Double,
    ) = withDisplay { d, t ->
        move(d, t, p)
        scrollRemainderY += dy
        scrollRemainderX += dx
        val vy = (scrollRemainderY / PIXELS_PER_CLICK).toInt()
        val vx = (scrollRemainderX / PIXELS_PER_CLICK).toInt()
        scrollRemainderY -= vy * PIXELS_PER_CLICK
        scrollRemainderX -= vx * PIXELS_PER_CLICK
        // Finger down → content down → scroll up (button 4). Finger right → scroll left (button 6).
        if (vy != 0) clicks(d, t, if (vy > 0) BUTTON_WHEEL_UP else BUTTON_WHEEL_DOWN, kotlin.math.abs(vy))
        if (vx != 0) clicks(d, t, if (vx > 0) BUTTON_WHEEL_LEFT else BUTTON_WHEEL_RIGHT, kotlin.math.abs(vx))
    }

    // Ctrl + wheel zooms in most Linux apps.
    override fun zoom(
        p: Point,
        delta: Int,
    ) = withDisplay { d, t ->
        zoomRemainder += delta
        val notches = zoomRemainder / ZOOM_STEPS_PER_NOTCH
        if (notches == 0) return@withDisplay
        zoomRemainder -= notches * ZOOM_STEPS_PER_NOTCH
        val ctrl = x11?.XKeysymToKeycode(d, NativeLong(XK_CONTROL_L))?.toInt()?.and(0xFF) ?: return@withDisplay
        move(d, t, p)
        t.XTestFakeKeyEvent(d, ctrl, 1, noDelay)
        clicks(d, t, if (notches > 0) BUTTON_WHEEL_UP else BUTTON_WHEEL_DOWN, kotlin.math.abs(notches))
        t.XTestFakeKeyEvent(d, ctrl, 0, noDelay)
    }

    override fun close() {
        display?.let { x11?.XCloseDisplay(it) }
    }

    private companion object {
        const val BUTTON_LEFT = 1
        const val BUTTON_RIGHT = 3
        const val BUTTON_WHEEL_UP = 4
        const val BUTTON_WHEEL_DOWN = 5
        const val BUTTON_WHEEL_LEFT = 6
        const val BUTTON_WHEEL_RIGHT = 7
        const val PIXELS_PER_CLICK = 30.0
        const val ZOOM_STEPS_PER_NOTCH = 20
        const val XK_CONTROL_L = 0xffe3L
    }
}
