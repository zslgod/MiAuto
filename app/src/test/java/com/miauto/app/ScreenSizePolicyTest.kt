package com.miauto.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the canvas ordering rules and the re-negotiation decision.
 *
 * Both matter more than they look: the canvas is fixed at `init()` time, so getting the order
 * wrong makes the phone encode for a shape the screen does not have, and getting
 * [ScreenSizePolicy.needsRenegotiation] wrong either leaves a stale canvas in place forever or
 * tears the SDK down on every single connect.
 */
class ScreenSizePolicyTest {

    private val portrait = ScreenSizePolicy.PORTRAIT
    private val portraitReverse = ScreenSizePolicy.PORTRAIT_REVERSE
    private val landscape = ScreenSizePolicy.LANDSCAPE
    private val landscapeReverse = ScreenSizePolicy.LANDSCAPE_REVERSE
    private val system = 0
    private val auto = 1

    @Test
    fun `landscape setting puts the long side first`() {
        assertArrayEquals(intArrayOf(1920, 1080), ScreenSizePolicy.reported(1080, 1920, landscape))
        assertArrayEquals(intArrayOf(1920, 1080), ScreenSizePolicy.reported(1920, 1080, landscape))
        assertArrayEquals(intArrayOf(1920, 1080), ScreenSizePolicy.reported(1920, 1080, landscapeReverse))
    }

    @Test
    fun `portrait setting puts the short side first`() {
        assertArrayEquals(intArrayOf(1080, 1920), ScreenSizePolicy.reported(1920, 1080, portrait))
        assertArrayEquals(intArrayOf(1080, 1920), ScreenSizePolicy.reported(1080, 1920, portrait))
        assertArrayEquals(intArrayOf(1080, 1920), ScreenSizePolicy.reported(1080, 1920, portraitReverse))
    }

    @Test
    fun `system and auto pass the window through untouched`() {
        // This is the case a head unit in portrait (or a tablet) used to get wrong: the old code
        // reported long-side-first unconditionally, so the picture came out stretched.
        assertArrayEquals(intArrayOf(1080, 1920), ScreenSizePolicy.reported(1080, 1920, system))
        assertArrayEquals(intArrayOf(1920, 1080), ScreenSizePolicy.reported(1920, 1080, system))
        assertArrayEquals(intArrayOf(1080, 1920), ScreenSizePolicy.reported(1080, 1920, auto))
    }

    @Test
    fun `invalid measurements are reported as zero-zero so callers retry`() {
        assertArrayEquals(intArrayOf(0, 0), ScreenSizePolicy.reported(0, 1080, landscape))
        assertArrayEquals(intArrayOf(0, 0), ScreenSizePolicy.reported(1920, 0, landscape))
        assertArrayEquals(intArrayOf(0, 0), ScreenSizePolicy.reported(-1, -1, system))
    }

    @Test
    fun `no renegotiation when the canvas is unchanged`() {
        assertFalse(ScreenSizePolicy.needsRenegotiation(1920, 1080, 1920, 1080))
    }

    @Test
    fun `renegotiation when the canvas changed`() {
        assertTrue(ScreenSizePolicy.needsRenegotiation(1080, 1920, 1920, 1080))
        assertTrue(ScreenSizePolicy.needsRenegotiation(1280, 720, 1920, 1080))
    }

    @Test
    fun `an unmeasured canvas never triggers a teardown`() {
        // Without a valid current size, re-init would only produce the 1x1 fallback canvas —
        // strictly worse than re-advertising with the old one.
        assertFalse(ScreenSizePolicy.needsRenegotiation(0, 0, 1920, 1080))
        assertFalse(ScreenSizePolicy.needsRenegotiation(1920, 0, 1920, 1080))
    }

    @Test
    fun `an init canvas that was never recorded is not trusted`() {
        // First launch records 0 only if the measurement failed at init time; that config is junk.
        assertTrue(ScreenSizePolicy.needsRenegotiation(1920, 1080, 0, 0))
    }

    @Test
    fun `canvases inside the decoder budget are reported untouched`() {
        assertArrayEquals(intArrayOf(1920, 1080), ScreenSizePolicy.fitForEncoder(1920, 1080))
        assertArrayEquals(intArrayOf(1280, 720), ScreenSizePolicy.fitForEncoder(1280, 720))
        assertArrayEquals(intArrayOf(540, 1200), ScreenSizePolicy.fitForEncoder(540, 1200))
    }

    @Test
    fun `a tall phone canvas is capped on its long side`() {
        // The measured failure: 1080x2400 negotiated fine but the AVC decoder never produced a
        // frame (black screen), while 540x1200 played. 2400 is over the 1920 side limit.
        assertArrayEquals(intArrayOf(864, 1920), ScreenSizePolicy.fitForEncoder(1080, 2400))
        assertArrayEquals(intArrayOf(1920, 864), ScreenSizePolicy.fitForEncoder(2400, 1080))
        assertArrayEquals(intArrayOf(864, 1920), ScreenSizePolicy.fitForEncoder(1440, 3200))
    }

    @Test
    fun `the pixel budget caps a canvas that fits on both sides`() {
        // 1920x1200 has no side over the limit, but 2.30 MP is past the level 4.1 budget (2.07 MP).
        val fitted = ScreenSizePolicy.fitForEncoder(1920, 1200)
        assertTrue(fitted[0].toLong() * fitted[1] <= ScreenSizePolicy.MAX_PIXELS)
        assertTrue(fitted[0] < 1920)
    }

    @Test
    fun `the fitted canvas keeps the aspect ratio, stays even and never shrinks more than needed`() {
        val sizes = listOf(
            1080 to 2400, 2400 to 1080, 1440 to 3200, 1920 to 1200,
            1080 to 1920, 1921 to 1081, 2160 to 3840,
        )
        for ((w, h) in sizes) {
            val (outW, outH) = ScreenSizePolicy.fitForEncoder(w, h)
            assertEquals("width even for ${w}x$h", 0, outW % 2)
            assertEquals("height even for ${w}x$h", 0, outH % 2)
            assertTrue("long side capped for ${w}x$h", maxOf(outW, outH) <= ScreenSizePolicy.MAX_SIDE)
            assertTrue("pixels capped for ${w}x$h", outW.toLong() * outH <= ScreenSizePolicy.MAX_PIXELS)
            val aspectError = kotlin.math.abs(outW.toDouble() / outH - w.toDouble() / h)
            assertTrue("aspect drift for ${w}x$h: $outW x $outH", aspectError < 0.01)
            // Only downscale for a real reason: the result must be close to the largest allowed.
            val allowedScale = minOf(
                1.0,
                ScreenSizePolicy.MAX_SIDE.toDouble() / maxOf(w, h),
                kotlin.math.sqrt(ScreenSizePolicy.MAX_PIXELS.toDouble() / (w.toDouble() * h)),
            )
            val actualScale = outW.toDouble() / w
            assertTrue("over-shrunk ${w}x$h -> $outW x $outH", actualScale > allowedScale * 0.97)
        }
    }

    @Test
    fun `an invalid measurement still fits to zero`() {
        assertArrayEquals(intArrayOf(0, 0), ScreenSizePolicy.fitForEncoder(0, 1080))
        assertArrayEquals(intArrayOf(0, 0), ScreenSizePolicy.fitForEncoder(1080, -1))
    }

    @Test
    fun `auto aspect is the plain encoder fit`() {
        assertArrayEquals(
            ScreenSizePolicy.fitForEncoder(1080, 2400),
            ScreenSizePolicy.canvasFor(1080, 2400, 0, 0),
        )
        assertArrayEquals(
            ScreenSizePolicy.fitForEncoder(2400, 1080),
            ScreenSizePolicy.canvasFor(2400, 1080, 0, 0),
        )
    }

    @Test
    fun `a chosen aspect gives the largest same-shape rectangle that fills the panel`() {
        // Landscape 20:9 panel, 16:9: height is the limit -> 1920x1080, exactly the pixel budget.
        assertArrayEquals(intArrayOf(1920, 1080), ScreenSizePolicy.canvasFor(2400, 1080, 16, 9))
        // 4:3 on the same panel: height-limited again -> 1440x1080.
        assertArrayEquals(intArrayOf(1440, 1080), ScreenSizePolicy.canvasFor(2400, 1080, 4, 3))
        // Portrait panel, 16:9: width-limited, the picture is a band in the middle.
        assertArrayEquals(intArrayOf(1080, 608), ScreenSizePolicy.canvasFor(1080, 2400, 16, 9))
        // 9:16 back on a portrait panel: width-limited -> 1080x1920, still inside the budget.
        assertArrayEquals(intArrayOf(1080, 1920), ScreenSizePolicy.canvasFor(1080, 2400, 9, 16))
    }

    @Test
    fun `a chosen aspect never crops and stays inside the decoder budget`() {
        val ratios = listOf(16 to 9, 4 to 3, 9 to 16, 3 to 2)
        val panels = listOf(1080 to 2400, 2400 to 1080, 2282 to 1080, 1280 to 720)
        for ((panelW, panelH) in panels) {
            for ((num, den) in ratios) {
                val (w, h) = ScreenSizePolicy.canvasFor(panelW, panelH, num, den)
                assertTrue("fits the panel ${w}x$h on ${panelW}x$panelH", w <= panelW && h <= panelH)
                assertTrue("long side ${w}x$h", maxOf(w, h) <= ScreenSizePolicy.MAX_SIDE)
                assertTrue("pixels ${w}x$h", w.toLong() * h <= ScreenSizePolicy.MAX_PIXELS)
                val expected = num.toDouble() / den
                val actual = w.toDouble() / h
                assertTrue("aspect ${w}x$h for $num:$den", kotlin.math.abs(actual - expected) < 0.01)
            }
        }
    }
}
