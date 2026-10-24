package com.sidescreen.host.input

import com.sidescreen.host.protocol.TouchAction.DOWN
import com.sidescreen.host.protocol.TouchAction.MOVE
import com.sidescreen.host.protocol.TouchAction.UP
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GestureRecognizerTest {
    private class Recorder : InputSink {
        val events = mutableListOf<String>()

        override fun moveCursor(p: Point) {
            events += "move"
        }

        override fun click(
            p: Point,
            clickCount: Int,
        ) {
            events += "click$clickCount"
        }

        override fun rightClick(p: Point) {
            events += "right"
        }

        override fun leftDown(p: Point) {
            events += "down"
        }

        override fun leftDrag(p: Point) {
            events += "drag"
        }

        override fun leftUp(p: Point) {
            events += "up"
        }

        override fun scroll(
            p: Point,
            dx: Double,
            dy: Double,
        ) {
            events += "scroll"
        }

        override fun zoom(
            p: Point,
            delta: Int,
        ) {
            events += if (delta > 0) "zoomIn" else "zoomOut"
        }
    }

    private fun TestScope.recognizer(sink: Recorder) = GestureRecognizer(sink, backgroundScope) { testScheduler.currentTime * 1_000_000 }

    private fun pt(
        x: Double,
        y: Double = 100.0,
    ) = Point(x, y)

    @Test fun tapClicksAndQuickSecondTapDoubleClicks() =
        runTest {
            val r = Recorder()
            val g = recognizer(r)
            g.onTouch(pt(100.0), DOWN)
            advanceTimeBy(50)
            g.onTouch(pt(101.0), UP)
            advanceTimeBy(150)
            g.onTouch(pt(102.0), DOWN)
            advanceTimeBy(50)
            g.onTouch(pt(102.0), UP)
            assertEquals(listOf("move", "click1", "move", "click2"), r.events)
        }

    @Test fun longPressThenReleaseRightClicks() =
        runTest {
            val r = Recorder()
            val g = recognizer(r)
            g.onTouch(pt(100.0), DOWN)
            advanceTimeBy(600)
            g.onTouch(pt(100.0), UP)
            assertEquals(listOf("move", "right"), r.events)
        }

    @Test fun longPressThenMoveDrags() =
        runTest {
            val r = Recorder()
            val g = recognizer(r)
            g.onTouch(pt(100.0), DOWN)
            advanceTimeBy(600)
            g.onTouch(pt(150.0), MOVE)
            advanceTimeBy(20)
            g.onTouch(pt(200.0), MOVE)
            g.onTouch(pt(200.0), UP)
            assertEquals(listOf("move", "down", "drag", "drag", "up"), r.events)
        }

    @Test fun swipeScrollsWithMomentum() =
        runTest {
            val r = Recorder()
            val g = recognizer(r)
            g.onTouch(pt(100.0), DOWN)
            for (i in 1..5) {
                advanceTimeBy(10)
                g.onTouch(pt(100.0, 100.0 + i * 20), MOVE)
            }
            g.onTouch(pt(100.0, 200.0), UP)
            val beforeMomentum = r.events.count { it == "scroll" }
            assertEquals(5, beforeMomentum)
            advanceTimeBy(2_000)
            assertTrue(r.events.count { it == "scroll" } > beforeMomentum + 10)
        }

    @Test fun slowPinchStillZooms() =
        runTest {
            val r = Recorder()
            val g = recognizer(r)
            g.onTouch(pt(100.0), DOWN, 2, pt(200.0))
            // Each step widens by 1px: below one zoom step, but it must accumulate.
            for (i in 1..60) g.onTouch(pt(100.0 - i / 2.0), MOVE, 2, pt(200.0 + i / 2.0))
            g.onTouch(pt(70.0), UP, 2, pt(230.0))
            assertTrue("zoomIn" in r.events, r.events.toString())
        }

    @Test fun twoFingerDragScrolls() =
        runTest {
            val r = Recorder()
            val g = recognizer(r)
            g.onTouch(pt(100.0, 100.0), DOWN, 2, pt(200.0, 100.0))
            for (i in 1..10) g.onTouch(pt(100.0, 100.0 + i * 4), MOVE, 2, pt(200.0, 100.0 + i * 4))
            assertTrue(r.events.count { it == "scroll" } >= 5, r.events.toString())
        }
}
