package com.vayunmathur.passwords.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.passwords.FakeAndroidKeyStore
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * The repository's read *flows* are cold: obtaining them builds the (lazy)
 * database handle but does not open it, so they can be handed to the UI
 * before the vault is unlocked. Actually collecting them is what opens
 * SQLCipher, which is unavailable under Robolectric — that part is on-device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RepositoryFlowGettersTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() = FakeAndroidKeyStore.install()

    @Test fun flowGettersReturnColdFlowsWithoutOpeningVault() {
        val repo = PasswordRepository.get(ctx)
        assertNotNull(repo.passwords)
        assertNotNull(repo.passkeys)
        assertNotNull(repo.getPasswordByIdFlow(42))
        // Each access builds a fresh cold flow (Room semantics), not a shared stream.
        assertNotSame(repo.passwords, repo.passwords)
    }

    @Test fun snapshotStoreIsStableAndLazy() {
        val repo = PasswordRepository.get(ctx)
        assertSame(repo.snapshots, repo.snapshots)
    }
}
