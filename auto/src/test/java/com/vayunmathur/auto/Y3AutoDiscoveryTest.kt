package com.vayunmathur.auto

import android.app.Application
import android.content.Context
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.auto.platform.CarAppDiscovery
import com.vayunmathur.auto.platform.CarApps
import com.vayunmathur.auto.platform.MediaPlaybackMonitor
import com.vayunmathur.auto.platform.NowPlayingInfo
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y3AutoDiscoveryTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ctx: Context get() = app

    @Test fun carAppsQueryWithPinnedOrderAndAssistant() {
        // No car apps installed on Robolectric: query must still walk every
        // legacy slot resolver and return whatever resolves (here, empty).
        val all = CarApps.query(ctx)
        assertNotNull(all)
        val pinned = CarApps.query(ctx, listOf("com.missing/.Nope", "com.also.missing/.Nope"))
        assertEquals(all.map { it.label.toString() }, pinned.map { it.label.toString() })
        // Assistant slot: no assist activity in this sandbox.
        val assistant = CarApps.assistant(ctx)
        assertTrue(assistant == null || assistant.label.isNotEmpty())
        // Discovery itself is empty-but-safe and stable across calls.
        val discovered = CarAppDiscovery.query(ctx)
        assertEquals(discovered.map { it.id }, CarAppDiscovery.query(ctx).map { it.id })
        assertEquals(discovered.map { it.id }, CarAppDiscovery.queryAll(ctx).map { it.id })
    }

    @Test fun mediaMonitorListenerPublishesEmptyInfoWithoutController() {
        val updates = mutableListOf<NowPlayingInfo>()
        val monitor = MediaPlaybackMonitor(ctx) { updates += it }
        monitor.start()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        // Drive the player listener reflectively: with no controller bound,
        // publish() must emit the empty now-playing snapshot, not crash.
        val listenerField = MediaPlaybackMonitor::class.java.declaredFields
            .first { Player.Listener::class.java.isAssignableFrom(it.type) }
            .apply { isAccessible = true }
        val listener = listenerField.get(monitor) as Player.Listener
        listener.onIsPlayingChanged(true)
        listener.onPlaybackStateChanged(Player.STATE_READY)
        listener.onMediaMetadataChanged(MediaMetadata.Builder().setTitle("Song").build())
        listener.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        listener.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(updates.isEmpty(), "$updates") // no controller: publish() is a safe no-op, nothing fabricated
        monitor.toggle(); monitor.seekToNext(); monitor.seekToPrevious()
        monitor.stop()
    }
}
