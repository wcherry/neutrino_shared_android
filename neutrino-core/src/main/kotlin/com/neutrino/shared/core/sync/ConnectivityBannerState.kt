package com.neutrino.shared.core.sync

/**
 * What an app's sync queue is doing, in the terms the connectivity banner needs. Each app's engine
 * has its own richer state and maps it down to this, so every app's banner reads the same.
 */
sealed interface SyncActivity {
    data object Idle : SyncActivity
    /** Changes are being sent; [remaining] is what is left, or 0 when the engine cannot say. */
    data class Syncing(val remaining: Int = 0) : SyncActivity
    /** The last pass ended with a failure the user may want to retry. */
    data class Failed(val reason: String) : SyncActivity
}

/**
 * The one line at the top of the app that says whether it is online and what the sync queue is
 * doing — or null, when there is nothing worth saying. The copy is the iOS `ConnectivityBannerState`'s
 * word for word, so the two platforms read the same. In priority order: offline, syncing, failed
 * (with Retry), changes waiting (with Sync Now), and briefly "Back online".
 */
data class ConnectivityBannerState(
    val kind: Kind,
    val message: String,
    /** The button's title, when the banner offers one. Its action is always "sync now". */
    val actionTitle: String? = null,
) {
    enum class Kind { Offline, Syncing, Failed, Waiting, Reconnected }

    companion object {
        /**
         * @param pendingChanges changes queued on the device and not yet on the server.
         * @param keepsChangesOffline whether this app queues edits made offline. Drive does not, and
         *   must not promise to.
         * @param justReconnected the banner's "came back a moment ago" flag.
         */
        fun resolve(
            isOnline: Boolean,
            pendingChanges: Int = 0,
            activity: SyncActivity = SyncActivity.Idle,
            keepsChangesOffline: Boolean = true,
            justReconnected: Boolean = false,
        ): ConnectivityBannerState? {
            val pending = pendingChanges.coerceAtLeast(0)

            if (!isOnline) {
                val message = when {
                    !keepsChangesOffline -> "You're offline. Showing what's saved on this device."
                    pending > 0 -> "You're offline. ${changes(pending)} will sync when you reconnect."
                    else -> "You're offline. Changes will sync when you reconnect."
                }
                return ConnectivityBannerState(Kind.Offline, message)
            }

            when (activity) {
                is SyncActivity.Syncing -> {
                    val count = if (activity.remaining > 0) activity.remaining else pending
                    return ConnectivityBannerState(Kind.Syncing, if (count > 0) "Syncing ${changes(count)}…" else "Syncing…")
                }
                is SyncActivity.Failed -> {
                    val reason = activity.reason.trim()
                    return ConnectivityBannerState(
                        Kind.Failed,
                        if (reason.isEmpty()) "Couldn't sync." else "Couldn't sync: $reason",
                        actionTitle = "Retry",
                    )
                }
                SyncActivity.Idle -> Unit
            }

            if (pending > 0) return ConnectivityBannerState(Kind.Waiting, "${changes(pending)} waiting to sync.", actionTitle = "Sync Now")
            if (justReconnected) return ConnectivityBannerState(Kind.Reconnected, "Back online.")
            return null
        }

        private fun changes(count: Int) = if (count == 1) "1 change" else "$count changes"
    }
}
