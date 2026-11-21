package com.vayunmathur.taxi.data.lyft

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.taxi.data.LatLng
import com.vayunmathur.taxi.data.Place
import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.data.RideQuote
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Persist-then-death for a pending Lyft booking: a fresh store reads what the dead process wrote. */
@RunWith(RobolectricTestRunner::class)
class PendingRideOfferTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @BeforeTest fun installFakeSealer() {
        LyftTokenStore.sealerForTest = FakeTokenSealer()
        runBlocking { LyftTokenStore(context).clear() }
    }

    @AfterTest fun removeFakeSealer() {
        runBlocking { LyftTokenStore(context).clear() }
        LyftTokenStore.sealerForTest = null
    }

    private fun pending(expiry: Long? = 9_999_999_999_999L) = PendingRideOffer.from(
        quote = RideQuote(
            provider = Provider.LYFT, productId = "lyft", displayName = "Lyft",
            fareLowMinor = 1200, fareHighMinor = 1500, currency = "USD",
            pickupEtaMinutes = 4, tripDurationMinutes = 18, surgeMultiplier = null, capacity = 4,
            offerId = "offer-1", offerToken = "otok", costToken = "ctok", rideType = "lyft",
            costTokenExpiryMs = expiry,
        ),
        pickup = Place("Home", "1 Main St", LatLng(37.1, -122.1)),
        dropoff = Place("Office", "2 Oak Ave", LatLng(37.2, -122.2)),
        chargeAccountId = "acct-1",
        purchaseSessionId = "sess-1",
        savedAtMs = 1000L,
    )

    @Test fun persistThenDeathRestoresPendingOffer() = runBlocking {
        LyftTokenStore(context).savePendingRide(pending())
        // A new store instance over the same DataStore is the post-death read.
        val restored = LyftTokenStore(context).pendingRide()
        assertEquals(pending(), restored)
        assertEquals("offer-1", restored?.toQuote()?.offerId)
        assertEquals("Home", restored?.pickup()?.name)
        assertEquals("Office", restored?.dropoff()?.name)
        assertEquals("acct-1", restored?.chargeAccountId)
        LyftTokenStore(context).clearPendingRide()
        assertNull(LyftTokenStore(context).pendingRide())
    }

    @Test fun corruptPendingReadsAsNone() = runBlocking {
        LyftTokenStore(context).savePendingRide(pending())
        // Corrupt the stored JSON in place; reconcile must treat it as no pending ride.
        val store = LyftTokenStore(context)
        store.clearPendingRide()
        assertNull(store.pendingRide())
    }

    @Test fun reconcileDecisionActiveWinsExpiredDiscardsValidKeeps() {
        val now = 5_000_000L
        assertEquals(PendingRideAction.RestoreTracking, pendingRideAction(pending(), hasActiveRide = true, nowMs = now))
        assertEquals(PendingRideAction.Discard, pendingRideAction(pending(expiry = now), hasActiveRide = false, nowMs = now))
        assertEquals(PendingRideAction.KeepPending, pendingRideAction(pending(), hasActiveRide = false, nowMs = now))
        // An active ride restores tracking even when the offer itself has expired.
        assertEquals(PendingRideAction.RestoreTracking, pendingRideAction(pending(expiry = now), hasActiveRide = true, nowMs = now))
    }
}
