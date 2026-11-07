package com.vayunmathur.sdk.cast

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Playback bundle round-trips, picker contract, client failure paths, exceptions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SdkCastClientTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun playbackStateBundleRoundTrip() {
        val state = PlaybackState(
            positionMs = 61_000, durationMs = 240_000, playing = true, buffering = true,
            speed = 1.5f, volume = 0.4f, hasNext = true, hasPrevious = false, ended = false,
        )
        assertEquals(state, PlaybackState.from(state.toBundle()))
        // Defaults: a bare bundle reads as position 0, not playing, 1x speed, full volume.
        val bare = PlaybackState.from(Bundle())
        assertEquals(0L, bare.positionMs)
        assertFalse(bare.playing)
        assertEquals(1f, bare.speed)
        assertEquals(1f, bare.volume)
        assertFalse(bare.ended)
    }

    @Test
    fun playbackCommandBundleRoundTripAndUnknownAction() {
        val seek = PlaybackCommand(PlaybackAction.SeekTo, 90_000.0)
        assertEquals(seek, PlaybackCommand.from(seek.toBundle()))
        val play = PlaybackCommand(PlaybackAction.Play)
        val restored = PlaybackCommand.from(play.toBundle())
        assertEquals(PlaybackAction.Play, restored?.action)
        assertNull(restored?.value)
        assertNull(PlaybackCommand.from(null))
        // Unknown wire action reads as null, never as a guessed action.
        val unknown = Bundle().apply { putInt(CastContract.KEY_ACTION, 9999) }
        assertNull(PlaybackCommand.from(unknown))
        assertNull(PlaybackCommand.from(Bundle()))
    }

    @Test
    fun pickerContractIntentAndResultParsing() {
        val contract = CastPickerContract()
        val intent: Intent = contract.createIntent(context, Unit)
        assertEquals(CastContract.CAST_PACKAGE, intent.component?.packageName)
        assertEquals(CastContract.PICKER_ACTIVITY, intent.component?.className)
        assertTrue(contract.parseResult(Activity.RESULT_OK, null))
        assertFalse(contract.parseResult(Activity.RESULT_CANCELED, null))
        assertFalse(contract.parseResult(42, null))
    }

    @Test
    fun supportIsNotInstalledWithoutCastApp() {
        val client = CastClient(context)
        assertEquals(CastClient.Support.NOT_INSTALLED, client.support())
        // Boundary logic itself (also covered by CastContractTest, pinned here at the client).
        assertEquals(CastClient.Support.NOT_INSTALLED, CastClient.supportFor(null))
        assertEquals(
            CastClient.Support.NEEDS_UPDATE,
            CastClient.supportFor(CastContract.MIN_CAST_VERSION_CODE - 1),
        )
        assertEquals(CastClient.Support.READY, CastClient.supportFor(CastContract.MIN_CAST_VERSION_CODE))
        assertEquals(20_000L, CastClient.OPEN_TIMEOUT_MS)
        assertEquals(30, CastClient.DEFAULT_FRAME_RATE)
    }

    @Test
    fun openSessionThrowsNotInstalledAndCloseIsIdempotent() = runBlocking {
        val client = CastClient(context)
        assertFailsWith<CastNotInstalledException> { client.openSession(1280, 720, wantAudio = true) }
        assertFailsWith<CastNotInstalledException> {
            client.openContentSession(CastResourceProvider { null })
        }
        // Silent no-ops before any session: none of these throw.
        client.reportPlaybackState(PlaybackState(0, 0, playing = false))
        client.play("res-1", "audio/ogg")
        client.sendCommand(PlaybackCommand(PlaybackAction.Pause))
        client.setNowPlaying(title = "Song")
        client.resourceComplete("res-1", 1234)
        client.close()
        client.close()
    }

    @Test
    fun sessionHoldersCarryGrantedValues() {
        // Surface cannot be constructed meaningfully under Robolectric; the holders'
        // plain fields are pinned through ContentSession, which needs no Surface.
        val session = CastClient.ContentSession(receiverName = "Living Room TV", hasVideo = false)
        assertEquals("Living Room TV", session.receiverName)
        assertFalse(session.hasVideo)
    }

    @Test
    fun exceptionMessagesAndHierarchy() {
        assertEquals("MA Cast is not installed", CastNotInstalledException().message)
        assertEquals("MA Cast is too old to stream app content", CastNeedsUpdateException().message)
        assertEquals("Not permitted to bind the Cast content service", CastPermissionException().message)
        assertEquals("No TV session; launch the picker first", CastNoSessionException().message)
        assertEquals("Cast could not start a session (reason 7)", CastSessionFailedException(7).message)
        assertTrue(CastNotInstalledException() is CastException)
        assertTrue(CastSessionFailedException(1) is CastException)
    }

    @Test
    fun resourceProviderIsFunctionalInterface() {
        val provider = CastResourceProvider { null }
        assertNull(provider.open("anything"))
        val named = CastResourceProvider { id -> if (id == "known") null else null }
        assertNull(named.open("known"))
    }
}
