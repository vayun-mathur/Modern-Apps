package com.vayunmathur.cast.tv.platform

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.cast.protocol.DecoderLimits
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A controllable NsdManager: Robolectric's own accepts every registration
 * silently, so the advertiser's conflict-retry and success paths need a shadow
 * that actually answers.
 */
@Implements(NsdManager::class)
class ShadowNsdManagerAnswering {

    @Suppress("UnusedParameter")
    @Implementation
    fun registerService(info: NsdServiceInfo, protocol: Int, listener: NsdManager.RegistrationListener) {
        registrations += info.serviceName
        if (failAlways) {
            listener.onRegistrationFailed(info, NsdManager.FAILURE_INTERNAL_ERROR)
        } else {
            listener.onServiceRegistered(info)
        }
    }

    @Implementation
    fun unregisterService(listener: NsdManager.RegistrationListener) {
        unregistrations++
        listener.onServiceUnregistered(NsdServiceInfo())
    }

    companion object {
        var failAlways = false
        val registrations = mutableListOf<String>()
        var unregistrations = 0

        fun reset() {
            failAlways = false
            registrations.clear()
            unregistrations = 0
        }
    }
}

/** The advertiser's registration lifecycle against an answering NsdManager. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowNsdManagerAnswering::class])
class AdvertiserShadowTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun registrationSuccessPublishesName() {
        ShadowNsdManagerAnswering.reset()
        val advertiser = ReceiverAdvertiser(ctx)
        advertiser.advertise("Living Room TV", "abc123def456", 43210, DecoderLimits())
        assertEquals("Living Room TV (MA Cast)", advertiser.registeredName)
        assertEquals(false, advertiser.localNetworkBlocked)
        advertiser.unadvertise()
        assertNull(advertiser.registeredName)
        assertEquals(1, ShadowNsdManagerAnswering.unregistrations)
    }

    @Test fun registrationFailureRetriesNumberedNamesThenGivesUp() {
        ShadowNsdManagerAnswering.reset()
        ShadowNsdManagerAnswering.failAlways = true
        val advertiser = ReceiverAdvertiser(ctx)
        advertiser.advertise("Living Room TV", "abc123def456", 43210, DecoderLimits())
        // Five attempts: the base name, then numbered variants, then it stops.
        assertEquals(5, ShadowNsdManagerAnswering.registrations.size)
        assertEquals("Living Room TV (MA Cast)", ShadowNsdManagerAnswering.registrations.first())
        assertEquals("Living Room TV (MA Cast 5)", ShadowNsdManagerAnswering.registrations.last())
        assertNull(advertiser.registeredName)
        advertiser.unadvertise()
    }
}
