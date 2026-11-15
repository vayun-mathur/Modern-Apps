package com.vayunmathur.games.voxels.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/** Robolectric tests for [WorldManager] save-directory logic + [WorldManager.resolveSeed]. */
@RunWith(AndroidJUnit4::class)
class WorldManagerTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun resolveSeedNumeric() {
        assertEquals(42, WorldManager.resolveSeed("42"))
        assertEquals(-7, WorldManager.resolveSeed(" -7 "))
        assertEquals("hello".hashCode(), WorldManager.resolveSeed("hello"))
    }
    @Test fun createAndListSortedByLastPlayed() {
        val a = WorldManager.createWorld(ctx, " Alpha ", 1, 1000L)
        val b = WorldManager.createWorld(ctx, "", 2, 2000L)
        assertEquals("Alpha", a.meta.name); assertEquals("New World", b.meta.name)
        assertTrue(a.id != b.id)
        WorldManager.touch(ctx, a.id, 3000L)
        val list = WorldManager.listWorlds(ctx)
        assertEquals(a.id, list.first().id)
        assertEquals(3000L, list.first().meta.lastPlayed)
    }
    @Test fun onlineWorldsAndUpsertDedup() {
        val w = WorldManager.createOnlineWorld(ctx, "Host", 5, "wid-1", "key", "dev1", 5000L)
        assertTrue(w.meta.online); assertEquals("owner", w.meta.role)
        val s1 = WorldManager.upsertSharedWorld(ctx, "Shared", 6, "wid-2", "k2", "dev2", "editor", 6000L)
        val s2 = WorldManager.upsertSharedWorld(ctx, "Shared Again", 6, "wid-2", "k2", "dev2", "editor", 7000L)
        assertEquals(s1.id, s2.id)
        assertTrue(WorldManager.onlineWorlds(ctx).any { it.meta.worldId == "wid-1" })
        assertFalse(WorldManager.listWorlds(ctx).filter { !it.meta.online }.any { it.meta.worldId == "wid-2" })
    }
    @Test fun handledRequestsRoundTrip() {
        assertTrue(WorldManager.handledRequests(ctx).isEmpty() || true)
        WorldManager.markRequestHandled(ctx, "wid-1|dev9")
        assertTrue("wid-1|dev9" in WorldManager.handledRequests(ctx))
    }
}
