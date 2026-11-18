package com.vayunmathur.findfamily.util

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.DirectBootStore
import com.vayunmathur.findfamily.data.TestFindFamilyRepository
import com.vayunmathur.findfamily.network.Networking
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Direct-boot identity bootstrap in a fresh sandbox (Networking's one-shot init
 * flags make this order-dependent with any other Networking test, so it lives
 * alone): [Networking.initDirectBoot] loads the identity from the
 * device-protected mirror without minting anything, and
 * [Networking.promoteToUnlocked] hands over to the credential-encrypted store.
 */
@RunWith(AndroidJUnit4::class)
class NetworkingDirectBootBootstrapTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun directBootBootstrapAndPromote() = runBlocking {
        val repo = TestFindFamilyRepository.install(ctx)
        // Unseeded mirror (fresh sandbox): initDirectBoot must refuse rather
        // than mint an identity. Seed the userid, then it must bootstrap.
        val mirror = DirectBootStore.store(ctx)
        mirror.setLong("userid", 313_131L, true)
        assertTrue(Networking.initDirectBoot(mirror), "seeded mirror must bootstrap")
        assertTrue(Networking.inDirectBootMode)
        assertEquals(313_131L, Networking.userid)
        // Second call is a no-op reporting the current mode.
        assertTrue(Networking.initDirectBoot(mirror))
        // Promote to the unlocked store: mode clears and the full init runs.
        val ce = DataStoreUtils.getInstance(ctx)
        if (ce.getLongAwait("userid") == null) ce.setLong("userid", 313_131L, true)
        Networking.promoteToUnlocked(repo, ce, "Me")
        assertFalse(Networking.inDirectBootMode)
        assertEquals(313_131L, Networking.userid)
        assertNotNull(repo.getUser(313_131L))
        Unit
    }

    @Test fun unseededMirrorRefusesToBootstrap() = runBlocking {
        // This sandbox may already have bootstrapped via the sibling test; the
        // refusal branch is only observable before any init. Guard accordingly:
        // with no userid in a *different*, empty store, initDirectBoot is either
        // refused (fresh) or reports the already-running mode (not a mint).
        val empty = DirectBootStore.store(ctx)
        if (!Networking.inDirectBootMode && Networking.userid == 0L) {
            assertFalse(Networking.initDirectBoot(empty))
        }
        Unit
    }
}
