package com.neutrino.shared.android.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.neutrino.shared.android.LocalNeutrinoAccount
import com.neutrino.shared.android.ui.LocalNeutrinoBrand
import com.neutrino.shared.android.ui.NeutrinoBrand
import com.neutrino.shared.core.api.AuthException
import kotlinx.coroutines.launch

/**
 * Sign-in, as the shared iOS `LoginView`: the brand, three trust rows, email and password, a
 * second-factor field that appears only when the server asks for one, and the server address
 * tucked away for self-hosters.
 */
@Composable
fun LoginScreen(onRegister: () -> Unit) {
    val graph = LocalNeutrinoAccount.current
    val brand = LocalNeutrinoBrand.current
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var totp by rememberSaveable { mutableStateOf("") }
    var needsTotp by rememberSaveable { mutableStateOf(false) }
    var server by rememberSaveable { mutableStateOf(graph.settings.serverUrl) }
    var editingServer by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (busy || email.isBlank() || password.isEmpty()) return
        busy = true
        error = null
        scope.launch {
            try {
                graph.setServerUrl(server)
                graph.auth.login(email, password, totp.takeIf { needsTotp })
                graph.onSignedIn()
            } catch (e: AuthException.TwoFactorRequired) {
                needsTotp = true
            } catch (e: Exception) {
                error = e.message ?: "Sign-in failed."
            } finally {
                busy = false
            }
        }
    }

    AuthScaffold {
        BrandHeader()
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            brand.trustRows.forEach { TrustRow(it.icon, it.text, it.color) }
        }
        OutlinedTextField(
            email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        )
        PasswordField(password, { password = it }, "Password", imeAction = if (needsTotp) ImeAction.Next else ImeAction.Done, onDone = ::submit)
        if (needsTotp) {
            OutlinedTextField(
                totp, { totp = it.filter(Char::isDigit).take(8) }, label = { Text("Authenticator code") },
                supportingText = { Text("Enter the 6-digit code from your authenticator app.") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        GradientButton(if (needsTotp) "Verify and sign in" else "Sign in", busy, enabled = email.isNotBlank() && password.isNotEmpty(), onClick = ::submit)
        TextButton(onClick = onRegister, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Create an account") }
        if (editingServer) {
            OutlinedTextField(
                server, { server = it }, label = { Text("Server") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("For a self-hosted Neutrino. From the emulator, your computer is http://10.0.2.2:8080.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        } else {
            TextButton(onClick = { editingServer = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Server: ${server.removePrefix("https://")}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * Sign-up, then straight into the account: `POST /auth/register` creates the user and nothing
 * else, so the session comes from the normal sign-in. A failure *after* the account exists says so —
 * telling someone "registration failed" when it did not would send them round to make a second one.
 */
@Composable
fun RegisterScreen(onBack: () -> Unit, onRegistered: () -> Unit) {
    val graph = LocalNeutrinoAccount.current
    val brand = LocalNeutrinoBrand.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val problem = when {
        password.isNotEmpty() && password.length < 8 -> "Use at least 8 characters."
        confirm.isNotEmpty() && confirm != password -> "The passwords do not match."
        else -> null
    }
    val ready = name.isNotBlank() && email.contains('@') && password.length >= 8 && password == confirm

    AuthScaffold {
        Text("Create your account", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            brand.registerSubtitle,
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        PasswordField(password, { password = it }, "Password")
        PasswordField(confirm, { confirm = it }, "Confirm password", imeAction = ImeAction.Done)
        (error ?: problem)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        GradientButton("Create account", busy, enabled = ready) {
            busy = true
            error = null
            scope.launch {
                var created = false
                try {
                    graph.auth.register(name, email, password)
                    created = true
                    onRegistered()
                    graph.onSignedIn()
                } catch (e: Exception) {
                    error = if (created) "Your account was created, but signing in failed: ${e.message}" else e.message
                } finally {
                    busy = false
                }
            }
        }
        TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("I already have an account") }
    }
}

@Composable
private fun AuthScaffold(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
fun BrandHeader() {
    val brand = LocalNeutrinoBrand.current
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painterResource(brand.icon), contentDescription = null,
            modifier = Modifier.size(88.dp).clip(RoundedCornerShape(22.dp)),
        )
        Text(brand.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
        Text(
            brand.tagline, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TrustRow(icon: ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onValueChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (visible) "Hide password" else "Show password")
            }
        },
    )
}

@Composable
fun GradientButton(text: String, busy: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled && !busy,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        else Text(text, style = MaterialTheme.typography.titleMedium)
    }
}
