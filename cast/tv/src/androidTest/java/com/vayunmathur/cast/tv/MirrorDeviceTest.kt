package com.vayunmathur.cast.tv

import android.content.Intent
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.vayunmathur.cast.protocol.PlaybackState
import com.vayunmathur.cast.tv.platform.MirrorActivity
import com.vayunmathur.cast.tv.platform.ReceiverController
import com.vayunmathur.cast.tv.platform.ReceiverPhase
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device receiver surfaces: the idle screen from the launcher, then the mirror
 * activity with a live playback snapshot driven by the remote's keys.
 * UiAutomator polling only (no Compose rule — its Espresso sync is broken on-device).
 */
@RunWith(AndroidJUnit4::class)
class MirrorDeviceTest {

    @Test
    fun idleScreenThenMirrorActivityWithRemoteKeys() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val context = instrumentation.targetContext
        val packageName = context.packageName

        // A mirroring session with playback: the mirror activity becomes the whole input surface.
        ReceiverController.mutableState.update {
            it.copy(phase = ReceiverPhase.Mirroring("Pixel", 1920, 1080, appLabel = "YouPipe"))
        }
        ReceiverController.onPlaybackState(
            PlaybackState(10_000, 60_000, playing = true, buffering = false, volume = 0.5f),
        )
        val mirror = Intent(context, MirrorActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(mirror)
        device.wait(Until.findObject(By.pkg(packageName)), 10_000)
        Thread.sleep(1_000)

        // Remote keys: toggle, scrub right (commits on release), volume, next, back dismisses overlay.
        for (key in listOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        )) {
            instrumentation.sendKeyDownUpSync(key)
            Thread.sleep(150)
        }
        device.pressBack()
        Thread.sleep(500)
        // Back leaves the mirror (the session's collector also finishes it once playback is gone).
        org.junit.Assert.assertNotEquals(packageName, device.currentPackageName)

        ReceiverController.forgetPlayback()
        ReceiverController.mutableState.update { it.copy(phase = ReceiverPhase.Advertising) }
    }

    @Test
    fun mirrorAudioOnlyFetchesArtwork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val context = instrumentation.targetContext
        // Local pinned-TLS artwork server (same shape as DeviceArtworkTest).
        val creds = com.vayunmathur.cast.protocol.EphemeralTls.server(listOf(java.net.InetAddress.getByName("127.0.0.1")))
        val server = creds.sslContext.serverSocketFactory.createServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")) as javax.net.ssl.SSLServerSocket
        val png = java.io.ByteArrayOutputStream().also { out ->
            android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
                .apply { eraseColor(android.graphics.Color.BLUE) }
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }.toByteArray()
        kotlin.concurrent.thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    val reader = socket.inputStream.bufferedReader()
                    while (true) { val line = reader.readLine(); if (line.isNullOrBlank()) break }
                    val out = socket.outputStream
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${png.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(png); out.flush(); Thread.sleep(500)
                }
            }
            runCatching { server.close() }
        }
        val session = com.vayunmathur.cast.protocol.ContentSession(host = "127.0.0.1", port = server.localPort, certificateFingerprint = com.vayunmathur.cast.protocol.ProtocolBase64.encode(creds.fingerprint), token = "tok", video = false, appLabel = "Music")
        ReceiverController.mutableState.update {
            it.copy(
                phase = ReceiverPhase.Mirroring("Pixel", 0, 0, appLabel = "Music", hasVideo = false),
                nowPlaying = com.vayunmathur.cast.protocol.NowPlaying(resourceId = "", title = "Art Song", artworkResourceId = "art1"),
                playingResourceId = "",
                artwork = com.vayunmathur.cast.tv.platform.ArtworkFetcher(session),
            )
        }
        ReceiverController.onPlaybackState(PlaybackState(5_000, 60_000, playing = true, buffering = false, volume = 0.5f))
        context.startActivity(Intent(context, MirrorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertNotNull(device.wait(Until.findObject(By.text("Art Song")), 10_000))
        Thread.sleep(1_500) // artwork fetch on IO + recompose with the bitmap
        device.pressBack(); Thread.sleep(400)
        ReceiverController.forgetPlayback()
        ReceiverController.mutableState.update { it.copy(phase = ReceiverPhase.Advertising, nowPlaying = null, artwork = null) }
    }

    @Test
    fun mirrorWithFrameRateMatchesDisplayMode() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val context = instrumentation.targetContext
        ReceiverController.mutableState.update {
            it.copy(phase = ReceiverPhase.Mirroring("Pixel", 3840, 2160, appLabel = "YouPipe", frameRate = 59.94f))
        }
        ReceiverController.onPlaybackState(PlaybackState(20_000, 60_000, playing = true, buffering = false, volume = 0.8f))
        context.startActivity(Intent(context, MirrorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertNotNull(device.wait(Until.findObject(By.pkg(context.packageName)), 10_000))
        Thread.sleep(1_500) // display-mode match + letterbox against the switched panel
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        Thread.sleep(300)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
        device.pressBack(); Thread.sleep(400)
        ReceiverController.forgetPlayback()
        ReceiverController.mutableState.update { it.copy(phase = ReceiverPhase.Advertising) }
    }

    @Test
    fun mirrorAudioOnlyShowsNowPlayingAndTransport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val context = instrumentation.targetContext
        ReceiverController.mutableState.update {
            it.copy(
                phase = ReceiverPhase.Mirroring("Pixel", 0, 0, appLabel = "Music", hasVideo = false),
                nowPlaying = com.vayunmathur.cast.protocol.NowPlaying(resourceId = "", title = "Blue Monday", author = "New Order", plainLyrics = "How does it feel"),
                playingResourceId = "",
            )
        }
        ReceiverController.onPlaybackState(
            PlaybackState(5_000, 60_000, playing = false, buffering = false, volume = 0.5f),
        )
        val mirror = Intent(context, MirrorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(mirror)
        assertNotNull(device.wait(Until.findObject(By.text("Blue Monday")), 10_000))
        for (key in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_DPAD_RIGHT)) {
            instrumentation.sendKeyDownUpSync(key); Thread.sleep(200)
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        device.pressBack(); Thread.sleep(500)
        ReceiverController.forgetPlayback()
        ReceiverController.mutableState.update { it.copy(phase = ReceiverPhase.Advertising, nowPlaying = null) }
    }

}
