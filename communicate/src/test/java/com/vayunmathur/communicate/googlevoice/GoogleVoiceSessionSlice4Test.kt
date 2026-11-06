package com.vayunmathur.communicate.googlevoice

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Slice-4 coverage for the Google Voice session store ([GoogleVoiceSession]).
 *
 * Robolectric with a real DataStore: persists, reads back, and clears the web
 * session material, so a lost-cookie / lost-key regression is caught without a
 * live sign-in.
 */
@RunWith(RobolectricTestRunner::class)
class GoogleVoiceSessionSlice4Test {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    // -- pure cookie parsing ------------------------------------------------------

    @Test
    fun extractSapisid_prefersPlainSapisidThenSecureVariants() {
        assertEquals(
            "plain1",
            GoogleVoiceSession.extractSapisid("OTHER=1; SAPISID=plain1; X=2"),
        )
        assertEquals(
            "sec3",
            GoogleVoiceSession.extractSapisid("OTHER=1; __Secure-3PAPISID=sec3"),
        )
        assertEquals(
            "sec1",
            GoogleVoiceSession.extractSapisid("__Secure-1PAPISID=sec1"),
        )
        assertNull(GoogleVoiceSession.extractSapisid("OTHER=1; X=2"))
        assertNull(GoogleVoiceSession.extractSapisid(""))
        assertNull(GoogleVoiceSession.extractSapisid("SAPISID=; X=1"))
    }

    // -- persisted session -----------------------------------------------------------

    @Test
    fun freshSession_isSignedOutWithDefaults() = runTest {
        val session = GoogleVoiceSession.get(context)
        session.signOut()
        assertFalse(session.isSignedIn())
        assertNull(session.cookieHeader())
        assertNull(session.sapisid())
        assertNull(session.apiKey())
        assertEquals("0", session.authUser())
        assertNull(session.phoneNumber())
        assertEquals(0L, session.lastSeenMessageTimestampMillis())
        assertFalse(session.hasUsableCredentials())
        assertFalse(session.signedInFlow.first())
    }

    @Test
    fun save_persistsCredentialsAndMarksSignedIn() = runTest {
        val session = GoogleVoiceSession.get(context)
        session.signOut()
        session.save(
            cookieHeader = "SAPISID=sid9; OTHER=1",
            apiKey = "key1",
            authUser = "2",
        )
        assertTrue(session.isSignedIn())
        assertEquals("SAPISID=sid9; OTHER=1", session.cookieHeader())
        assertEquals("sid9", session.sapisid())
        assertEquals("key1", session.apiKey())
        assertEquals("2", session.authUser())
        assertTrue(session.hasUsableCredentials())
        assertTrue(session.signedInFlow.first())
    }

    @Test
    fun save_acceptsAnExplicitSapisid() = runTest {
        val session = GoogleVoiceSession.get(context)
        session.signOut()
        session.save(cookieHeader = "OTHER=1", apiKey = "key1", authUser = "0", sapisid = "given")
        assertEquals("given", session.sapisid())
        assertTrue(session.hasUsableCredentials())
    }

    @Test
    fun updateCookies_refreshesCookieAndSapisid() = runTest {
        val session = GoogleVoiceSession.get(context)
        session.signOut()
        session.save(cookieHeader = "SAPISID=old; OTHER=1", apiKey = "key1", authUser = "0")
        session.updateCookies("SAPISID=new; OTHER=2")
        assertEquals("SAPISID=new; OTHER=2", session.cookieHeader())
        assertEquals("new", session.sapisid())
    }

    @Test
    fun lastSeenWatermark_roundTrips() = runTest {
        val session = GoogleVoiceSession.get(context)
        session.signOut()
        session.setLastSeenMessageTimestampMillis(1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, session.lastSeenMessageTimestampMillis())
    }

    @Test
    fun phoneNumber_roundTripsThroughFlow() = runTest {
        val session = GoogleVoiceSession.get(context)
        session.signOut()
        session.setPhoneNumber("+1555")
        assertEquals("+1555", session.phoneNumber())
        assertEquals("+1555", session.phoneNumberFlow.first())
    }

    @Test
    fun signOut_clearsEverything() = runTest {
        val session = GoogleVoiceSession.get(context)
        session.save(cookieHeader = "SAPISID=s; O=1", apiKey = "k", authUser = "1")
        session.setPhoneNumber("+1555")
        session.setLastSeenMessageTimestampMillis(9L)
        session.signOut()
        assertFalse(session.isSignedIn())
        assertFalse(session.hasUsableCredentials())
        assertNull(session.cookieHeader())
        assertNull(session.phoneNumber())
        assertEquals(0L, session.lastSeenMessageTimestampMillis())
    }
}
