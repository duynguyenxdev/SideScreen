package com.sidescreen.host.protocol

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ported from MacHost/Tests/SideScreenTests/CodecLimitsTests.swift.
class CodecLimitsTest {
    @Test fun booxPanelClampsToFitAvcLimit() = assertEquals(Size(1440, 1088), CodecLimits.clampForAvc(Size(1872, 1404)))

    @Test fun sizeWithinLimitIsUntouched() = assertEquals(Size(1920, 1080), CodecLimits.clampForAvc(Size(1920, 1080)))

    @Test fun wideHiDpiPanelClamps() = assertEquals(Size(1728, 1088), CodecLimits.clampForAvc(Size(2560, 1600)))

    @Test fun widthBindingClampDoesNotLosePixelsToTruncation() = assertEquals(Size(1920, 704), CodecLimits.clampForAvc(Size(2148, 800)))

    @Test fun smallPanelUntouched() = assertEquals(Size(1280, 800), CodecLimits.clampForAvc(Size(1280, 800)))

    @Test fun clampedDimensionsAre16Aligned() {
        val r = CodecLimits.clampForAvc(Size(3840, 2400))
        assertEquals(0, r.width % 16)
        assertEquals(0, r.height % 16)
        assertTrue(r.width <= 1920 && r.height <= 1088)
    }

    @Test fun hiDpiStreamClampsToClientDecoderLimit() {
        val r = CodecLimits.clamp(Size(2400, 1440), 2048, 1152)
        assertTrue(r.width <= 2048 && r.height <= 1152)
        assertEquals(0, r.width % 16)
        assertEquals(0, r.height % 16)
    }

    @Test fun streamWithinClientLimitIsUntouched() = assertEquals(Size(1920, 1200), CodecLimits.clamp(Size(1920, 1200), 4096, 2304))

    @Test fun limitIsUnchangedAtOrBelowReferenceRate() {
        assertEquals(Size(2304, 1440), CodecLimits.scaleLimit(Size(2304, 1440), 60))
        assertEquals(Size(2304, 1440), CodecLimits.scaleLimit(Size(2304, 1440), 30))
    }

    @Test fun limitAreaHalvesAtDoubleRate() {
        val r = CodecLimits.scaleLimit(Size(2304, 1440), 120)
        assertEquals(Size(1616, 1008), r)
        assertTrue(abs(r.width * r.height / (2304.0 * 1440) - 0.5) < 0.03)
    }

    @Test fun scaledLimitKeepsAspect() {
        val r = CodecLimits.scaleLimit(Size(2560, 1600), 90)
        assertTrue(abs(r.width.toDouble() / r.height - 1.6) < 0.02)
    }

    @Test fun portraitCaptureIsMeasuredAgainstTransposedLimit() =
        assertEquals(Size(1440, 2304), CodecLimits.clampToClientLimit(Size(1440, 2304), Size(2304, 1440)))

    @Test fun landscapeCaptureUsesLimitAsReported() =
        assertEquals(Size(2304, 1440), CodecLimits.clampToClientLimit(Size(2304, 1440), Size(2304, 1440)))

    @Test fun transposedLimitStillClampsOversizeCapture() {
        val r = CodecLimits.clampToClientLimit(Size(2880, 4608), Size(2304, 1440))
        assertTrue(r.width <= 1440 && r.height <= 2304)
        assertEquals(0, r.width % 16)
        assertEquals(0, r.height % 16)
    }

    @Test fun squareCaptureIsUnaffectedByTransposition() {
        val r = CodecLimits.clampToClientLimit(Size(1600, 1600), Size(2304, 1440))
        assertTrue(r.height <= 1440)
        assertEquals(r.width, r.height)
    }

    private val honorPadX9 = mapOf(30 to Size(2000, 1200), 60 to Size(1744, 1040), 120 to Size(1248, 752))

    @Test fun tableEntryAtTheStreamRateIsUsed() = assertEquals(Size(2000, 1200), CodecLimits.budget(Size(1744, 1040), honorPadX9, 30))

    @Test fun measuredEntryBeatsTheSingleLimit() = assertEquals(Size(1744, 1040), CodecLimits.budget(Size(1744, 1040), honorPadX9, 60))

    @Test fun rateBetweenEntriesRoundsUpToTheNextMeasuredRate() = assertEquals(Size(1248, 752), CodecLimits.budget(null, honorPadX9, 90))

    @Test fun rateAboveTheTableScalesItsFastestEntryByArea() {
        val r = CodecLimits.budget(null, honorPadX9, 240)!!
        assertTrue(abs(r.width * r.height / (1248.0 * 752) - 0.5) < 0.05)
    }

    @Test fun panelSizedLimitIsNotScaledWhenTheTableSaysTheRateIsFine() {
        val panel = Size(2800, 1840)
        val r = CodecLimits.budget(panel, mapOf(30 to panel, 60 to panel, 120 to panel), 120)!!
        assertEquals(panel, CodecLimits.clampToClientLimit(panel, r))
    }

    @Test fun withoutATableTheSingleLimitScalesAsBefore() {
        val panel = Size(2800, 1840)
        val r = CodecLimits.budget(panel, null, 120)!!
        assertEquals(Size(1968, 1296), r)
        assertEquals(Size(1968, 1280), CodecLimits.clampToClientLimit(panel, r))
    }

    @Test fun nothingReportedGivesNoBudget() {
        assertNull(CodecLimits.budget(null, null, 60))
        assertNull(CodecLimits.budget(null, emptyMap(), 60))
    }

    @Test fun encodeSizeFallsBackToAvcFloorWithoutClientLimit() {
        assertEquals(Size(1728, 1088), CodecLimits.encodeSize(Size(2560, 1600), StreamCodec.H264, null, null, 60))
        assertEquals(Size(2560, 1600), CodecLimits.encodeSize(Size(2560, 1600), StreamCodec.HEVC, null, null, 60))
    }

    @Test fun decoderLimitWireRoundTrips() {
        val bytes = DecoderLimitWire.encodeSize(Size(2800, 1840))
        assertTrue(bytes.all { it.toInt() and 0x80 != 0 })
        assertEquals(Size(2800, 1840), DecoderLimitWire.decodeSize(bytes))
    }
}
