package com.vayunmathur.communicate.signal

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.signal.SignalContactSync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wave 8 (w6 slice 2/6): [SignalContactSync]'s pure helpers — E.164 normalization/encoding plus the
 * default-region fallback. The device-read and enclave paths need permissions, contacts and SGX;
 * what is testable on JVM is the number shaping every sync funnels through.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignalSlice2ContactSyncTest {

    @Test
    fun normalizeE164_formatsUsNumbers() {
        assertEquals("+14155552671", SignalContactSync.normalizeE164("+14155552671", "US"))
        assertEquals("+14155552671", SignalContactSync.normalizeE164("(415) 555-2671", "US"))
        assertEquals("+14155552671", SignalContactSync.normalizeE164("4155552671", "US"))
    }

    @Test
    fun normalizeE164_rejectsJunk() {
        assertNull(SignalContactSync.normalizeE164("", "US"))
        assertNull(SignalContactSync.normalizeE164("not a number", "US"))
        assertNull(SignalContactSync.normalizeE164("123", "US"))
    }

    @Test
    fun e164Uint64_roundTrips() {
        val bytes = SignalContactSync.e164ToUint64Bytes("+14155552671")
        assertEquals(8, bytes.size)
        var num = 0L
        for (b in bytes) num = (num shl 8) or (b.toLong() and 0xFF)
        assertEquals("+14155552671", SignalContactSync.uint64ToE164(num))
    }

    @Test
    fun e164Uint64_digitsOnly() {
        // Formatting characters are stripped before encoding.
        assertTrue(
            SignalContactSync.e164ToUint64Bytes("+1 (415) 555-2671")
                .contentEquals(SignalContactSync.e164ToUint64Bytes("+14155552671")),
        )
    }

    @Test
    fun syncResult_defaults() {
        val r = SignalContactSync.SyncResult(10, 8, 3)
        assertEquals(10, r.deviceCount)
        assertEquals(8, r.e164Count)
        assertEquals(3, r.onSignalCount)
        assertNull(r.transportError)
    }

    @Test
    fun defaultRegion_returnsNonBlank() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val region = SignalContactSync.defaultRegion(ctx)
        assertTrue(region.isNotBlank())
        assertNotNull(region)
        assertFalse(region.any { it.isLowerCase() })
    }
}
