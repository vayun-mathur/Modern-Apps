package com.vayunmathur.library.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.ConnectivityMonitor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * Drives [ConnectivityMonitor]'s registered [ConnectivityManager.NetworkCallback]
 * through the Robolectric shadow registry: validated capabilities publish
 * online, losing them (or the network) publishes offline. One method because
 * the monitor registers once per process while Robolectric resets the
 * connectivity shadow per method.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectivityMonitorCallbackTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun caps(vararg capabilities: Int): NetworkCapabilities {
        val result = ShadowNetworkCapabilities.newInstance()
        val shadow = Shadows.shadowOf(result)
        capabilities.forEach { shadow.addCapability(it) }
        return result
    }

    @Test
    fun callbackPublishesValidatedState() {
        ConnectivityMonitor.start(context)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callbacks = Shadows.shadowOf(cm).networkCallbacks
        assertEquals(1, callbacks.size)
        val callback = callbacks.single()
        val validated = caps(
            NetworkCapabilities.NET_CAPABILITY_INTERNET,
            NetworkCapabilities.NET_CAPABILITY_VALIDATED,
        )
        val network = ShadowNetwork.newInstance(41)

        assertFalse(ConnectivityMonitor.isOnline.value)
        callback.onCapabilitiesChanged(network, validated)
        assertTrue(ConnectivityMonitor.isOnline.value)

        // Losing validation on the only network flips back offline.
        callback.onCapabilitiesChanged(network, caps(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertFalse(ConnectivityMonitor.isOnline.value)

        // A second validated network keeps the state online when the first is lost.
        val second = ShadowNetwork.newInstance(43)
        callback.onCapabilitiesChanged(network, validated)
        callback.onCapabilitiesChanged(second, validated)
        assertTrue(ConnectivityMonitor.isOnline.value)
        callback.onLost(network)
        assertTrue(ConnectivityMonitor.isOnline.value)
        callback.onLost(second)
        assertFalse(ConnectivityMonitor.isOnline.value)

        callback.onCapabilitiesChanged(network, validated)
        assertTrue(ConnectivityMonitor.isOnline.value)
        callback.onUnavailable()
        assertFalse(ConnectivityMonitor.isOnline.value)
    }
}
