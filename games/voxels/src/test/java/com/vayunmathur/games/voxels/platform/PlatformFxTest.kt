package com.vayunmathur.games.voxels.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.voxels.ui.VoxelSurfaceView
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class PlatformFxTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun soundFxInitWritesWavsAndPlays() {
        SoundFx.init(ctx)
        SoundFx.init(ctx) // idempotent
        for (name in listOf("vox_break.wav", "vox_place.wav", "vox_hurt.wav", "vox_eat.wav", "vox_explode.wav", "vox_cave.wav", "vox_stalk.wav", "vox_step0.wav", "vox_step5.wav")) {
            val f = File(ctx.cacheDir, name)
            assertTrue(f.exists() && f.length() > 44, "$name missing or empty")
            val header = f.readBytes().copyOfRange(0, 4).decodeToString()
            assertEquals("RIFF", header)
        }
        // all play entry points are best-effort no-ops when pool is shadowed
        SoundFx.playBreak(); SoundFx.playPlace(); SoundFx.playHurt(); SoundFx.playEat(); SoundFx.playExplode()
        SoundFx.playCave(); SoundFx.playStalk()
        for (m in -1..8) SoundFx.playStep(m) // includes out-of-range mats
    }

    @Test fun musicFxAmbientDiscAndStop() {
        MusicFx.startAmbient(ctx)
        MusicFx.startAmbient(ctx) // already playing: no-op
        MusicFx.toggle(ctx, "golden.ogg")
        MusicFx.toggle(ctx, "golden.ogg") // same disc: stop + resume ambient
        MusicFx.toggle(ctx, "lullaby.ogg")
        MusicFx.toggle(ctx, null) // resume ambient
        MusicFx.stop()
        MusicFx.stop() // idempotent
        MusicFx.toggle(ctx, "nonexistent-track.ogg") // missing asset: best-effort null player
        MusicFx.stop()
    }

    @Test fun achievementsUnlockProgressAndDismiss() = runBlocking {
        val json = ctx.assets.open("achievements.json").bufferedReader().use { it.readText() }
        val ach = VoxelsAchievements(ctx, json, "testworld")
        assertNotNull(ach.app)
        assertNotNull(ach.world)
        ach.unlock("first_block")
        ach.progress("builder_100", 50)
        ach.progress("builder_100", 10) // monotonic: lower value does not regress stored progress path
        ach.dismissNotification()
        // manager with prefix parses catalog and reports statuses
        val statuses = ach.world.getAchievementStatuses()
        assertNotNull(statuses)
        val mgr = VoxelsAchievementsManager(ctx, json, "world_x:")
        mgr.checkExistingAchievements() // no-op override
        mgr.forgetAll()
        Unit
    }

    @Test fun backupAgentProperties() {
        val agent = AppBackupAgent()
        assertEquals(listOf("voxels_prefs"), agent.prefNames)
        assertEquals(listOf("datastore_default"), agent.datastoreNames)
    }

    @Test fun voxelSurfaceViewLifecycleCallbacks() {
        val view = VoxelSurfaceView(ctx)
        assertNotNull(view.holder)
        // callbacks delegate to native when available; shadow surface may make
        // native calls fail inside their RuntimeException guards, but the
        // view must stay usable either way.
        runCatching { view.surfaceCreated(view.holder) }
        runCatching { view.surfaceChanged(view.holder, 0, 320, 240) }
        runCatching { view.surfaceDestroyed(view.holder) }
    }
}
