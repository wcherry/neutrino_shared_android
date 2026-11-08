package com.neutrino.shared.android.print

import android.graphics.Canvas
import com.neutrino.shared.core.print.PrintLayout

/**
 * Everything a Neutrino app needs to say to print something: its name, its pages, and how the pages
 * are dressed. The counterpart of `PrintJob` in neutrino_shared_ios's NeutrinoPrint.
 *
 * An app's only job is to turn its own document into a [PrintPageSource]. Pagination of flowing
 * text ([TextPageSource]), the running header, page numbers, writing the PDF and the system print
 * dialog are the same in every app, and live here.
 *
 * **Paper is light.** Build the content with light-theme colours — on Android a colour is resolved
 * when the span is made, so there is nothing the printing code can re-resolve afterwards the way
 * iOS does with dynamic colours. Content built from a dark Compose theme prints white on white.
 */
data class NeutrinoPrintJob(
    /** The job's name in the print queue and the suggested PDF file name. Usually the title. */
    val name: String,
    val source: PrintPageSource,
    /** Drawn at the top of every page; `null` leaves the header off. */
    val header: String? = name,
    val showsPageNumbers: Boolean = true,
    val margins: PrintLayout.Margins = PrintLayout.DEFAULT_MARGINS,
    /** The orientation the dialog opens with. The user can still change it. */
    val prefersLandscape: Boolean = false,
)

/**
 * Content that knows how to cut itself into pages.
 *
 * Asked once the paper is known — only after the user has picked a printer and paper size — so a
 * source must not assume a size up front, and should cache its layout per size: the dialog asks
 * again every time the paper or orientation changes. Sizes are in PDF points (1/72 inch).
 *
 * Called on the main thread.
 */
interface PrintPageSource {

    /** How many pages the content needs in a content area this size. At least one. */
    fun pageCount(width: Float, height: Float): Int

    /**
     * Draws page [index]. The canvas is translated so the content area's top left is the origin,
     * and clipped to `width × height`.
     */
    fun drawPage(index: Int, canvas: Canvas, width: Float, height: Float)
}
