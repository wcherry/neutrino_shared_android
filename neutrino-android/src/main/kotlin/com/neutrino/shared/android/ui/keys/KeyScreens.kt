package com.neutrino.shared.android.ui.keys

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.neutrino.shared.android.LocalNeutrinoAccount
import com.neutrino.shared.android.ui.LocalNeutrinoBrand
import com.neutrino.shared.android.ui.NeutrinoBrand
import com.neutrino.shared.android.ui.VerticalSpace
import com.neutrino.shared.core.keys.ArchivedKeys
import com.neutrino.shared.core.keys.KeyBundleParser
import com.neutrino.shared.core.keys.MobileKeyQr
import com.neutrino.shared.core.keys.Pairing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a key arriving here recovered, in words — the iOS `KeyImportView`'s confirmation. */
fun describeOutcome(outcome: ArchivedKeys.Outcome): String? = when {
    outcome.activeIsStale -> "This key has since been replaced on your account. Files written after it was replaced will not open here — add your current key instead."
    outcome.unopenable > 0 -> "${outcome.unopenable} earlier key(s) on your account could not be opened with this key."
    outcome.recovered > 0 -> "Also recovered ${outcome.recovered} earlier key${if (outcome.recovered == 1) "" else "s"} from your account, so older files open here too."
    else -> null
}

/** A scanner configured for one QR code, without the beep. */
fun scanOptions(prompt: String): ScanOptions = ScanOptions()
    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    .setPrompt(prompt)
    .setBeepEnabled(false)
    .setOrientationLocked(false)

fun qrBitmap(text: String, size: Int = 720): Bitmap {
    val matrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

/** Copies text marked sensitive, so Android 13+ does not preview it in the clipboard toast. */
fun copySensitive(context: Context, label: String, text: String) {
    val clip = ClipData.newPlainText(label, text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

@Composable
private fun KeyPage(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun Success(text: String, detail: String?, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Filled.CheckCircle, null, tint = NeutrinoBrand.success, modifier = Modifier.size(56.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
        Button(onClick = onDone) { Text("Done") }
    }
}

// ── First-run setup ─────────────────────────────────────────────────────────────────────────────

/**
 * Mints the account's key right after sign-up, then shows the recovery kit exactly once — the iOS
 * `EncryptionSetupView`. Runs once: a second attempt would mint a second identity. "Continue
 * without a key" is a real option; Settings offers setup again.
 */
@Composable
fun EncryptionSetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val graph = LocalNeutrinoAccount.current
    val brand = LocalNeutrinoBrand.current
    var kit by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by rememberSaveable { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        if (kit != null) return@LaunchedEffect
        error = null
        runCatching { graph.keys.provision() }
            .onSuccess { kit = it; graph.keyringChanged() }
            .onFailure { error = it.message ?: "Could not set up encryption." }
    }

    KeyPage("Set up encryption") {
        val k = kit
        when {
            k != null -> {
                Text(
                    brand.encryptionBlurb,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Save your recovery kit", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Your key was created on this phone and never sent to us, so we cannot reset it. This kit is the only way back into your files if you lose this phone. Write it down or store it somewhere safe — it will not be shown again.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                KitBox(k)
                OutlinedButton(onClick = { copySensitive(context, "Recovery kit", k) }) {
                    Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp)); Text("  Copy recovery kit")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(saved, { saved = it })
                    Text("I have saved my recovery kit somewhere safe.", style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = onDone, enabled = saved, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
            }
            error != null -> {
                Text(error!!, color = MaterialTheme.colorScheme.error)
                Button(onClick = { attempt++ }) { Text("Try again") }
                TextButton(onClick = onDone) { Text("Continue without a key") }
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp)); Text("   Creating your encryption key…")
            }
        }
    }
}

@Composable
fun KitBox(kit: String) {
    Text(
        kit, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 20.sp, softWrap = false,
        maxLines = Int.MAX_VALUE,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp),
    )
}

// ── Pairing ─────────────────────────────────────────────────────────────────────────────────────

/**
 * Receive the keyring from a device that has it, in two QR codes — the preferred way in. This
 * phone shows QR-A; the other device scans it and shows QR-B; this phone scans that, and both show
 * a 6-digit code the user compares before anything is stored.
 */
@Composable
fun PairingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val graph = LocalNeutrinoAccount.current
    val scope = rememberCoroutineScope()
    val session = remember { Pairing.createSession() }
    DisposableEffect(Unit) { onDispose { session.close() } }
    val offerQr = remember { qrBitmap(Pairing.encode(session.offer)) }
    var response by remember { mutableStateOf<Pairing.Response?>(null) }
    var pasted by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }

    fun take(raw: String) {
        error = null
        runCatching { Pairing.parseResponse(raw) }
            .onSuccess { if (it.n != session.offer.n) error = Pairing.PairingException.DifferentAttempt().message else response = it }
            .onFailure { error = it.message }
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(::take) }

    KeyPage("Pair with another device") {
        val r = response
        when {
            done != null -> Success("This phone now has your encryption key.", done!!.ifEmpty { null }, onDone)
            r != null -> {
                Text("Check the codes match", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The other device is showing a 6-digit code. If it is the same as this one, the key came from your device and nobody in between.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    Pairing.confirmationCode(session.offer, r).chunked(3).joinToString(" "),
                    style = MaterialTheme.typography.displayMedium, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    busy = true
                    scope.launch {
                        runCatching { graph.keys.acceptPairing(r, session) }
                            .onSuccess { done = describeOutcome(it).orEmpty(); graph.keyringChanged() }
                            .onFailure { error = it.message }
                        busy = false
                    }
                }) { Text("The codes match") }
                TextButton(onClick = { response = null }) { Text("They are different — cancel") }
            }
            else -> {
                Text(
                    "On a device that already has your key — Neutrino on the web or on your computer — choose to add a device, and scan this code with it.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Image(
                        offerQr.asImageBitmap(), "Pairing code",
                        Modifier.size(260.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp),
                    )
                }
                Text(
                    "This code is a public key — photographing it reveals nothing. Then scan the code the other device shows in reply.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { scanner.launch(scanOptions("Scan the reply code from your other device")) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.QrCodeScanner, null); Text("  Scan the reply")
                }
                OutlinedTextField(
                    pasted, { pasted = it }, label = { Text("Or paste the reply") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                TextButton(onClick = { take(pasted) }, enabled = pasted.isNotBlank()) { Text("Use pasted reply") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

// ── Recovery kit ────────────────────────────────────────────────────────────────────────────────

@Composable
fun RecoveryKitScreen(onDone: () -> Unit) {
    val graph = LocalNeutrinoAccount.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }

    KeyPage("Enter recovery kit") {
        if (done != null) {
            Success("Your encryption key is restored.", done, onDone)
            return@KeyPage
        }
        Text(
            "Type the recovery kit you saved when encryption was set up. Spacing, dashes and case do not matter, and O and 0, I and 1 are read the same.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            text, { text = it }, label = { Text("Recovery kit") }, minLines = 5,
            modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(enabled = !busy && text.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
            busy = true
            error = null
            scope.launch {
                runCatching { graph.keys.restoreRecoveryKit(text) }
                    .onSuccess { ring ->
                        graph.keyringChanged()
                        done = "Key ${ring.describe()}."
                    }
                    .onFailure { error = it.message }
                busy = false
            }
        }) { Text(if (busy) "Restoring…" else "Restore") }
    }
}

// ── Key code (PIN QR) and key file ──────────────────────────────────────────────────────────────

/**
 * The web's PIN-protected key QR, and the older exported key file. Both carry the active keypair
 * only; the account's retired keys are then pulled from the key file on the server.
 */
@Composable
fun KeyCodeScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val graph = LocalNeutrinoAccount.current
    val scope = rememberCoroutineScope()
    var envelope by rememberSaveable { mutableStateOf<String?>(null) }
    var pin by rememberSaveable { mutableStateOf("") }
    var pasted by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }

    fun adoptJson(json: String) {
        busy = true
        error = null
        scope.launch {
            runCatching { graph.keys.adoptBundle(KeyBundleParser.parse(json)) }
                .onSuccess { outcome -> graph.keyringChanged(); done = describeOutcome(outcome).orEmpty() }
                .onFailure { error = it.message }
            busy = false
        }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents ?: return@rememberLauncherForActivityResult
        when {
            MobileKeyQr.looksLikeEnvelope(raw) -> { envelope = raw; error = null }
            raw.contains(Pairing.RESPONSE_TYPE) || raw.contains(Pairing.OFFER_TYPE) ->
                error = "That is a pairing code. Use “Pair with another device” instead."
            else -> error = "QR code format not recognised."
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }.getOrNull()
            }
            if (text == null) error = "Could not read that file." else adoptJson(text)
        }
    }

    KeyPage("Scan key code") {
        if (done != null) {
            Success("Your encryption key was added.", done!!.ifEmpty { null }, onDone)
            return@KeyPage
        }
        val env = envelope
        if (env == null) {
            Text(
                "On the web, open Settings › Encryption and show the key code for a phone. Scan it here, then type the PIN shown beside it.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { scanner.launch(scanOptions("Scan the key code from the web app")) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.QrCodeScanner, null); Text("  Scan key code")
            }
            VerticalSpace(4)
            Text("Or use an exported key file", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onClick = { filePicker.launch(arrayOf("application/json", "text/plain", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("Choose key file…")
            }
            OutlinedTextField(
                pasted, { pasted = it }, label = { Text("Or paste the key JSON") }, minLines = 3,
                modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            TextButton(onClick = { adoptJson(pasted) }, enabled = pasted.isNotBlank() && !busy) { Text("Import pasted key") }
        } else {
            Text("Enter the PIN shown next to the code on the web.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                pin, { pin = it.filter(Char::isDigit).take(12) }, label = { Text("PIN") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
            Button(enabled = pin.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                busy = true
                error = null
                scope.launch {
                    // 600 000 PBKDF2 rounds: a second or two, off the main thread.
                    val inner = withContext(Dispatchers.Default) { runCatching { MobileKeyQr.decrypt(env, pin) } }
                    busy = false
                    inner.onSuccess(::adoptJson).onFailure { error = it.message }
                }
            }) { Text(if (busy) "Unlocking…" else "Unlock key") }
            TextButton(onClick = { envelope = null; pin = "" }) { Text("Scan again") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
