package com.neutrino.shared.core.print

/**
 * Where everything goes on a printed page, in PDF points (1/72 inch): the running header band, the
 * content, and the `Page n of m` band. The geometry half of `neutrino-android`'s printing, kept
 * here so it is tested on the JVM — and the same numbers as NeutrinoPrint in neutrino_shared_ios,
 * so a document printed from an iPhone and from an Android phone come out alike.
 */
data class PrintLayout(
    val pageWidth: Float,
    val pageHeight: Float,
    /** The printer's unprintable edge — what the print framework reports as its minimum margins. */
    val hardwareMargins: Margins = Margins(DEFAULT_HARDWARE_MARGIN),
    /** Space between the imageable area and the content. */
    val margins: Margins = DEFAULT_MARGINS,
    val showsHeader: Boolean = true,
    val showsPageNumbers: Boolean = true,
) {
    data class Margins(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        constructor(all: Float) : this(all, all, all, all)
    }

    /** A rectangle in page coordinates, origin at the top left. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    /** The imageable area: the paper less the printer's unprintable edge. */
    val printable: Box
        get() = Box(hardwareMargins.left, hardwareMargins.top,
            pageWidth - hardwareMargins.right, pageHeight - hardwareMargins.bottom)

    val headerHeight: Float get() = if (showsHeader) BAND_HEIGHT else 0f
    val footerHeight: Float get() = if (showsPageNumbers) BAND_HEIGHT else 0f

    /** The running header's band, the full width of the imageable area, inset to the margins. */
    val header: Box
        get() = printable.let { Box(it.left + margins.left, it.top, it.right - margins.right, it.top + headerHeight) }

    val footer: Box
        get() = printable.let { Box(it.left + margins.left, it.bottom - footerHeight, it.right - margins.right, it.bottom) }

    /** Where the content is drawn: between the bands, inside the margins. */
    val content: Box
        get() = printable.let {
            Box(it.left + margins.left, it.top + headerHeight + margins.top,
                it.right - margins.right, it.bottom - footerHeight - margins.bottom)
        }

    companion object {
        /** Height of the header and footer bands. */
        const val BAND_HEIGHT = 28f
        /** Size of the header and footer text. */
        const val BAND_TEXT_SIZE = 9f
        /** A typical printer's unprintable edge, used when there is no printer to ask — a PDF. */
        const val DEFAULT_HARDWARE_MARGIN = 18f
        /** Half an inch at the sides, a quarter above and below: the bands already add air vertically. */
        val DEFAULT_MARGINS = Margins(36f, 18f, 36f, 18f)

        /** US Letter, in points. */
        const val LETTER_WIDTH = 612f
        const val LETTER_HEIGHT = 792f

        /** Converts the print framework's thousandths of an inch to points. */
        fun milsToPoints(mils: Int): Float = mils * 72f / 1000f

        /** `Page 3 of 7`, counting from one. */
        fun pageLabel(index: Int, count: Int): String = "Page ${index + 1} of ${maxOf(count, index + 1)}"
    }
}

/**
 * Cuts a column of lines into pages without splitting any line: each page takes as many whole
 * lines as fit in [pageHeight]. A line taller than a page — a photograph — gets a page to itself
 * and is clipped, rather than never fitting anywhere.
 *
 * `lineTops[i]` and `lineBottoms[i]` are line `i`'s extent in the laid-out text, top to bottom.
 * Returns each page's lines, and where on the text the page starts (its top), so a page is drawn by
 * translating the text up by that offset and clipping to the page.
 */
object PageBreaks {

    data class Page(val lines: IntRange, val top: Float, val bottom: Float) {
        val height: Float get() = bottom - top
    }

    fun split(lineTops: FloatArray, lineBottoms: FloatArray, pageHeight: Float): List<Page> {
        require(lineTops.size == lineBottoms.size) { "a top and a bottom for every line" }
        if (lineTops.isEmpty() || pageHeight <= 0f) return listOf(Page(IntRange.EMPTY, 0f, 0f))

        val pages = mutableListOf<Page>()
        var first = 0
        while (first < lineTops.size) {
            val top = lineTops[first]
            var last = first
            // Always take the first line, then every following line whose bottom still fits.
            while (last + 1 < lineTops.size && lineBottoms[last + 1] - top <= pageHeight) last++
            pages += Page(first..last, top, lineBottoms[last])
            first = last + 1
        }
        return pages
    }
}
