package com.vayunmathur.sdk.cast

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A fake Cast service behind Robolectric's bindService shadow: the client binds, sends
 * MSG_OPEN_SESSION to this Messenger, and everything the client sends afterwards is
 * recorded so the tests can assert the exact wire bundles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CastClientSessionTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val app get() = context.applicationContext as android.app.Application

    /** Everything the fake service received, in order. */
    private val received = mutableListOf<Message>()
    private var clientMessenger: Messenger? = null
    private var resourceResponse: Message? = null
    private val resourceLatch = CountDownLatch(1)

    private fun idleMain() {
        ShadowLooper.idleMainLooper()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    /** The resource reply arrives via the client's background executor + main looper. */
    private fun awaitResourceResponse(): Boolean {
        var guard = 0
        while (guard++ < 200) {
            idleMain()
            if (resourceLatch.await(50, TimeUnit.MILLISECONDS)) return true
        }
        return false
    }

    private fun installCast(versionCode: Long) {
        val info = PackageInfo().apply {
            packageName = CastContract.CAST_PACKAGE
            setLongVersionCode(versionCode)
            applicationInfo = android.content.pm.ApplicationInfo().apply {
                packageName = CastContract.CAST_PACKAGE
            }
        }
        Shadows.shadowOf(context.packageManager).installPackage(info)
    }

    /** A Messenger standing in for ContentCastService, answering opens as [answerOpen] does. */
    private fun fakeService(answerOpen: (Messenger?) -> Unit): Messenger {
        val handler = Handler(Looper.getMainLooper()) { msg ->
            // Copy: Message objects are recycled by the framework after dispatch.
            val copy = Message.obtain().apply {
                what = msg.what
                data = msg.data?.let { Bundle(it) }
                replyTo = msg.replyTo
            }
            received += copy
            when (msg.what) {
                CastContract.MSG_OPEN_SESSION -> {
                    clientMessenger = msg.replyTo
                    answerOpen(msg.replyTo)
                }
                CastContract.MSG_RESOURCE_RESPONSE -> {
                    resourceResponse = copy
                    resourceLatch.countDown()
                }
            }
            true
        }
        return Messenger(handler)
    }

    private fun bindTo(service: Messenger) {
        val shadow = Shadows.shadowOf(app)
        val component = ComponentName(CastContract.CAST_PACKAGE, CastContract.SERVICE_CLASS)
        // Robolectric's bindService shadow: registering a binder for the component makes
        // bindService() deliver it to the client's ServiceConnection.
        val method = shadow.javaClass.methods.firstOrNull {
            it.name == "setComponentNameAndServiceForBindService" && it.parameterCount == 2
        } ?: shadow.javaClass.methods.first {
            it.name.contains("BindService") && it.parameterCount >= 2
        }
        if (method.parameterCount == 2) {
            method.invoke(shadow, component, service.binder)
        } else {
            val intent = android.content.Intent().setComponent(component)
            method.invoke(shadow, intent, component, service.binder)
        }
    }

    private fun readyBundle(withSurface: Boolean): Bundle = Bundle().apply {
        if (withSurface) {
            putParcelable(
                CastContract.KEY_SURFACE,
                Surface(android.graphics.SurfaceTexture(0)),
            )
        }
        putInt(CastContract.KEY_GRANTED_WIDTH, 1920)
        putInt(CastContract.KEY_GRANTED_HEIGHT, 1080)
        putInt(CastContract.KEY_GRANTED_FRAME_RATE, 60)
        putString(CastContract.KEY_RECEIVER_NAME, "Living Room TV")
    }

    private fun sendToClient(what: Int, data: Bundle?) {
        val reply = Message.obtain(null, what).apply { this.data = data }
        clientMessenger!!.send(reply)
        idleMain()
    }

    @Test fun supportNeedsUpdateForOldCast() {
        installCast(CastContract.MIN_CAST_VERSION_CODE - 1)
        assertEquals(CastClient.Support.NEEDS_UPDATE, CastClient(context).support())
    }

    @Test fun openSessionFailsWithNeedsUpdateBeforeBinding() = runBlocking {
        installCast(CastContract.MIN_CAST_VERSION_CODE - 1)
        val client = CastClient(context)
        assertFailsWith<CastNeedsUpdateException> { client.openSession(640, 480, wantAudio = false) }
        Unit
    }

    @Test fun openSessionReturnsGrantedGeometryAndSendsRequestedSize() = runBlocking {
        installCast(CastContract.MIN_CAST_VERSION_CODE)
        bindTo(fakeService { replyTo ->
            val ready = Message.obtain(null, CastContract.MSG_SESSION_READY).apply {
                data = readyBundle(withSurface = true)
            }
            replyTo!!.send(ready)
        })
        val client = CastClient(context)
        val deferred = async(Dispatchers.Default) { client.openSession(1280, 720, wantAudio = true) }
        var guard = 0
        var session: CastClient.Session? = null
        while (session == null && guard++ < 200) {
            idleMain()
            session = withTimeoutOrNull(50) { deferred.await() }
            if (session == null) Thread.sleep(10)
        }
        val opened = assertNotNull(session, "session never became ready")
        assertEquals(1920, opened.width)
        assertEquals(1080, opened.height)
        assertEquals(60, opened.frameRate)
        assertEquals("Living Room TV", opened.receiverName)
        assertNotNull(opened.surface)
        assertNull(opened.audio)

        val open = received.first { it.what == CastContract.MSG_OPEN_SESSION }
        assertEquals(1280, open.data.getInt(CastContract.KEY_WIDTH))
        assertEquals(720, open.data.getInt(CastContract.KEY_HEIGHT))
        assertTrue(open.data.getBoolean(CastContract.KEY_WANT_AUDIO))
        assertTrue(open.data.getBoolean(CastContract.KEY_WANT_VIDEO))
        assertFalse(open.data.getBoolean(CastContract.KEY_SERVE_RESOURCES))

        // While open, playback reports go out on the wire; after close they stop.
        client.reportPlaybackState(PlaybackState(5_000, 60_000, playing = true))
        idleMain()
        val report = received.last { it.what == CastContract.MSG_PLAYBACK_STATE }
        assertEquals(5_000L, report.data.getLong(CastContract.KEY_POSITION_MS))

        client.close()
        idleMain()
        assertTrue(received.any { it.what == CastContract.MSG_CLOSE_SESSION })
        val countAfterClose = received.size
        client.reportPlaybackState(PlaybackState(9_000, 60_000, playing = true))
        idleMain()
        assertEquals(countAfterClose, received.size)
        Unit
    }

    @Test fun openSessionWithoutSurfaceInReadyBundleFails() = runBlocking {
        installCast(CastContract.MIN_CAST_VERSION_CODE)
        bindTo(fakeService { replyTo ->
            val ready = Message.obtain(null, CastContract.MSG_SESSION_READY).apply {
                data = readyBundle(withSurface = false)
            }
            replyTo!!.send(ready)
        })
        val client = CastClient(context)
        val deferred = async(Dispatchers.Default) {
            runCatching { client.openSession(800, 600, wantAudio = false) }
        }
        var guard = 0
        var result: Result<CastClient.Session>? = null
        while (result == null && guard++ < 200) {
            idleMain()
            result = withTimeoutOrNull(50) { deferred.await() }
            if (result == null) Thread.sleep(10)
        }
        assertTrue(assertNotNull(result).exceptionOrNull() is CastSessionFailedException)
        Unit
    }

    @Test fun endedBeforeReadyFailsTheOpenWithTypedReason() = runBlocking {
        installCast(CastContract.MIN_CAST_VERSION_CODE)
        bindTo(fakeService { replyTo ->
            val ended = Message.obtain(null, CastContract.MSG_SESSION_ENDED).apply {
                data = Bundle().apply { putInt(CastContract.KEY_END_REASON, CastContract.REASON_NO_SESSION) }
            }
            replyTo!!.send(ended)
        })
        val client = CastClient(context)
        val deferred = async(Dispatchers.Default) {
            runCatching { client.openSession(800, 600, wantAudio = false) }
        }
        var guard = 0
        var result: Result<CastClient.Session>? = null
        while (result == null && guard++ < 200) {
            idleMain()
            result = withTimeoutOrNull(50) { deferred.await() }
            if (result == null) Thread.sleep(10)
        }
        assertTrue(assertNotNull(result).exceptionOrNull() is CastNoSessionException)
        Unit
    }

    /** An opened content session plus the knobs its test needs. */
    private inner class OpenedContent(
        val client: CastClient,
        val session: CastClient.ContentSession,
        val tempFile: File,
    ) {
        var openedId: String? = null
    }

    private suspend fun openContent(tempFile: File): OpenedContent {
        installCast(CastContract.MIN_CAST_VERSION_CODE)
        bindTo(fakeService { replyTo ->
            val ready = Message.obtain(null, CastContract.MSG_SESSION_READY).apply {
                data = readyBundle(withSurface = false)
            }
            replyTo!!.send(ready)
        })
        lateinit var fixture: OpenedContent
        val provider = CastResourceProvider { id ->
            fixture.openedId = id
            CastResource(
                descriptor = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY),
                length = tempFile.length(),
                contentType = "audio/ogg",
            )
        }
        val client = CastClient(context)
        val session = kotlinx.coroutines.coroutineScope {
            val deferred = async(Dispatchers.Default) {
                client.openContentSession(provider, video = false, wantAudio = true)
            }
            var guard = 0
            var opened: CastClient.ContentSession? = null
            while (opened == null && guard++ < 200) {
                idleMain()
                opened = withTimeoutOrNull(50) { deferred.await() }
                if (opened == null) Thread.sleep(10)
            }
            opened
        }
        fixture = OpenedContent(client, assertNotNull(session), tempFile)
        return fixture
    }

    private fun tempResourceFile(): File = File.createTempFile("cast-resource", ".bin").apply {
        writeBytes(ByteArray(2048) { (it % 251).toByte() })
        deleteOnExit()
    }

    @Test fun contentSessionSendsFullOutgoingBundles() = runBlocking {
        val fixture = openContent(tempResourceFile())
        val client = fixture.client
        assertEquals("Living Room TV", fixture.session.receiverName)
        assertFalse(fixture.session.hasVideo)
        val open = received.first { it.what == CastContract.MSG_OPEN_SESSION }
        assertTrue(open.data.getBoolean(CastContract.KEY_SERVE_RESOURCES))
        assertFalse(open.data.getBoolean(CastContract.KEY_WANT_VIDEO))

        client.play("track-1", "audio/ogg", durationMs = 180_000, startPositionMs = 12_000)
        client.sendCommand(PlaybackCommand(PlaybackAction.SeekTo, 30_000.0))
        client.setNowPlaying(
            title = "Song", author = "Singer", resourceId = "track-1",
            lyricTimesMs = longArrayOf(0, 1_000), lyricTexts = arrayOf("one", "two"),
        )
        client.resourceComplete("track-1", fixture.tempFile.length())
        client.resourceComplete("failed-1", -1)
        idleMain()
        val play = received.first { it.what == CastContract.MSG_PLAY_MEDIA }
        assertEquals("track-1", play.data.getString(CastContract.KEY_RESOURCE_ID))
        assertEquals(12_000L, play.data.getLong(CastContract.KEY_START_POSITION_MS))
        val command = received.first { it.what == CastContract.MSG_SEND_PLAYBACK_COMMAND }
        assertEquals(CastContract.ACTION_SEEK_TO, command.data.getInt(CastContract.KEY_ACTION))
        val nowPlaying = received.first { it.what == CastContract.MSG_SET_NOW_PLAYING }
        assertEquals("Song", nowPlaying.data.getString(CastContract.KEY_TITLE))
        // A completed resource carries its length; a failed one has no length key at all.
        assertTrue(received.any {
            it.what == CastContract.MSG_RESOURCE_COMPLETE &&
                it.data.getString(CastContract.KEY_RESOURCE_ID) == "track-1" &&
                it.data.containsKey(CastContract.KEY_RESOURCE_LENGTH)
        })
        assertTrue(received.any {
            it.what == CastContract.MSG_RESOURCE_COMPLETE &&
                it.data.getString(CastContract.KEY_RESOURCE_ID) == "failed-1" &&
                !it.data.containsKey(CastContract.KEY_RESOURCE_LENGTH)
        })
        client.close()
        Unit
    }

    @Test fun contentSessionServesResourcesAndCarriesTvPlaybackBothWays() = runBlocking {
        val fixture = openContent(tempResourceFile())
        val client = fixture.client
        var endedReason: Int? = null
        client.onEnded = { endedReason = it }
        var tvState: PlaybackState? = null
        client.onPlaybackState = { tvState = it }
        var tvCommand: PlaybackCommand? = null
        client.onCommand = { tvCommand = it }

        // The service asks for the resource; the provider answers off the main thread and
        // the client replies with descriptor, length and MIME type, then closes its copy.
        sendToClient(
            CastContract.MSG_RESOURCE_REQUEST,
            Bundle().apply {
                putInt(CastContract.KEY_REQUEST_ID, 7)
                putString(CastContract.KEY_RESOURCE_ID, "track-1")
            },
        )
        assertTrue(awaitResourceResponse(), "no resource response")
        assertEquals("track-1", fixture.openedId)
        val response = assertNotNull(resourceResponse)
        assertEquals(7, response.data.getInt(CastContract.KEY_REQUEST_ID))
        assertEquals(fixture.tempFile.length(), response.data.getLong(CastContract.KEY_RESOURCE_LENGTH))
        assertEquals("audio/ogg", response.data.getString(CastContract.KEY_RESOURCE_TYPE))

        // TV-originated playback and remote presses reach the callbacks only while open.
        sendToClient(
            CastContract.MSG_TV_PLAYBACK_STATE,
            PlaybackState(42_000, 180_000, playing = true).toBundleForTest(),
        )
        assertEquals(42_000L, assertNotNull(tvState).positionMs)
        sendToClient(
            CastContract.MSG_PLAYBACK_COMMAND,
            PlaybackCommand(PlaybackAction.Pause).toBundleForTest(),
        )
        assertEquals(PlaybackAction.Pause, assertNotNull(tvCommand).action)

        sendToClient(
            CastContract.MSG_SESSION_ENDED,
            Bundle().apply { putInt(CastContract.KEY_END_REASON, CastContract.REASON_RECEIVER_GONE) },
        )
        assertEquals(CastContract.REASON_RECEIVER_GONE, endedReason)
        // After the session ends, late TV snapshots are dropped, not delivered.
        tvState = null
        sendToClient(
            CastContract.MSG_TV_PLAYBACK_STATE,
            PlaybackState(99_000, 180_000, playing = true).toBundleForTest(),
        )
        assertNull(tvState)
        client.close()
        Unit
    }

    @Test fun resourceRequestWithUnknownIdStillAnswersWithEmptyResponse() = runBlocking {
        installCast(CastContract.MIN_CAST_VERSION_CODE)
        bindTo(fakeService { replyTo ->
            val ready = Message.obtain(null, CastContract.MSG_SESSION_READY).apply {
                data = readyBundle(withSurface = false)
            }
            replyTo!!.send(ready)
        })
        val client = CastClient(context)
        val deferred = async(Dispatchers.Default) {
            client.openContentSession(CastResourceProvider { null }, video = true)
        }
        var guard = 0
        var session: CastClient.ContentSession? = null
        while (session == null && guard++ < 200) {
            idleMain()
            session = withTimeoutOrNull(50) { deferred.await() }
            if (session == null) Thread.sleep(10)
        }
        assertNotNull(session)
        sendToClient(
            CastContract.MSG_RESOURCE_REQUEST,
            Bundle().apply {
                putInt(CastContract.KEY_REQUEST_ID, 3)
                putString(CastContract.KEY_RESOURCE_ID, "gone")
            },
        )
        // A null payload and a request with no resource id are ignored outright.
        sendToClient(CastContract.MSG_RESOURCE_REQUEST, Bundle())
        assertTrue(awaitResourceResponse(), "declined resource must still be answered")
        val response = assertNotNull(resourceResponse)
        assertEquals(3, response.data.getInt(CastContract.KEY_REQUEST_ID))
        assertFalse(response.data.containsKey(CastContract.KEY_RESOURCE_LENGTH))
        client.close()
        Unit
    }
}

/** PlaybackState/Command bundles are internal to the SDK; rebuild them from contract keys. */
private fun PlaybackState.toBundleForTest(): Bundle = Bundle().apply {
    putLong(CastContract.KEY_POSITION_MS, positionMs)
    putLong(CastContract.KEY_DURATION_MS, durationMs)
    putBoolean(CastContract.KEY_PLAYING, playing)
    putBoolean(CastContract.KEY_BUFFERING, buffering)
    putFloat(CastContract.KEY_SPEED, speed)
    putFloat(CastContract.KEY_VOLUME, volume)
    putBoolean(CastContract.KEY_HAS_NEXT, hasNext)
    putBoolean(CastContract.KEY_HAS_PREVIOUS, hasPrevious)
    putBoolean(CastContract.KEY_ENDED, ended)
}

private fun PlaybackCommand.toBundleForTest(): Bundle = Bundle().apply {
    // The wire value is internal; ACTION_PAUSE's constant is the public wire definition.
    putInt(CastContract.KEY_ACTION, CastContract.ACTION_PAUSE)
}
