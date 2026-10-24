package com.sidescreen.host.input

import com.sidescreen.host.protocol.TouchAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

data class Point(val x: Double, val y: Double)

/** Rectangle in global desktop coordinates (the injector's coordinate space). */
data class Rect(val x: Double, val y: Double, val width: Double, val height: Double) {
    fun map(
        nx: Float,
        ny: Float,
    ): Point = Point(x + nx * width, y + ny * height)
}

/**
 * What the gesture layer asks of the OS. Coordinates are global desktop
 * coordinates. Scroll deltas are in pixels with "natural" direction: positive
 * dy means the finger moved down, so content should follow it down.
 */
interface InputSink {
    fun moveCursor(p: Point)

    fun click(
        p: Point,
        clickCount: Int,
    )

    fun rightClick(p: Point)

    fun leftDown(p: Point)

    fun leftDrag(p: Point)

    fun leftUp(p: Point)

    fun scroll(
        p: Point,
        dx: Double,
        dy: Double,
    )

    /** Zoom by [delta] steps at [p]; positive zooms in (fingers spreading). */
    fun zoom(
        p: Point,
        delta: Int,
    )
}

object GestureThresholds {
    const val TAP_MAX_DISTANCE = 15.0
    const val TAP_MAX_TIME_NS = 250_000_000L
    const val DOUBLE_TAP_MAX_TIME_NS = 400_000_000L
    const val DOUBLE_TAP_MAX_DISTANCE = 20.0
    const val LONG_PRESS_TIME_MS = 500L
    const val SCROLL_SENSITIVITY = 1.2
    const val PINCH_MIN_DISTANCE = 20.0
    const val MIN_TOUCH_INTERVAL_NS = 8_000_000L // ~120 Hz
    const val MOMENTUM_TICK_MS = 16L
    const val MOMENTUM_DECAY = 0.92
    const val MOMENTUM_MIN_VELOCITY = 0.5
}

/**
 * Touch → mouse gesture state machine (same behaviour as the original macOS host):
 *
 * - tap → left click; two quick taps → double click
 * - hold 500 ms then release → right click
 * - hold 500 ms then drag → left-button drag
 * - one-finger drag → scroll, with momentum on release
 * - two-finger drag → scroll; pinch → zoom
 *
 * Not thread-safe: call [onTouch] and run [scope] on one thread or a
 * single-parallelism dispatcher.
 */
class GestureRecognizer(
    private val sink: InputSink,
    private val scope: CoroutineScope,
    private val nanoTime: () -> Long,
) {
    private enum class State { IDLE, PENDING, SCROLLING, LONG_PRESS_READY, DRAGGING, TWO_FINGER_SCROLL, PINCHING }

    private var state = State.IDLE
    private var lastTouchTime = 0L
    private var touchStart = Point(0.0, 0.0)
    private var touchLast = Point(0.0, 0.0)
    private var touchStartTime = 0L
    private var touchLastMoveTime = 0L
    private var lastScrollDx = 0.0
    private var lastScrollDy = 0.0
    private var lastTapTime = 0L
    private var lastTapPosition = Point(0.0, 0.0)
    private var longPressJob: Job? = null
    private var initialPinchDistance = 0.0
    private var lastPinchDistance = 0.0
    private var momentumJob: Job? = null

    fun onTouch(
        p1: Point,
        action: Int,
        pointerCount: Int = 1,
        p2: Point = Point(0.0, 0.0),
    ) {
        if (pointerCount >= 2) {
            twoFinger(p1, p2, action)
        } else {
            when (action) {
                TouchAction.DOWN -> oneFingerDown(p1)
                TouchAction.MOVE -> oneFingerMove(p1)
                TouchAction.UP -> oneFingerUp(p1)
            }
        }
    }

    fun cancel() {
        cancelLongPress()
        stopMomentum()
        if (state == State.DRAGGING) sink.leftUp(touchLast)
        state = State.IDLE
    }

    private fun oneFingerDown(p: Point) {
        stopMomentum()
        cancelLongPress()
        touchStart = p
        touchLast = p
        touchStartTime = nanoTime()
        touchLastMoveTime = touchStartTime
        state = State.PENDING
        sink.moveCursor(p)
        longPressJob =
            scope.launch {
                delay(GestureThresholds.LONG_PRESS_TIME_MS)
                if (state == State.PENDING) state = State.LONG_PRESS_READY
            }
    }

    private fun oneFingerMove(p: Point) {
        val now = nanoTime()
        if (now - lastTouchTime < GestureThresholds.MIN_TOUCH_INTERVAL_NS) return
        lastTouchTime = now

        val dx = p.x - touchLast.x
        val dy = p.y - touchLast.y
        val total = distance(p, touchStart)

        when (state) {
            State.PENDING ->
                if (total > GestureThresholds.TAP_MAX_DISTANCE) {
                    cancelLongPress()
                    state = State.SCROLLING
                    val sx = dx * GestureThresholds.SCROLL_SENSITIVITY
                    val sy = dy * GestureThresholds.SCROLL_SENSITIVITY
                    sink.scroll(p, sx, sy)
                    lastScrollDx = sx
                    lastScrollDy = sy
                }

            State.LONG_PRESS_READY ->
                if (total > GestureThresholds.TAP_MAX_DISTANCE) {
                    state = State.DRAGGING
                    sink.leftDown(touchStart)
                    sink.leftDrag(p)
                }

            State.SCROLLING -> {
                val sx = dx * GestureThresholds.SCROLL_SENSITIVITY
                val sy = dy * GestureThresholds.SCROLL_SENSITIVITY
                sink.scroll(p, sx, sy)
                val dt = now - touchLastMoveTime
                if (dt in 1 until 100_000_000) {
                    lastScrollDx = sx
                    lastScrollDy = sy
                }
            }

            State.DRAGGING -> sink.leftDrag(p)

            else -> Unit
        }
        touchLast = p
        touchLastMoveTime = now
    }

    private fun oneFingerUp(p: Point) {
        cancelLongPress()
        val now = nanoTime()
        val elapsed = now - touchStartTime
        val dist = distance(p, touchStart)

        when (state) {
            State.PENDING ->
                if (dist < GestureThresholds.TAP_MAX_DISTANCE && elapsed < GestureThresholds.TAP_MAX_TIME_NS) {
                    val sinceLastTap = now - lastTapTime
                    if (lastTapTime != 0L &&
                        sinceLastTap < GestureThresholds.DOUBLE_TAP_MAX_TIME_NS &&
                        distance(p, lastTapPosition) < GestureThresholds.DOUBLE_TAP_MAX_DISTANCE
                    ) {
                        sink.click(p, clickCount = 2)
                        lastTapTime = 0 // so a triple tap doesn't chain
                    } else {
                        sink.click(p, clickCount = 1)
                        lastTapTime = now
                        lastTapPosition = p
                    }
                }

            State.LONG_PRESS_READY -> sink.rightClick(p)

            State.SCROLLING ->
                if (now - touchLastMoveTime < 50_000_000 && (abs(lastScrollDx) > 2.0 || abs(lastScrollDy) > 2.0)) {
                    startMomentum(lastScrollDx * 6.0, lastScrollDy * 6.0, p)
                }

            State.DRAGGING -> sink.leftUp(p)

            else -> Unit
        }
        state = State.IDLE
    }

    private fun twoFinger(
        p1: Point,
        p2: Point,
        action: Int,
    ) {
        val d = distance(p1, p2)
        val mid = Point((p1.x + p2.x) / 2, (p1.y + p2.y) / 2)
        when (action) {
            TouchAction.DOWN -> {
                cancelLongPress()
                stopMomentum()
                if (state == State.DRAGGING) sink.leftUp(touchLast)
                state = State.IDLE
                initialPinchDistance = d
                lastPinchDistance = d
                touchLast = mid
            }

            TouchAction.MOVE -> {
                if (state != State.TWO_FINGER_SCROLL && state != State.PINCHING) {
                    if (abs(d - initialPinchDistance) > GestureThresholds.PINCH_MIN_DISTANCE) {
                        state = State.PINCHING
                    } else if (distance(mid, touchLast) > GestureThresholds.TAP_MAX_DISTANCE) {
                        state = State.TWO_FINGER_SCROLL
                    }
                }
                when (state) {
                    State.TWO_FINGER_SCROLL -> {
                        val dx = (mid.x - touchLast.x) * GestureThresholds.SCROLL_SENSITIVITY
                        val dy = (mid.y - touchLast.y) * GestureThresholds.SCROLL_SENSITIVITY
                        sink.scroll(mid, dx, dy)
                    }

                    State.PINCHING -> {
                        val zoom = ((d - lastPinchDistance) * 0.5).toInt()
                        if (zoom != 0) {
                            sink.zoom(mid, zoom)
                            lastPinchDistance = d
                        }
                    }

                    else -> Unit
                }
                if (state == State.TWO_FINGER_SCROLL) touchLast = mid
            }

            TouchAction.UP -> {
                state = State.IDLE
                // Reset 1-finger tracking so leftover moves don't trigger scroll.
                touchStart = Point(0.0, 0.0)
                touchLast = Point(0.0, 0.0)
            }
        }
    }

    private fun startMomentum(
        vx: Double,
        vy: Double,
        at: Point,
    ) {
        stopMomentum()
        momentumJob =
            scope.launch {
                var x = vx
                var y = vy
                while (isActive) {
                    if (abs(x) < GestureThresholds.MOMENTUM_MIN_VELOCITY && abs(y) < GestureThresholds.MOMENTUM_MIN_VELOCITY) break
                    sink.scroll(at, x, y)
                    x *= GestureThresholds.MOMENTUM_DECAY
                    y *= GestureThresholds.MOMENTUM_DECAY
                    delay(GestureThresholds.MOMENTUM_TICK_MS)
                }
            }
    }

    private fun stopMomentum() {
        momentumJob?.cancel()
        momentumJob = null
    }

    private fun cancelLongPress() {
        longPressJob?.cancel()
        longPressJob = null
    }

    private fun distance(
        a: Point,
        b: Point,
    ): Double = hypot(a.x - b.x, a.y - b.y)
}
