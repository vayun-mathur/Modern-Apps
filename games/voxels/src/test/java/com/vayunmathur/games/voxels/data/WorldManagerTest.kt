package com.vayunmathur.games.voxels.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class WorldManagerTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @BeforeTest fun clean() {
        File(ctx.filesDir, "worlds").deleteRecursively()
        File(ctx.filesDir, "voxels_handled_requests.json").delete()
    }

    @Test fun `create list sorted by last played, touch reorders, delete removes`() {
        assertTrue(WorldManager.listWorlds(ctx).isEmpty())
        val a = WorldManager.createWorld(ctx, "  Alpha ", 42, now = 1000)
        val b = WorldManager.createWorld(ctx, "", 7, now = 2000)
        assertEquals("Alpha", a.meta.name)
        assertEquals("New World", b.meta.name)
        assertTrue(a.id != b.id)
        // Same timestamp creates a suffixed id instead of colliding
        val c = WorldManager.createWorld(ctx, "Gamma", 1, now = 2000)
        assertTrue(c.id.startsWith("w_2000"))
        assertEquals(listOf(b.id, c.id, a.id).sorted(), WorldManager.listWorlds(ctx).map { it.id }.sorted())
        assertEquals(2000L, WorldManager.listWorlds(ctx).first().meta.lastPlayed)
        WorldManager.touch(ctx, a.id, now = 3000)
        assertEquals(a.id, WorldManager.listWorlds(ctx).first().id)
        WorldManager.touch(ctx, "missing", now = 4000) // no-op
        WorldManager.deleteWorld(ctx, b.id)
        assertTrue(WorldManager.listWorlds(ctx).none { it.id == b.id })
    }

    @Test fun `corrupt world metadata is skipped`() {
        val root = File(ctx.filesDir, "worlds").apply { mkdirs() }
        File(root, "w_bad").apply { mkdirs() }
        File(root, "w_bad/world.json").writeText("not json")
        File(root, "w_empty").apply { mkdirs() }
        val good = WorldManager.createWorld(ctx, "Good", 1, now = 10)
        assertEquals(listOf(good.id), WorldManager.listWorlds(ctx).map { it.id })
    }

    @Test fun `seed resolution numbers hash and blank`() {
        assertEquals(42, WorldManager.resolveSeed(" 42 "))
        assertEquals(-5, WorldManager.resolveSeed("-5"))
        assertEquals("hello".hashCode(), WorldManager.resolveSeed("hello"))
        // Blank is random: just assert it returns without throwing (value is nondeterministic)
        WorldManager.resolveSeed("   ")
    }

    @Test fun `online and shared worlds dedupe by world id`() {
        val host = WorldManager.createOnlineWorld(ctx, "Host", 1, "world-1", "key", "dev-1", now = 100)
        assertTrue(host.meta.online)
        assertEquals("owner", host.meta.role)
        assertEquals(listOf(host.id), WorldManager.onlineWorlds(ctx).map { it.id })
        val shared = WorldManager.upsertSharedWorld(ctx, "Shared", 2, "world-2", "k2", "dev-9", "editor", now = 200)
        val again = WorldManager.upsertSharedWorld(ctx, "Shared Again", 3, "world-2", "k2", "dev-9", "editor", now = 300)
        assertEquals(shared.id, again.id)
        assertEquals(2, WorldManager.onlineWorlds(ctx).size)
        assertTrue(WorldManager.listWorlds(ctx).none { !it.meta.online && it.id == host.id })
    }

    @Test fun `handled requests persist and survive corrupt files`() {
        assertTrue(WorldManager.handledRequests(ctx).isEmpty())
        WorldManager.markRequestHandled(ctx, "world-1|dev-2")
        WorldManager.markRequestHandled(ctx, "world-1|dev-2")
        WorldManager.markRequestHandled(ctx, "world-3|dev-4")
        assertEquals(setOf("world-1|dev-2", "world-3|dev-4"), WorldManager.handledRequests(ctx))
        File(ctx.filesDir, "voxels_handled_requests.json").writeText("broken")
        assertTrue(WorldManager.handledRequests(ctx).isEmpty())
    }
}
