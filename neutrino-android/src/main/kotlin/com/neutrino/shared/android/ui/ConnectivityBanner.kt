package com.neutrino.shared.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.neutrino.shared.core.sync.ConnectivityBannerState
import com.neutrino.shared.core.sync.SyncActivity
import kotlinx.coroutines.delay

/**
 * The strip at the top of the app that says it is offline, syncing, stuck, or back — the Android
 * counterpart of `ConnectivityBanner` in neutrino_shared_ios, with the same copy
 * ([ConnectivityBannerState]). It takes no space when there is nothing to say.
 *
 * "Back online" is kept here: reconnecting is a transition no monitor publishes, so it is noticed
 * by this composable and shown for a couple of seconds.
 */
@Composable
fun ConnectivityBanner(
    isOnline: Boolean,
    modifier: Modifier = Modifier,
    pendingChanges: Int = 0,
    activity: SyncActivity = SyncActivity.Idle,
    keepsChangesOffline: Boolean = true,
    onRetry: (() -> Unit)? = null,
) {
    // Set the first time the device is seen offline, so the first `true` at launch is not a reconnect.
    var hasBeenOffline by rememberSaveable { mutableStateOf(false) }
    var justReconnected by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(isOnline) {
        if (!isOnline) {
            hasBeenOffline = true
            justReconnected = false
            return@LaunchedEffect
        }
        if (!hasBeenOffline) return@LaunchedEffect
        hasBeenOffline = false
        justReconnected = true
        // Cancelled when connectivity changes again; that run owns the flag then.
        delay(2_500)
        justReconnected = false
    }

    val state = ConnectivityBannerState.resolve(isOnline, pendingChanges, activity, keepsChangesOffline, justReconnected)
    // What to draw while the banner animates out, when `state` is already null.
    val shown = remember { mutableStateOf<ConnectivityBannerState?>(null) }
    if (state != null) shown.value = state

    AnimatedVisibility(
        visible = state != null,
        modifier = modifier.fillMaxWidth(),
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        shown.value?.let { BannerRow(it, onRetry.takeIf { isOnline }) }
    }
}

@Composable
private fun BannerRow(state: ConnectivityBannerState, onRetry: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val (background, foreground) = when (state.kind) {
        ConnectivityBannerState.Kind.Failed -> colors.errorContainer to colors.onErrorContainer
        else -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("connectivityBanner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state.kind) {
            ConnectivityBannerState.Kind.Syncing ->
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.primary)
            else -> Icon(icon(state.kind), null, Modifier.size(16.dp), tint = tint(state.kind, foreground))
        }
        Text(
            state.message,
            style = MaterialTheme.typography.bodySmall,
            color = foreground,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        val title = state.actionTitle
        if (title != null && onRetry != null) {
            TextButton(onClick = onRetry, modifier = Modifier.testTag("connectivityBannerAction")) {
                Text(title, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private fun icon(kind: ConnectivityBannerState.Kind) = when (kind) {
    ConnectivityBannerState.Kind.Offline -> Icons.Filled.CloudOff
    ConnectivityBannerState.Kind.Failed -> Icons.Filled.Warning
    ConnectivityBannerState.Kind.Waiting -> Icons.Filled.Schedule
    ConnectivityBannerState.Kind.Reconnected, ConnectivityBannerState.Kind.Syncing -> Icons.Filled.Wifi
}

@Composable
private fun tint(kind: ConnectivityBannerState.Kind, foreground: Color) = when (kind) {
    ConnectivityBannerState.Kind.Reconnected -> Color(0xFF2E7D32)
    else -> foreground
}
