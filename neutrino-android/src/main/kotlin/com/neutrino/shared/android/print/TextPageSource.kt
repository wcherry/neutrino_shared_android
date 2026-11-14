package com.neutrino.shared.android.print

import android.graphics.Canvas
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.neutrino.shared.core.print.PageBreaks

/**
 * Flows styled text across pages: one [StaticLayout] the width of the content area, cut between
 * lines with [PageBreaks] so no line is split across two pages.
 *
 * Spans draw as they would in a text view — paragraph spans (`LeadingMarginSpan`,
 * `LineBackgroundSpan`, `LineHeightSpan`) and `ReplacementSpan`s included — which is what lets an
 * app print with the same spans its editor draws with: a bullet that is a span on screen is a span
 * on paper.
 *
 * Sizes are in points, so [paint]'s text size is the body size on paper (11 for 11pt).
 */
class TextPageSource(
    /** What is printed, spans and all. */
    val text: CharSequence,
    /** The base paint: body size, typeface and colour on paper. */
    val paint: TextPaint,
) : PrintPageSource {

    /**
     * The width the text is laid out at, in points — 0 until the paper is known. For spans that
     * size themselves to the column (a rule, a picture), which ask for it during layout.
     */
    var layoutWidth: Int = 0
        private set

    private var cachedSize: Pair<Float, Float>? = null
    private var layout: StaticLayout? = null
    private var pages: List<PageBreaks.Page> = emptyList()

    override fun pageCount(width: Float, height: Float): Int {
        layOut(width, height)
        return maxOf(1, pages.size)
    }

    override fun drawPage(index: Int, canvas: Canvas, width: Float, height: Float) {
        layOut(width, height)
        val layout = layout ?: return
        val page = pages.getOrNull(index) ?: return

        canvas.save()
        canvas.translate(0f, -page.top)
        // Only this page's lines: the next line starts just below and must wait for its own page,
        // and a line taller than a page is cut at the page's foot.
        canvas.clipRect(0f, page.top, width, minOf(page.bottom, page.top + height))
        layout.draw(canvas)
        canvas.restore()
    }

    private fun layOut(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        if (cachedSize == width to height) return
        cachedSize = width to height
        layoutWidth = width.toInt()

        val built = StaticLayout.Builder.obtain(text, 0, text.length, paint, layoutWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
        layout = built

        val tops = FloatArray(built.lineCount) { built.getLineTop(it).toFloat() }
        val bottoms = FloatArray(built.lineCount) { built.getLineBottom(it).toFloat() }
        pages = PageBreaks.split(tops, bottoms, height)
    }
}
