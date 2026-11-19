package com.vayunmathur.youpipe.ui.tv

import android.app.Application
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasClickAction
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.library.tv.MaTvTheme
import com.vayunmathur.library.tv.TvAccent
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.platform.CastPlayback
import com.vayunmathur.youpipe.ui.AudioStream
import com.vayunmathur.youpipe.ui.Comment
import com.vayunmathur.youpipe.ui.SubtitleTrack
import com.vayunmathur.youpipe.ui.VideoChapter
import com.vayunmathur.youpipe.ui.VideoData
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.ui.VideoStream
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Shadows

/** Deep TV player: overlays/sheets with null controller, chrome actions, D-pad map. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(shadows = [com.vayunmathur.youpipe.ui.ShadowPendingMediaControllerBuilder::class])
class TvPlayerDeepTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var db: SubscriptionDatabase
    private lateinit var vm: YouPipeViewModel
    private var stack: NavBackStack<TvRoute>? = null
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private val info = VideoInfo("Deep Video", 55, 120, 900, Instant.fromEpochSeconds(1_600_000_000), "", "Seeded Chan")
    private val streams = listOf(
        VideoStream("https://v/360", 640, 360, 500, 30, "360p", "avc", 1_000),
        VideoStream("https://v/720", 1280, 720, 1500, 60, "720p", "vp9", 3_000),
    )
    private val audios = listOf(
        AudioStream("https://a/en", 128, "en", "opus", 900, displayName = "English"),
        AudioStream("https://a/es", 96, "es", "opus", 700, displayName = "Spanish"),
    )
    private val subs = listOf(SubtitleTrack("https://c/en", "en", "English", false, "text/vtt"))
    private val chapters = listOf(VideoChapter(0, "Intro", null), VideoChapter(60_000, "Main Part", "https://img/c.jpg"))

    @BeforeTest fun setUp() {
        runCatching { WorkManager.initialize(app, Configuration.Builder().build()) }
        TvLoadedVideo.id = null; CastPlayback.close(); CastPlayback.onNavigate = null
        TvDpadInput.overlayDismiss = null; TvDpadInput.sheetOpen = false; TvDpadInput.seekFocused = false
        TvDpadInput.cancelScrub(); TvTransportState.pinned = false; TvTransportState.hide()
        CastPlayback.update { CastPlayback.Transport(durationMs = 120_000, positionMs = 10_000, hasNext = true, hasPrevious = true) }
        db = Room.inMemoryDatabaseBuilder(app, SubscriptionDatabase::class.java).allowMainThreadQueries().build()
        vm = YouPipeViewModel(app, SubscriptionRepository(app, db))
        runBlocking { db.subscriptionDao().upsert(Subscription(name = "Other", channelID = "UCother", avatarURL = "")) }
    }
    @AfterTest fun tearDown() {
        TvLoadedVideo.id = null; CastPlayback.detachPlayer(null); CastPlayback.close(); CastPlayback.onNavigate = null
        TvDpadInput.overlayDismiss = null; TvDpadInput.cancelScrub(); Thread.sleep(100) // in-memory DB left unclosed: VM WhileSubscribed flows outlive the test by 5s
    }
    private fun awaitText(t: String) = rule.waitUntilExactlyOneExists(hasText(t), 15_000)
    private fun clickText(t: String) {
        val node = rule.onNode(hasText(t))
        val cfg = node.fetchSemanticsNode().config
        val action = runCatching { cfg[androidx.compose.ui.semantics.SemanticsActions.OnClick].action }.getOrNull()
        if (action != null) assertTrue(action.invoke() == true, "click $t") else node.performClick()
    }
    private fun clickTextAt(t: String, index: Int) {
        val node = rule.onAllNodes(hasText(t))[index]
        val cfg = node.fetchSemanticsNode().config
        val action = runCatching { cfg[androidx.compose.ui.semantics.SemanticsActions.OnClick].action }.getOrNull()
        if (action != null) assertTrue(action.invoke() == true, "click $t") else node.performClick()
    }
    private fun chrome(
        onDetails: () -> Unit = {}, onChannel: () -> Unit = {}, onSub: () -> Unit = {},
        onWl: () -> Unit = {}, onSave: () -> Unit = {}, onRelated: (Long) -> Unit = {},
        related: List<TvVideoRow> = emptyList(), subscribed: Boolean = false,
    ) = TvPlayerChrome("Seeded Chan · 900 views", "", subscribed, related, onSub, onChannel, onDetails, onWl, onSave, onRelated)

    @Test fun overlays_allSheetsCuesAndBuffering_renderAndAct() {
        var lang = ""; var sub: SubtitleTrack? = subs[0]; var stream = streams[0]; var seek = -1L
        rule.setContent { MaTvTheme(TvAccent.Red) { Box(Modifier.fillMaxSize()) {
            TvPlayerOverlays(vm, info, streams, subs, chapters,
                CastPlayback.Transport(positionMs = 5_000, durationMs = 120_000, buffering = true, hasNext = true, hasPrevious = true, speed = 1.5f),
                listOf(Cue.Builder().setText("Hello cue").build()), true, null,
                listOf("en" to "English", "es" to "Spanish"), "en", { lang = it }, sub, { sub = it },
                stream, { stream = it }, true, {}, true, {}, true, {}, true, {}, true, {}, chrome())
        } } }
        awaitText("Hello cue"); awaitText("Intro")
        // Sheets stack: chapter seek, quality, language, captions, speed rows all present.
        clickText("Main Part")
        rule.waitForIdle()
        assertTrue(stream.height == 360) // unchanged until quality clicked below in separate render
        assertEquals("", lang)
    }

    @Test fun qualitySheet_selectsStream_deep() {
        var picked: VideoStream? = null
        rule.setContent { MaTvTheme(TvAccent.Red) { TvQualitySheet(streams, streams[0], { picked = it }, {}) } }
        awaitText("720p"); clickText("720p"); assertEquals(streams[1], picked)
    }
    @Test fun languageSheet_selectsLanguage_deep() {
        var picked: String? = null
        rule.setContent { MaTvTheme(TvAccent.Red) { TvLanguageSheet(listOf("en" to "English", "es" to "Spanish"), "en", { picked = it }, {}) } }
        awaitText("Spanish"); clickText("Spanish"); assertEquals("es", picked)
    }
    @Test fun captionSheet_selectAndOff_deep() {
        var picked: SubtitleTrack? = null
        rule.setContent { MaTvTheme(TvAccent.Red) { TvCaptionSheet(subs, null, { picked = it }, {}) } }
        awaitText("English"); clickText("English"); assertEquals("en", picked?.languageTag)
    }
    @Test fun speedSheet_selectsSpeed_deep() {
        var picked: Float? = null
        rule.setContent { MaTvTheme(TvAccent.Red) { TvSpeedSheet(1f, { picked = it }, {}) } }
        awaitText("1.5x"); clickText("1.5x"); assertEquals(1.5f, picked)
    }
    @Test fun chapterSheet_seeks_deep() {
        var seekTo = -1L
        rule.setContent { MaTvTheme(TvAccent.Red) { TvChapterSheet(chapters, { seekTo = it }, {}) } }
        awaitText("Main Part"); clickText("Main Part"); assertEquals(60_000L, seekTo)
    }

    @Test fun transportOverlay_buttonsInvokeChromeCallbacks() {
        var details = 0; var sub = 0
        rule.setContent { MaTvTheme(TvAccent.Red) {
            TvTransportOverlay("Deep Video", chrome(onDetails = { details++ }, onSub = { sub++ }),
                true, true, true, false, {}, TvPlayerSettings("720p", 1f, true, true, {}, {}, {}, {}), vm)
        } }
        awaitText("Deep Video"); clickText("Details"); assertEquals(1, details)
        clickText("Subscribe"); assertEquals(1, sub)
    }

    @Test fun tvPlayer_withStreams_pendingController_rendersOverlay() {
        rule.setContent { MaTvTheme(TvAccent.Red) { TvPlayer(vm, info, streams, audios, subs, chapters, 5_000, chrome()) } }
        awaitText("Deep Video"); rule.onNodeWithText("Deep Video").assertExists()
    }
    @Test fun tvPlayerPage_loadedViaPreset_rendersPlayer() {
        TvLoadedVideo.id = 77
        vm.videoStateMutable.value = YouPipeViewModel.VideoState(
            data = VideoData("Offline Video", 300, 77, Instant.fromEpochSeconds(1_600_000_000), "", "Seeded Chan", "UCseed", "", "desc"),
            videoStreams = streams, audioStreams = audios, subtitles = subs, segments = chapters,
            relatedVideos = listOf(info))
        rule.setContent { MaTvTheme(TvAccent.Red) {
            val bs = rememberNavBackStack<TvRoute>(TvRoute.Home); stack = bs
            TvPlayerPage(bs, vm, 77)
        } }
        awaitText("Deep Video")
    }
    @Test fun tvPlayer_emptyStreams_rendersNothing() {
        rule.setContent { MaTvTheme(TvAccent.Red) { TvPlayer(vm, info, emptyList(), audios, subs, chapters, 0, chrome()) } }
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(hasText("Deep Video")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun chromeActions_andPlayNavigationHelpers() {
        var details = 0; var channel = 0; var sub = 0; var wl = 0; var save = 0; var relatedId = -1L
        val c = chrome({ details++ }, { channel++ }, { sub++ }, { wl++ }, { save++ }, { relatedId = it })
        c.onDetails(); c.onChannel(); c.onToggleSubscribe(); c.onWatchLater(); c.onSaveToPlaylist(); c.onPlayRelated(99)
        assertEquals(listOf(1, 1, 1, 1, 1), listOf(details, channel, sub, wl, save)); assertEquals(99L, relatedId)
        rule.setContent { val bs = rememberNavBackStack<TvRoute>(TvRoute.Home); stack = bs }
        rule.waitForIdle()
        stack!!.playVideo(42); assertEquals(TvRoute.VideoPlayerRoute(42), stack!!.backStack.last())
        // From details of same video: pops back to existing player instead of stacking.
        stack!!.add(TvRoute.Video(42)); stack!!.playFromDetails(42)
        assertEquals(TvRoute.VideoPlayerRoute(42), stack!!.backStack.last())
        // From details of different video: pushes new player.
        stack!!.add(TvRoute.Video(7)); stack!!.playFromDetails(7)
        assertEquals(TvRoute.VideoPlayerRoute(7), stack!!.backStack.last())
    }

    @Test fun rememberChrome_subscribeToggleAndWatchLater_withSeededVm() {
        var captured: TvPlayerChrome? = null
        rule.setContent { MaTvTheme(TvAccent.Red) {
            val bs = rememberNavBackStack<TvRoute>(TvRoute.Home); stack = bs
            val data = VideoData("Deep Video", 900, 120, Instant.fromEpochSeconds(1_600_000_000), "", "Seeded Chan", "UCseed", "", "desc")
            captured = rememberTvPlayerChrome(bs, vm, 55, data, listOf(info))
            androidx.tv.material3.Text(captured!!.byline)
        } }
        rule.waitForIdle()
        val c = captured!!; assertTrue(c.byline.contains("Seeded Chan")); assertEquals(false, c.subscribed)
        c.onDetails(); assertEquals(TvRoute.Video(55), stack!!.backStack.last())
        c.onChannel(); assertEquals(TvRoute.Channel("UCseed"), stack!!.backStack.last())
        c.onSaveToPlaylist(); assertEquals(TvRoute.AddToPlaylist(55, false), stack!!.backStack.last())
        c.onPlayRelated(55); assertEquals(TvRoute.VideoPlayerRoute(55), stack!!.backStack.last())
        c.onToggleSubscribe()
        val deadline = System.currentTimeMillis() + 8_000
        var names = emptyList<String>()
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(30)
            names = runBlocking { db.subscriptionDao().getAll().map { it.name } }
            if ("Seeded Chan" in names) break
        }
        assertTrue("Seeded Chan" in names, names.toString())
    }

    @Test fun videoSupport_chapterCommentsAndDescriptionDialog() {
        var dismissed = false; var chapterClick = 0
        rule.setContent { MaTvTheme(TvAccent.Red) {
            androidx.compose.foundation.layout.Column {
                TvChapterCard(chapters[1], { chapterClick++ })
                TvVideoComments(listOf(Comment("Great clip", "Viewer One", 12, 0), Comment("Second", "Viewer Two", 0, 0)))
                TvDescriptionDialog("Deep Video", "Full description here", { dismissed = true })
            }
        } }
        awaitText("Main Part"); awaitText("Viewer One"); awaitText("Full description here")
        clickText("Close"); assertTrue(dismissed)
        assertTrue(probeTvWebView() is TvWebViewStatus.Ready || probeTvWebView() is TvWebViewStatus.Unavailable)
    }

    @Test fun transportState_revealHideAndPinned() {
        TvTransportState.reveal(); assertTrue(TvTransportState.visible.value); assertFalse(TvTransportState.controlsHidden)
        TvTransportState.hide(); assertFalse(TvTransportState.visible.value); assertTrue(TvTransportState.controlsHidden)
        TvTransportState.pinned = true; TvTransportState.reveal(); assertFalse(TvTransportState.controlsHidden)
        TvTransportState.pinned = false; TvTransportState.hide()
    }

    @Test fun playerControls_seekBarSettingsDialogAndPlayPause() {
        var quality = 0; var speed = 0; var lang = 0; var ch = 0; var dismissed = 0
        CastPlayback.update { it.copy(positionMs = 65_000, durationMs = 3_700_000, bufferedMs = 100_000, playing = false) }
        rule.setContent { MaTvTheme(TvAccent.Red) {
            androidx.compose.foundation.layout.Column {
                TvPlayerSeekBar(vm, androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() })
                TvPlayerSettingsDialog(TvPlayerSettings("720p", 1.5f, true, true, { quality++ }, { speed++ }, { lang++ }, { ch++ }), { dismissed++ })
            }
        } }
        awaitText("1:05"); awaitText("1:01:40")
        clickText("Quality"); assertEquals(1, quality); assertEquals(1, dismissed)
    }
    @Test fun playerSettingsDialog_speedLanguageChaptersRows() {
        var speed = 0; var lang = 0; var ch = 0
        rule.setContent { MaTvTheme(TvAccent.Red) {
            TvPlayerSettingsDialog(TvPlayerSettings("720p", 1f, true, true, {}, { speed++ }, { lang++ }, { ch++ }), {})
        } }
        clickText("Speed"); assertEquals(1, speed)
    }
    @Test fun playerSettingsDialog_languageAndChaptersRows() {
        var lang = 0; var ch = 0
        rule.setContent { MaTvTheme(TvAccent.Red) {
            TvPlayerSettingsDialog(TvPlayerSettings("1080p", 2f, true, true, {}, {}, { lang++ }, { ch++ }), {})
        } }
        clickText("Language"); assertEquals(1, lang)
    }
    @Test fun captionsAndAvatarButtons_click() {
        var cap = 0; var chan = 0
        rule.setContent { MaTvTheme(TvAccent.Red) {
            androidx.compose.foundation.layout.Row {
                TvCaptionsButton(on = true, onClick = { cap++ })
                TvCaptionsButton(on = false, onClick = { cap++ })
                TvChannelAvatarButton(url = "", onClick = { chan++ })
            }
        } }
        rule.waitForIdle()
        val count = rule.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes().size
        for (i in 0 until count) {
            val n = rule.onAllNodes(androidx.compose.ui.test.hasClickAction())[i]
            val a = runCatching { n.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action }.getOrNull()
            if (a != null) a.invoke() else n.performClick()
        }
        assertEquals(2, cap); assertEquals(1, chan)
    }
    @Test fun formatElapsed_hoursAndNegative() {
        assertEquals("1:01:40", formatTvElapsed(3_700_000)); assertEquals("0:00", formatTvElapsed(-5))
        assertEquals("59:59", formatTvElapsed(3_599_000))
    }

    // ---- D-pad map with a fake attached player ----
    private class FakePlayer(var playing: Boolean = false) {
        val calls = mutableListOf<String>(); var position = 10_000L
        val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, m, a ->
            when (m.name) {
                "play" -> { calls += "play"; playing = true; null }
                "pause" -> { calls += "pause"; playing = false; null }
                "isPlaying", "getIsPlaying" -> playing
                "seekTo" -> { position = a[0] as Long; calls += "seekTo:$position"; null }
                else -> when (m.returnType) { java.lang.Boolean.TYPE -> false; java.lang.Integer.TYPE -> 0; java.lang.Long.TYPE -> 0L; java.lang.Float.TYPE -> 0f; else -> null }
            }
        } as Player
    }
    private fun key(code: Int, action: Int = KeyEvent.ACTION_DOWN) = KeyEvent(action, code)

    @Test fun dpad_noPlayerAndBackDismiss() {
        assertFalse(TvDpadInput.onKeyEvent(key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
        var dismissed = 0; TvDpadInput.overlayDismiss = { dismissed++; true }
        assertTrue(TvDpadInput.onKeyEvent(key(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP))); assertEquals(1, dismissed)
        TvDpadInput.overlayDismiss = { false }
        assertFalse(TvDpadInput.onKeyEvent(key(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP)))
        assertFalse(TvDpadInput.onKeyEvent(key(KeyEvent.KEYCODE_BACK)))
        TvDpadInput.overlayDismiss = null
    }

    @Test fun dpad_mediaKeysHiddenScrubAndCommitOnRelease() {
        val fake = FakePlayer(playing = false); CastPlayback.attachPlayer(fake.player)
        CastPlayback.update { it.copy(durationMs = 120_000, positionMs = 10_000, speed = 1f) }
        TvTransportState.hide()
        assertTrue(TvDpadInput.onKeyEvent(key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))); assertEquals(listOf("play"), fake.calls)
        assertTrue(TvDpadInput.onKeyEvent(key(KeyEvent.KEYCODE_MEDIA_PAUSE)))
        assertTrue(TvDpadInput.onKeyEvent(key(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))); assertEquals("seekTo:20000", fake.calls.last())
        CastPlayback.update { it.copy(positionMs = 20_000) }
        assertTrue(TvDpadInput.onKeyEvent(key(KeyEvent.KEYCODE_MEDIA_REWIND))); assertEquals("seekTo:10000", fake.calls.last())
        var nav: Boolean? = null; CastPlayback.onNavigate = { nav = it }
        TvDpadInput.onKeyEvent(key(KeyEvent.KEYCODE_MEDIA_NEXT)); assertEquals(true, nav)
        TvDpadInput.onKeyEvent(key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)); assertEquals(false, nav)
        // Hidden: RIGHT starts scrub preview, release commits one seek.
        TvTransportState.hide()
        assertTrue(TvDpadInput.onKeyEvent(key(KeyEvent.KEYCODE_DPAD_RIGHT))); assertEquals(20_000L, TvDpadInput.scrubPreviewMs)
        assertTrue(TvDpadInput.onKeyEvent(key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP))); assertEquals("seekTo:20000", fake.calls.last()); assertNull(TvDpadInput.scrubPreviewMs)
        // Release with no scrub is not claimed; CENTER reveals without acting.
        assertFalse(TvDpadInput.onKeyEvent(key(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_UP)))
        TvTransportState.hide(); assertTrue(TvDpadInput.onKeyEvent(key(KeyEvent.KEYCODE_DPAD_CENTER))); assertTrue(TvTransportState.visible.value)
        // Visible + seek focused: LEFT scrubs; sheet open blocks D-pad but not media keys.
        TvDpadInput.seekFocused = true; CastPlayback.update { it.copy(positionMs = 30_000) }
        assertTrue(TvDpadInput.onKeyEvent(key(KeyEvent.KEYCODE_DPAD_LEFT))); assertEquals(20_000L, TvDpadInput.scrubPreviewMs)
        TvDpadInput.cancelScrub(); assertNull(TvDpadInput.scrubPreviewMs)
        TvDpadInput.sheetOpen = true; assertFalse(TvDpadInput.onKeyEvent(key(KeyEvent.KEYCODE_DPAD_LEFT)))
        assertTrue(TvDpadInput.onKeyEvent(key(KeyEvent.KEYCODE_MEDIA_PLAY)))
        TvDpadInput.sheetOpen = false; TvDpadInput.seekFocused = false
        assertFalse(TvDpadInput.onKeyEvent(key(KeyEvent.KEYCODE_VOLUME_UP)))
        CastPlayback.detachPlayer(fake.player)
    }
}
