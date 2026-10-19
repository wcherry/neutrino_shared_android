package com.neutrino.shared.android.print

import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The print command's state for one screen: preparing the job, opening the dialog, and reporting a
 * failure. The counterpart of `PrintCoordinator` in neutrino_shared_ios.
 *
 * Preparing is real work in most apps — a document's pictures have to be downloaded and decrypted
 * before they can go on paper — so the job is built by a suspending function the controller runs,
 * and [isPreparing] is what the screen shows progress from and what stops a second tap.
 *
 * ```
 * val printer = rememberPrintController()
 * DropdownMenu(…) { PrintMenuItem(printer, onDismiss = { open = false }) { makeJob() } }
 * PrintStatus(printer)
 * ```
 */
@Stable
class PrintController internal constructor(
    private val scope: CoroutineScope,
    private val context: Context,
    private val present: (Context, NeutrinoPrintJob) -> Unit,
) {
    /** True from the tap until the dialog is open. */
    var isPreparing by mutableStateOf(false)
        private set

    /** The last failure, for the dialog. Cleared when it is dismissed. */
    var failure by mutableStateOf<String?>(null)

    private var task: Job? = null

    /** Builds the job and opens the print dialog. Ignored while a job is still being prepared. */
    fun print(makeJob: suspend () -> NeutrinoPrintJob) {
        if (isPreparing) return
        isPreparing = true
        task = scope.launch {
            try {
                val job = makeJob()
                isPreparing = false
                present(context, job)
            } catch (e: CancellationException) {
                isPreparing = false
                throw e
            } catch (e: Exception) {
                isPreparing = false
                failure = e.message ?: "The document couldn't be prepared for printing."
            }
        }
    }

    /** Abandons a job still being prepared. */
    fun cancel() {
        task?.cancel()
        task = null
        isPreparing = false
    }
}

/** A [PrintController] for this screen, opening the system dialog through [NeutrinoPrinter]. */
@Composable
fun rememberPrintController(): PrintController {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return remember(scope, context) { PrintController(scope, context, NeutrinoPrinter::print) }
}

/**
 * The "Print…" command, spelled once so it reads the same in every app's menu. A second command
 * for part of a document — "Print Selection…" — passes its own [label] and keeps the icon.
 */
@Composable
fun PrintMenuItem(
    controller: PrintController,
    label: String = "Print…",
    enabled: Boolean = true,
    onDismiss: () -> Unit = {},
    makeJob: suspend () -> NeutrinoPrintJob,
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(Icons.Filled.Print, null) },
        enabled = enabled && !controller.isPreparing,
        onClick = {
            onDismiss()
            controller.print(makeJob)
        },
    )
}

/** Progress while a job is prepared and a dialog for a failure. Place it once in the screen. */
@Composable
fun PrintStatus(controller: PrintController) {
    if (controller.isPreparing) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = { TextButton(onClick = { controller.cancel() }) { Text("Cancel") } },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                    Spacer(androidx.compose.ui.Modifier.width(16.dp))
                    Text("Preparing to print…")
                }
            },
        )
    }
    controller.failure?.let { message ->
        AlertDialog(
            onDismissRequest = { controller.failure = null },
            confirmButton = { TextButton(onClick = { controller.failure = null }) { Text("OK") } },
            title = { Text("Couldn't Print") },
            text = { Text(message) },
        )
    }
}
