package com.vayunmathur.games.voxels

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.voxels.data.WorldInfo
import com.vayunmathur.games.voxels.data.WorldMeta
import com.vayunmathur.games.voxels.data.WorldMode
import com.vayunmathur.games.voxels.platform.MenuActivity
import com.vayunmathur.games.voxels.platform.MusicFx
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/** Cov-x4: MenuActivity join-link/clipboard/play glue and MainActivity launch variants. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class VoxelsCovX4ActivityTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    private fun menuActivity(intent: Intent? = null) =
        Robolectric.buildActivity(MenuActivity::class.java, intent ?: Intent(ctx, MenuActivity::class.java))

    private fun parse(activity: MenuActivity, intent: Intent?): Pair<String, String>? {
        val m = MenuActivity::class.java.getDeclaredMethod("parseJoinLink", Intent::class.java)
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return m.invoke(activity, intent) as Pair<String, String>?
    }

    private fun play(activity: MenuActivity, world: WorldInfo) {
        val m = MenuActivity::class.java.getDeclaredMethod("play", WorldInfo::class.java)
            .apply { isAccessible = true }
        m.invoke(activity, world)
    }

    private fun copy(activity: MenuActivity, text: String) {
        val m = MenuActivity::class.java.getDeclaredMethod("copyToClipboard", String::class.java)
            .apply { isAccessible = true }
        m.invoke(activity, text)
    }

    private fun world(online: Boolean, role: String) = WorldInfo(
        id = "w_1", dir = File(ctx.filesDir, "worlds/w_1").absolutePath,
        meta = WorldMeta(
            name = "W", seed = 42, created = 1, lastPlayed = 2, online = online,
            worldId = "shared-1", ownerDeviceId = "owner-9", role = role, keyB64 = "a2V5",
            mode = WorldMode.SURVIVAL,
        ),
    )

    @Test fun joinLinkParsingAcceptsOnlyWellFormedVoxelsLinks() {
        val controller = menuActivity().setup()
        val a = controller.get()
        assertEquals("world123" to "dev9", parse(a, Intent(Intent.ACTION_VIEW, Uri.parse("voxels://join/world123?owner=dev9"))))
        assertEquals("w" to "o", parse(a, Intent(Intent.ACTION_VIEW, Uri.parse("voxels://join/w?owner=o&x=1"))))
        assertNull(parse(a, null))
        assertNull(parse(a, Intent()))
        assertNull(parse(a, Intent(Intent.ACTION_VIEW, Uri.parse("https://join/world123?owner=dev9"))))
        assertNull(parse(a, Intent(Intent.ACTION_VIEW, Uri.parse("voxels://play/world123?owner=dev9"))))
        assertNull(parse(a, Intent(Intent.ACTION_VIEW, Uri.parse("voxels://join/world123"))))
        assertNull(parse(a, Intent(Intent.ACTION_VIEW, Uri.parse("voxels://join/world123?owner="))))
        assertNull(parse(a, Intent(Intent.ACTION_VIEW, Uri.parse("voxels://join/?owner=dev9"))))
        assertNull(parse(a, Intent(Intent.ACTION_VIEW, Uri.parse("voxels://join?owner=dev9"))))
        controller.pause().stop().destroy()
    }

    @Test fun clipboardCopyStoresDeviceIdAndIgnoresEmpty() {
        val controller = menuActivity().setup()
        val a = controller.get()
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("seed", "before"))
        copy(a, "")
        assertEquals("before", cm.primaryClip?.getItemAt(0)?.text)
        copy(a, "device-42")
        assertEquals("device-42", cm.primaryClip?.getItemAt(0)?.text)
        controller.pause().stop().destroy()
    }

    @Test fun playBuildsOfflineAndOnlineIntents() {
        val controller = menuActivity().setup()
        val a = controller.get()
        Shadows.shadowOf(a).clearNextStartedActivities()
        play(a, world(online = false, role = ""))
        var next = Shadows.shadowOf(a).nextStartedActivity
        assertEquals("W", next.getStringExtra("world_name"))
        assertEquals(42, next.getIntExtra("world_seed", -1))
        assertEquals(WorldMode.SURVIVAL, next.getStringExtra("world_mode"))
        assertFalse(next.getBooleanExtra("online", false))
        play(a, world(online = true, role = "owner"))
        next = Shadows.shadowOf(a).nextStartedActivity
        assertTrue(next.getBooleanExtra("online", false))
        assertEquals("shared-1", next.getStringExtra("world_id"))
        assertEquals("a2V5", next.getStringExtra("world_key"))
        assertEquals("owner-9", next.getStringExtra("owner_device"))
        assertEquals(1, next.getIntExtra("net_role", -1)) // owner hosts
        play(a, world(online = true, role = "editor"))
        next = Shadows.shadowOf(a).nextStartedActivity
        assertEquals(2, next.getIntExtra("net_role", -1)) // everyone else joins as a client
        controller.pause().stop().destroy()
    }

    @Test fun menuActivityResumePauseCyclesAndNewIntents() {
        val controller = menuActivity().setup()
        ShadowLooper.idleMainLooper()
        controller.resume().pause().resume()
        ShadowLooper.idleMainLooper()
        controller.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("voxels://join/warm?owner=o1")))
        ShadowLooper.idleMainLooper()
        controller.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("voxels://join/warm")))
        controller.pause().stop().destroy()
    }

    @Test fun mainActivityLaunchVariantsAndLifecycle() {
        fun launch(extras: Intent.() -> Unit) {
            val dir = File(ctx.filesDir, "worlds/w_covx4").apply { mkdirs() }
            val intent = Intent(ctx, MainActivity::class.java).apply {
                putExtra("world_dir", dir.absolutePath)
                extras()
            }
            val c = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
            ShadowLooper.idleMainLooper()
            c.get().onWindowFocusChanged(true)
            c.get().onWindowFocusChanged(false)
            c.pause().stop().start().resume() // onStop pauses the game; returning shows the pause state
            ShadowLooper.idleMainLooper()
            c.pause().stop().destroy()
        }
        launch { putExtra("world_seed", 1); putExtra("world_mode", WorldMode.SURVIVAL); putExtra("world_name", "S") }
        launch { putExtra("world_seed", -5); putExtra("world_mode", WorldMode.CREATIVE) }
        launch { /* all defaults */ }
        launch {
            putExtra("online", true); putExtra("net_role", 1); putExtra("world_id", "wid")
            putExtra("world_key", "a2V5"); putExtra("owner_device", "owner"); putExtra("world_name", "Host")
        }
        launch {
            putExtra("online", true); putExtra("net_role", 2); putExtra("world_id", "wid2")
            putExtra("world_key", ""); putExtra("owner_device", ""); putExtra("world_name", "")
        }
    }

    @Test fun mainActivityDirectLaunchUsesDefaultWorld() {
        val c = Robolectric.buildActivity(MainActivity::class.java, Intent(ctx, MainActivity::class.java)).setup()
        ShadowLooper.idleMainLooper()
        assertTrue(File(ctx.filesDir, "worlds/default").exists())
        c.pause().stop().destroy()
    }

    @Test fun musicFxForegroundBackgroundAndDiscCycle() {
        MusicFx.stop()
        MusicFx.onForeground(ctx)
        ShadowLooper.idleMainLooper()
        MusicFx.setVolume(0.25f)
        MusicFx.onBackground()
        MusicFx.onForeground(ctx)
        MusicFx.toggle(ctx, "golden.ogg")
        MusicFx.toggle(ctx, "lullaby.ogg") // a different disc takes over
        MusicFx.toggle(ctx, null) // null resumes ambient
        MusicFx.toggle(ctx, "golden.ogg")
        MusicFx.toggle(ctx, "golden.ogg") // same disc stops it
        MusicFx.stop()
        MusicFx.stop() // stopping twice is safe
        MusicFx.onBackground()
    }
}
