package com.vayunmathur.passwords.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.passwords.FakeAndroidKeyStore
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KdbxPasswordHelperTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() = FakeAndroidKeyStore.install()

    @Test fun storeAndReadBackPassphrase() {
        val helper = KdbxPasswordHelper(ctx)
        assertFalse(helper.isKeyGenerated())
        helper.storePassphrase("correct horse battery staple")
        assertTrue(helper.isKeyGenerated())
        assertEquals("correct horse battery staple", helper.getPassphrase())
    }

    @Test fun storedPassphraseSurvivesNewHelperInstance() {
        KdbxPasswordHelper(ctx).storePassphrase("vault-pw-2")
        assertEquals("vault-pw-2", KdbxPasswordHelper(ctx).getPassphrase())
    }
}
