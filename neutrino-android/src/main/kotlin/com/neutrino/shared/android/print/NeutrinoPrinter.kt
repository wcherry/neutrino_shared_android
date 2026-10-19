package com.neutrino.shared.android.print

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import com.neutrino.shared.core.print.PrintLayout
import java.io.FileOutputStream
import java.io.IOException

/**
 * Opens the system print dialog for a [NeutrinoPrintJob]. Printer choice, copies, page range, paper,
 * orientation and the preview are the system's; "Save as PDF" is one of its printers, so every
 * Android device can print even with no printer set up.
 */
object NeutrinoPrinter {

    /**
     * Shows the print dialog. [context] must reach an Activity — the dialog is one of its windows.
     *
     * @throws IllegalStateException when there is no Activity to show it from.
     */
    fun print(context: Context, job: NeutrinoPrintJob) {
        val activity = context.findActivity() ?: error("Printing needs an Activity to show the print dialog from.")
        val manager = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val attributes = PrintAttributes.Builder()
            .setMediaSize(if (job.prefersLandscape) PrintAttributes.MediaSize.UNKNOWN_LANDSCAPE else PrintAttributes.MediaSize.UNKNOWN_PORTRAIT)
            .build()
        manager.print(job.name, NeutrinoPrintAdapter(job), attributes)
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

/**
 * Feeds the print framework: lays the job out whenever the paper changes, and writes the pages the
 * user asked for as a PDF. Both callbacks arrive on the main thread.
 */
class NeutrinoPrintAdapter(job: NeutrinoPrintJob) : PrintDocumentAdapter() {

    private val renderer = PrintPageRenderer(job)
    private var layout: PrintLayout? = null
    private var pageCount = 0

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal.isCanceled) return callback.onLayoutCancelled()
        val media = newAttributes.mediaSize ?: return callback.onLayoutFailed("No paper size was chosen.")
        val min = newAttributes.minMargins
        val hardware = min?.let {
            PrintLayout.Margins(
                PrintLayout.milsToPoints(it.leftMils), PrintLayout.milsToPoints(it.topMils),
                PrintLayout.milsToPoints(it.rightMils), PrintLayout.milsToPoints(it.bottomMils),
            )
        }
        val next = renderer.layout(
            PrintLayout.milsToPoints(media.widthMils),
            PrintLayout.milsToPoints(media.heightMils),
            // A printer reporting no unprintable edge (Save as PDF) still gets the usual one, so a
            // PDF and a printed page look alike.
            hardware?.takeIf { it.left > 0f || it.top > 0f } ?: PrintLayout.Margins(PrintLayout.DEFAULT_HARDWARE_MARGIN),
        )
        val changed = next != layout
        layout = next
        pageCount = renderer.pageCount(next)

        val info = PrintDocumentInfo.Builder(fileName(renderer.job.name))
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(pageCount)
            .build()
        callback.onLayoutFinished(info, changed)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        val layout = layout ?: return callback.onWriteFailed("The document was not laid out.")
        try {
            // Every page is written whatever range was asked for: the framework picks the
            // requested ones out, and a whole document keeps "Page n of m" honest.
            val written = FileOutputStream(destination.fileDescriptor).use { out ->
                renderer.writePdf(out, layout) { cancellationSignal.isCanceled }
            }
            if (written == null) callback.onWriteCancelled() else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: IOException) {
            callback.onWriteFailed(e.message)
        }
    }

    companion object {
        /** The job's name as a PDF file name — what "Save as PDF" suggests. */
        fun fileName(name: String): String {
            val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifEmpty { "Document" }
            return if (safe.endsWith(".pdf", ignoreCase = true)) safe else "$safe.pdf"
        }
    }
}
