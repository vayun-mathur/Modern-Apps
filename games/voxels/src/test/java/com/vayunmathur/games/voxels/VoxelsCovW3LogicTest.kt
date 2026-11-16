package com.vayunmathur.games.voxels

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.games.voxels.network.VoxelsRoles
import com.vayunmathur.games.voxels.network.VoxelsSync
import com.vayunmathur.games.voxels.platform.MusicFx
import com.vayunmathur.games.voxels.platform.SoundFx
import com.vayunmathur.games.voxels.ui.isDurabilityItem
import com.vayunmathur.games.voxels.ui.maxDurability
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Cov-w3: voxels sync glue, audio synthesis entry points and catalog invariants (no network). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoxelsCovW3LogicTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun syncPureHelpersWithoutNetwork() = runBlocking<Unit> {
        assertEquals(32, VoxelsSync.newWorldKey().size)
        assertTrue(VoxelsSync.newWorldId().isNotBlank())
        assertTrue(VoxelsSync.newWorldId() != VoxelsSync.newWorldId())
        val member = VoxelsSync.Member(id = "dev1", name = "Ada", role = VoxelsRoles.EDITOR)
        assertEquals("w1|dev1|editor", VoxelsSync.memberSigningBytes("w1", member).decodeToString())
        assertEquals("w1|dev1|owner", VoxelsSync.memberSigningBytes("w1", member.copy(role = "owner")).decodeToString())
        // Live helpers with no socket: append is false, presence is a no-op, parse/decrypt degrade.
        assertFalse(VoxelsSync.isLive)
        assertFalse(VoxelsSync.liveAppend("world:x", ByteArray(32), listOf("op")))
        VoxelsSync.sendPresence("world:x", ByteArray(32), "hello")
        VoxelsSync.stopLive()
        assertEquals(null, VoxelsSync.parseLive("junk"))
        assertEquals(null, VoxelsSync.parseLive("""{"t":1}"""))
        val msg = VoxelsSync.parseLive("""{"t":"actions","channel":"world:x","actions":["a"],"seq":3,"data":"d"}""")
        assertEquals("actions", msg?.t)
        assertEquals(3, msg?.seq)
        assertEquals(listOf("a"), msg?.actions)
        assertEquals(null, VoxelsSync.decrypt(ByteArray(32) { 1 }, "!!!not-base64!!!"))
        assertEquals(null, VoxelsSync.decrypt(ByteArray(16), "AAAA"))
        // Serializable protocol shapes keep their defaults.
        assertEquals("editor", VoxelsSync.Invite("w", "k", "n").role)
        assertEquals("req", VoxelsSync.JoinRequest(worldId = "w", requesterId = "r").kind)
        assertEquals("", VoxelsSync.Member("x").name)
        assertTrue(VoxelsRoles.canEdit("owner"))
        assertTrue(VoxelsRoles.canEdit("editor"))
        assertFalse(VoxelsRoles.canEdit("viewer"))
        assertFalse(VoxelsRoles.canEdit(""))
    }

    @Test fun soundFxInitAndAllPlaysAreBestEffort() {
        SoundFx.volume = 2f
        assertEquals(1f, SoundFx.volume)
        SoundFx.volume = -1f
        assertEquals(0f, SoundFx.volume)
        SoundFx.volume = 0.5f
        SoundFx.init(ctx)
        SoundFx.init(ctx) // idempotent second init
        SoundFx.playBreak(); SoundFx.playPlace(); SoundFx.playHurt(); SoundFx.playEat(); SoundFx.playExplode()
        for (m in -1..8) SoundFx.playStep(m)
        SoundFx.playCave(); SoundFx.playStalk()
    }

    @Test fun musicFxLifecycleWithoutAssetsPlaying() {
        MusicFx.setVolume(2f)
        MusicFx.setVolume(-1f)
        MusicFx.setVolume(0.5f)
        MusicFx.onBackground()
        MusicFx.stop()
        MusicFx.toggle(ctx, null)
        MusicFx.toggle(ctx, "golden.ogg")
        MusicFx.toggle(ctx, "golden.ogg") // same disc stops it
        MusicFx.stop()
    }

    @Test fun durabilityCatalogInvariants() {
        // Every durability item must have a max so its bar can render; discs are not durability.
        for ((id, max) in maxDurability) {
            assertTrue(isDurabilityItem(id), "durability id $id missing from isDurabilityItem")
            assertTrue(max > 0)
        }
        assertFalse(isDurabilityItem(1075))
        assertFalse(isDurabilityItem(1))
        assertFalse(isDurabilityItem(0))
    }
}
