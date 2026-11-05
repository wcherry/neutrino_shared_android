package com.neutrino.shared.android.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.TextPaint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neutrino.shared.core.print.PrintLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The pages the print dialog and a PDF export get: real StaticLayout, real PdfDocument. */
@RunWith(AndroidJUnit4::class)
class PrintRenderingTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun paragraphs(count: Int): String =
        (1..count).joinToString("\n") { "Paragraph $it. The quick brown fox jumps over the lazy dog, again and again." }

    private fun paint() = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.BLACK }

    private fun pdf(job: NeutrinoPrintJob): File {
        val file = File(context.cacheDir, "print-test-${System.nanoTime()}.pdf")
        file.outputStream().use { PrintPageRenderer(job).writePdf(it) }
        return file
    }

    private fun pdfPageCount(file: File): Int =
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount }

    /** Renders page [index] to a bitmap at 1 px per point. */
    private fun render(file: File, index: Int): Bitmap =
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
            renderer.openPage(index).use { page ->
                Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                }
            }
        }

    private fun hasInk(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Boolean {
        for (y in top until bottom) for (x in left until right) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.red(pixel) < 200 || Color.green(pixel) < 200 || Color.blue(pixel) < 200) return true
        }
        return false
    }

    // ── Text ─────────────────────────────────────────────────────────────────────────────────

    @Test fun shortTextIsOnePage() {
        val source = TextPageSource(paragraphs(3), paint())
        assertEquals(1, source.pageCount(504f, 664f))
    }

    @Test fun longTextFlowsOntoMorePages() {
        val source = TextPageSource(paragraphs(300), paint())
        val tall = source.pageCount(504f, 664f)
        val short = source.pageCount(504f, 332f)
        assertTrue("several pages, got $tall", tall > 1)
        assertTrue("half the height, about twice the pages: $tall then $short", short >= tall * 2 - 1)
    }

    @Test fun theLayoutWidthIsTheContentWidth() {
        val source = TextPageSource(paragraphs(1), paint())
        source.pageCount(504f, 664f)
        assertEquals(504, source.layoutWidth)
    }

    // ── The PDF ──────────────────────────────────────────────────────────────────────────────

    @Test fun thePdfHasOnePagePerLaidOutPage() {
        val job = NeutrinoPrintJob("Long", TextPageSource(paragraphs(300), paint()))
        val renderer = PrintPageRenderer(job)
        val expected = renderer.pageCount(renderer.layout(PrintLayout.LETTER_WIDTH, PrintLayout.LETTER_HEIGHT))

        val file = pdf(job)
        assertTrue(expected > 1)
        assertEquals(expected, pdfPageCount(file))
    }

    @Test fun pagesAreLetterSized() {
        val file = pdf(NeutrinoPrintJob("a", TextPageSource("Hello", paint())))
        val page = render(file, 0)
        assertEquals(612, page.width)
        assertEquals(792, page.height)
    }

    /** Ink in the header band, the content and the footer band — and none in the margins. */
    @Test fun headerContentAndPageNumberAreDrawnWhereTheLayoutSays() {
        val job = NeutrinoPrintJob("Minutes of the meeting", TextPageSource(paragraphs(2), paint()))
        val page = render(pdf(job), 0)
        val layout = PrintPageRenderer(job).layout(612f, 792f)

        fun ink(box: PrintLayout.Box) = hasInk(page, box.left.toInt(), box.top.toInt(), box.right.toInt(), box.bottom.toInt())
        assertTrue("header", ink(layout.header))
        assertTrue("content", ink(layout.content))
        assertTrue("footer", ink(layout.footer))
        assertTrue("left margin is blank", !hasInk(page, 0, 0, layout.content.left.toInt() - 1, 792))
    }

    /** With nothing to print, any ink on the page is a band's. */
    @Test fun noBandsWhenTurnedOff() {
        val empty = TextPageSource("", paint())
        val bare = render(pdf(NeutrinoPrintJob("Title", empty, header = null, showsPageNumbers = false)), 0)
        val dressed = render(pdf(NeutrinoPrintJob("Title", empty)), 0)

        assertTrue("a blank page", !hasInk(bare, 0, 0, 612, 792))
        assertTrue("the bands draw when on", hasInk(dressed, 0, 0, 612, 792))
    }

    /** Content draws in a canvas translated to the content area and clipped to it. */
    @Test fun aPageSourceDrawsInsideTheContentArea() {
        var size: Pair<Float, Float>? = null
        val source = object : PrintPageSource {
            override fun pageCount(width: Float, height: Float) = 1
            override fun drawPage(index: Int, canvas: Canvas, width: Float, height: Float) {
                size = width to height
                // Fills well beyond the content area; the clip must hold it in.
                canvas.drawColor(Color.BLACK)
            }
        }
        val job = NeutrinoPrintJob("a", source, header = null, showsPageNumbers = false)
        val page = render(pdf(job), 0)
        val content = PrintPageRenderer(job).layout(612f, 792f).content

        assertEquals(content.width to content.height, size)
        assertTrue(hasInk(page, content.left.toInt() + 1, content.top.toInt() + 1, content.left.toInt() + 4, content.top.toInt() + 4))
        assertTrue(!hasInk(page, 0, 0, content.left.toInt() - 1, content.top.toInt() - 1))
    }

    @Test fun fileNamesAreSafe() {
        assertEquals("Q3 report.pdf", NeutrinoPrintAdapter.fileName("Q3 report"))
        assertEquals("a b.pdf", NeutrinoPrintAdapter.fileName("a/b"))
        assertEquals("Document.pdf", NeutrinoPrintAdapter.fileName("  "))
        assertEquals("x.pdf", NeutrinoPrintAdapter.fileName("x.pdf"))
    }
}
