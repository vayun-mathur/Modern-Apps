package com.vayunmathur.youpipe.util.sabr

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrProtocolException

/**
 * Slice-1 (sabr/) second pass: SessionStore format selection exercised
 * through createSourceSpec (selection runs before the network fetch, which
 * fails closed against a config-less synthetic info), and the download
 * helper's entry path up to the token minter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SabrSlice1StoreDownloadTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun attempt(
        videoItag: Int, audioItag: Int, trackId: String?,
        formats: List<org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrInfo.Format>,
    ): IOException {
        val info = SabrSlice1ExtraTest.info("https://127.0.0.1:9/sabr", *formats.toTypedArray())
        return assertFailsWith<IOException> {
            SabrNgSessionStore.createSourceSpec("vidS1", videoItag, audioItag, trackId, info, null, Localization("en", "US"), null)
        }
    }

    @Test fun selectionRunsBeforeFailingInitFetch() {
        val audioLow = SabrSlice1ExtraTest.format(139, "audio/mp4")
        val audioHigh = SabrSlice1ExtraTest.format(140, "audio/mp4")
        val videoSmall = SabrSlice1ExtraTest.format(160, "video/mp4; codecs=\"avc1.4d401f\"")
        val videoBig = SabrSlice1ExtraTest.format(137, "video/mp4; codecs=\"avc1.640028\"")
        // Preferred itags present.
        var err = attempt(137, 140, null, listOf(audioLow, audioHigh, videoSmall, videoBig))
        assertTrue(err.message!!.contains("initialization") || err.message!!.contains("init"))
        // Preferred itags absent: smallest-height video + fallback audio selection still runs.
        err = attempt(999, 999, null, listOf(audioLow, audioHigh, videoSmall, videoBig))
        assertTrue(err.message!!.isNotBlank())
        // Preferred audio itag with a track-id constraint.
        err = attempt(137, 140, "en", listOf(audioLow, audioHigh, videoBig))
        assertTrue(err.message!!.isNotBlank())
        // Only one track each.
        err = attempt(160, 139, null, listOf(audioLow, videoSmall))
        assertTrue(err.message!!.isNotBlank())
    }

    @Test fun providerForceRefreshAndInvalidateOnFreshProvider() {
        SharedWebViewRuntime.get(ctx).warmUp()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(11, java.util.concurrent.TimeUnit.SECONDS)
        val provider = LocalDomPoTokenProvider(ctx)
        provider.invalidate() // no task yet: safe return
        // forceRefresh invalidates first, then the mint still fails against the dead runtime.
        assertFailsWith<org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrProtocolException> {
            provider.getPoTokenBytes("vidForce", forceRefresh = true)
        }
        provider.invalidate()
    }

    @Test fun downloadFailsAtTokenMintWithFakeHome() {
        // Fail the WebView runtime's init first (shadow WebView never runs its JS),
        // so the mint fails fast instead of waiting out the 60s ready timeout.
        SharedWebViewRuntime.get(ctx).warmUp()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(11, java.util.concurrent.TimeUnit.SECONDS)
        NewPipe.init(object : Downloader() {
            override fun execute(request: Request): Response =
                Response(200, null, null, SabrSlice1WebViewTest.homeHtml(), request.url)
        })
        val dir = File(ctx.cacheDir, "dl-${System.nanoTime()}").apply { mkdirs() }
        val info = SabrSlice1ExtraTest.info(
            "https://127.0.0.1:9/sabr",
            SabrSlice1ExtraTest.format(140, "audio/mp4"),
            SabrSlice1ExtraTest.format(137, "video/mp4; codecs=\"avc1.640028\""),
        )
        assertFailsWith<SabrProtocolException> {
            SabrNgDownloadHelper.download(ctx, "vidS1", 137, 140, null, info, dir, File(dir, "out.mp4")) { }
        }
        // Work dir was created by the attempt.
        assertTrue(dir.exists())
    }
}
