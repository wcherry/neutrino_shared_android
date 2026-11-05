package com.neutrino.shared.core.print

import org.junit.Assert.assertEquals
import org.junit.Test

class PrintLayoutTest {

    private val letter = PrintLayout(PrintLayout.LETTER_WIDTH, PrintLayout.LETTER_HEIGHT)

    @Test fun contentSitsInsideTheBandsAndMargins() {
        val content = letter.content
        assertEquals(18f + 36f, content.left, 0.01f)
        assertEquals(18f + 28f + 18f, content.top, 0.01f)
        assertEquals(612f - 18f - 36f, content.right, 0.01f)
        assertEquals(792f - 18f - 28f - 18f, content.bottom, 0.01f)
    }

    /** The same content area NeutrinoPrint lays out on iOS: 504 × 664 on Letter. */
    @Test fun matchesTheIosContentArea() {
        assertEquals(504f, letter.content.width, 0.01f)
        assertEquals(664f, letter.content.height, 0.01f)
    }

    @Test fun noBandsGiveTheContentTheRoom() {
        val bare = letter.copy(showsHeader = false, showsPageNumbers = false)
        assertEquals(0f, bare.headerHeight, 0f)
        assertEquals(letter.content.height + 2 * PrintLayout.BAND_HEIGHT, bare.content.height, 0.01f)
    }

    @Test fun bandsLineUpWithTheContentEdges() {
        assertEquals(letter.content.left, letter.header.left, 0f)
        assertEquals(letter.content.right, letter.footer.right, 0f)
        assertEquals(letter.printable.bottom, letter.footer.bottom, 0f)
    }

    @Test fun milsAreConvertedToPoints() {
        // Letter is 8.5 × 11 inches, 8500 × 11000 mils.
        assertEquals(612f, PrintLayout.milsToPoints(8500), 0.01f)
        assertEquals(792f, PrintLayout.milsToPoints(11000), 0.01f)
    }

    @Test fun pageLabelsCountFromOne() {
        assertEquals("Page 1 of 3", PrintLayout.pageLabel(0, 3))
        assertEquals("Page 3 of 3", PrintLayout.pageLabel(2, 3))
    }

    // ── Page breaks ──────────────────────────────────────────────────────────────────────────

    private fun lines(vararg heights: Float): Pair<FloatArray, FloatArray> {
        val tops = FloatArray(heights.size)
        val bottoms = FloatArray(heights.size)
        var y = 0f
        heights.forEachIndexed { i, h -> tops[i] = y; y += h; bottoms[i] = y }
        return tops to bottoms
    }

    @Test fun shortTextIsOnePage() {
        val (tops, bottoms) = lines(10f, 10f, 10f)
        val pages = PageBreaks.split(tops, bottoms, 100f)
        assertEquals(1, pages.size)
        assertEquals(0..2, pages[0].lines)
    }

    @Test fun noTextIsStillOnePage() {
        assertEquals(1, PageBreaks.split(FloatArray(0), FloatArray(0), 100f).size)
    }

    @Test fun linesAreNeverSplitAcrossPages() {
        val (tops, bottoms) = lines(*FloatArray(25) { 12f })
        val pages = PageBreaks.split(tops, bottoms, 100f)
        // 8 lines of 12 fit in 100; the ninth would end at 108.
        assertEquals(listOf(0..7, 8..15, 16..23, 24..24), pages.map { it.lines })
        assertEquals(96f, pages[1].top, 0f)
        pages.forEach { assert(it.height <= 100f) }
    }

    @Test fun aLineTallerThanAPageGetsAPageToItself() {
        val (tops, bottoms) = lines(10f, 250f, 10f)
        val pages = PageBreaks.split(tops, bottoms, 100f)
        assertEquals(listOf(0..0, 1..1, 2..2), pages.map { it.lines })
    }

    @Test fun everyLineLandsOnExactlyOnePage() {
        val (tops, bottoms) = lines(*FloatArray(97) { (it % 7 + 5).toFloat() })
        val pages = PageBreaks.split(tops, bottoms, 64f)
        assertEquals((0..96).toList(), pages.flatMap { it.lines.toList() })
    }
}
