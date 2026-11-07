package com.vayunmathur.communicate.rcs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.rcs.RcsGbaBootstrap
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSubscriptionManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * Pass 3: GBA bootstrap fail-fast guards. The modem round trip itself
 * (TelephonyManager.bootstrapAuthenticationRequest, reached
 * reflectively by production) silently no-ops under Robolectric —
 * neither the default shadow nor an exact-signature custom shadow
 * receives the reflective call — so onKeysAvailable delivery cannot
 * be driven on the JVM; that path is exercised on-device only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GbaBootstrapRcsPass3Test {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @AfterTest fun tearDown() {
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(-1)
    }

    @Test fun blankUrlFailsFast() = runBlocking {
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(1)
        assertNull(RcsGbaBootstrap.bootstrap(context, "", force = false))
        assertNull(RcsGbaBootstrap.bootstrap(context, "   ", force = true))
    }

    @Test fun invalidSubscriptionFailsFast() = runBlocking {
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(-1)
        assertNull(RcsGbaBootstrap.bootstrap(context, "https://naf.example.com", force = false))
    }
}
