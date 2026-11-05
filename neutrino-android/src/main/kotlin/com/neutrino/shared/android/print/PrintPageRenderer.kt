package com.neutrino.shared.android.print

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import android.text.TextUtils
import com.neutrino.shared.core.print.PrintLayout
import java.io.OutputStream

/**
 * Lays a [NeutrinoPrintJob] out on paper: the running header, the content, and `Page n of m`.
 *
 * Used by [NeutrinoPrintAdapter] for the print dialog and by [writePdf] for everything else — the
 * same pages either way, which is what the tests assert against and what an "Export as PDF" would
 * share.
 */
class PrintPageRenderer(val job: NeutrinoPrintJob) {

    /** The page geometry for paper of this size and a printer with these unprintable edges. */
    fun layout(
        pageWidth: Float,
        pageHeight: Float,
        hardwareMargins: PrintLayout.Margins = PrintLayout.Margins(PrintLayout.DEFAULT_HARDWARE_MARGIN),
    ) = PrintLayout(pageWidth, pageHeight, hardwareMargins, job.margins,
        showsHeader = job.header != null, showsPageNumbers = job.showsPageNumbers)

    fun pageCount(layout: PrintLayout): Int {
        val content = layout.content
        if (content.width <= 0f || content.height <= 0f) return 1
        return maxOf(1, job.source.pageCount(content.width, content.height))
    }

    /** Draws page [index] of [count] on a canvas covering the whole sheet of paper. */
    fun drawPage(canvas: Canvas, index: Int, count: Int, layout: PrintLayout) {
        // A PDF page has no background of its own; paint it so a viewer in dark mode shows paper.
        canvas.drawColor(Color.WHITE)

        val content = layout.content
        canvas.save()
        canvas.translate(content.left, content.top)
        canvas.clipRect(0f, 0f, content.width, content.height)
        job.source.drawPage(index, canvas, content.width, content.height)
        canvas.restore()

        job.header?.takeIf { it.isNotEmpty() && layout.showsHeader }?.let {
            drawBand(canvas, it, layout.header, Paint.Align.LEFT)
        }
        if (layout.showsPageNumbers) {
            drawBand(canvas, PrintLayout.pageLabel(index, count), layout.footer, Paint.Align.CENTER)
        }
    }

    /** One line of small grey text, vertically centred in its band, truncated to fit. */
    private fun drawBand(canvas: Canvas, text: String, band: PrintLayout.Box, align: Paint.Align) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = PrintLayout.BAND_TEXT_SIZE
            color = BAND_COLOR
            textAlign = align
        }
        val line = TextUtils.ellipsize(text, paint, band.width, TextUtils.TruncateAt.END).toString()
        val baseline = band.top + band.height / 2 - (paint.ascent() + paint.descent()) / 2
        val x = if (align == Paint.Align.CENTER) band.left + band.width / 2 else band.left
        canvas.drawText(line, x, baseline, paint)
    }

    /**
     * Every page as a PDF on paper of this size, written to [out]. Returns the number of pages.
     * Main thread: the content's spans may read state the UI owns.
     */
    fun writePdf(
        out: OutputStream,
        pageWidth: Float = PrintLayout.LETTER_WIDTH,
        pageHeight: Float = PrintLayout.LETTER_HEIGHT,
    ): Int = writePdf(out, layout(pageWidth, pageHeight)) ?: 0

    /**
     * Every page laid out by [layout], written to [out] — or nothing, returning null, once
     * [isCancelled] says so. What the print adapter writes.
     */
    fun writePdf(out: OutputStream, layout: PrintLayout, isCancelled: () -> Boolean = { false }): Int? {
        val count = pageCount(layout)
        val pdf = PdfDocument()
        try {
            for (index in 0 until count) {
                if (isCancelled()) return null
                val info = PdfDocument.PageInfo.Builder(layout.pageWidth.toInt(), layout.pageHeight.toInt(), index + 1).create()
                val page = pdf.startPage(info)
                drawPage(page.canvas, index, count, layout)
                pdf.finishPage(page)
            }
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
        return count
    }

    companion object {
        val BAND_COLOR = Color.rgb(0x66, 0x66, 0x66)
    }
}
