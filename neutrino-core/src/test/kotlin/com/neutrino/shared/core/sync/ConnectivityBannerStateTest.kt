package com.neutrino.shared.core.sync

import com.neutrino.shared.core.sync.ConnectivityBannerState.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The same cases, and the same copy, as `ConnectivityBannerStateTests` in neutrino_shared_ios. */
class ConnectivityBannerStateTest {

    private fun resolve(
        online: Boolean = true,
        pending: Int = 0,
        activity: SyncActivity = SyncActivity.Idle,
        keeps: Boolean = true,
        reconnected: Boolean = false,
    ) = ConnectivityBannerState.resolve(online, pending, activity, keeps, reconnected)

    @Test
    fun onlineAndIdleSaysNothing() = assertNull(resolve())

    @Test
    fun offlinePromisesToSyncOnlyWhenTheAppQueues() {
        assertEquals("You're offline. Changes will sync when you reconnect.", resolve(online = false)?.message)
        assertEquals("You're offline. Showing what's saved on this device.", resolve(online = false, keeps = false)?.message)
    }

    @Test
    fun offlineCountsWhatIsQueued() {
        assertEquals("You're offline. 1 change will sync when you reconnect.", resolve(online = false, pending = 1)?.message)
        assertEquals("You're offline. 3 changes will sync when you reconnect.", resolve(online = false, pending = 3)?.message)
    }

    @Test
    fun offlineWinsOverEverythingAndOffersNoAction() {
        val state = resolve(online = false, pending = 2, activity = SyncActivity.Failed("boom"), reconnected = true)
        assertEquals(Kind.Offline, state?.kind)
        assertNull(state?.actionTitle)
    }

    @Test
    fun syncingCountsRemainingThenFallsBackToPending() {
        assertEquals("Syncing 2 changes…", resolve(activity = SyncActivity.Syncing(2))?.message)
        assertEquals("Syncing 1 change…", resolve(pending = 1, activity = SyncActivity.Syncing(0))?.message)
        assertEquals("Syncing…", resolve(activity = SyncActivity.Syncing())?.message)
        assertNull(resolve(activity = SyncActivity.Syncing(2))?.actionTitle)
    }

    @Test
    fun failedShowsTheReasonAndOffersRetry() {
        val state = resolve(pending = 1, activity = SyncActivity.Failed("Server error (500)."))
        assertEquals(Kind.Failed, state?.kind)
        assertEquals("Couldn't sync: Server error (500).", state?.message)
        assertEquals("Retry", state?.actionTitle)
        assertEquals("Couldn't sync.", resolve(activity = SyncActivity.Failed("  "))?.message)
    }

    @Test
    fun waitingOffersSyncNow() {
        val state = resolve(pending = 4)
        assertEquals(Kind.Waiting, state?.kind)
        assertEquals("4 changes waiting to sync.", state?.message)
        assertEquals("Sync Now", state?.actionTitle)
    }

    @Test
    fun reconnectedOnlyWhenThereIsNothingElseToSay() {
        assertEquals(Kind.Reconnected, resolve(reconnected = true)?.kind)
        assertEquals(Kind.Waiting, resolve(pending = 1, reconnected = true)?.kind)
        assertEquals(Kind.Syncing, resolve(activity = SyncActivity.Syncing(1), reconnected = true)?.kind)
    }
}
