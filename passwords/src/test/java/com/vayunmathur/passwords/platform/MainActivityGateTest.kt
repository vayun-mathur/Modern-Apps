package com.vayunmathur.passwords.platform

import com.vayunmathur.passwords.FakeAndroidKeyStore
import com.vayunmathur.passwords.MainActivity
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityGateTest {

    @Before fun setUp() = FakeAndroidKeyStore.install()

    @Test fun mainActivityWaitsAtBiometricGate() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.create()
        val activity = controller.get()
        // Composition is deliberately held back (autoSetContent = false) until
        // the biometric gate passes: creating the activity must neither finish
        // it nor render the vault before unlock.
        assertEquals(false, activity.isFinishing)
        assertEquals(false, activity.isDestroyed)
        controller.pause().stop().destroy()
    }
}
