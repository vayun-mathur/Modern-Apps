package com.vayunmathur.maps.car
import com.vayunmathur.maps.platform.MapsSession
import com.vayunmathur.maps.platform.NavMapScreen

import android.app.Application
import android.content.Intent
import android.content.res.Configuration
import androidx.car.app.HandshakeInfo
import androidx.car.app.Session
import androidx.car.app.testing.TestCarContext
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * W6: [MapsSession.onCreateScreen] itself. The car library's configure()
 * is package-private and TestCarContext does not fabricate a host session,
 * so the session is configured reflectively with a stub handshake — the
 * same state a real host puts it in before asking for its first screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapsCarSessionW6Test {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun session_createsNavMapScreen_andStopsNavigation() {
        val carContext = TestCarContext.createCarContext(app)
        val session = MapsSession()
        val configure = Session::class.java.getDeclaredMethod(
            "configure",
            android.content.Context::class.java,
            HandshakeInfo::class.java,
            androidx.car.app.HostInfo::class.java,
            androidx.car.app.ICarHost::class.java,
            Configuration::class.java,
        )
        configure.isAccessible = true
        try {
            val carHost = java.lang.reflect.Proxy.newProxyInstance(
                javaClass.classLoader, arrayOf(androidx.car.app.ICarHost::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "asBinder" -> android.os.Binder()
                    "toString" -> "fake-car-host"
                    else -> null
                }
            } as androidx.car.app.ICarHost
            configure.invoke(session, app, HandshakeInfo("com.example.host", 7), androidx.car.app.HostInfo("com.example.host", 1000), carHost, Configuration().apply { screenWidthDp = 800; screenHeightDp = 480; densityDpi = 160 })
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw AssertionError("configure failed: ${e.cause}", e)
        }
        val screen = try {
            session.onCreateScreen(Intent())
        } catch (e: Exception) {
            throw AssertionError("onCreateScreen failed: $e", e)
        }
        assertNotNull(screen)
        assertTrue(screen is NavMapScreen, "first car screen should be the nav map, was $screen")
        assertNotNull(screen.onGetTemplate())
        // The host stop-navigation path tears down any running guidance.
        val stop = MapsSession::class.java.getDeclaredMethod("stopExistingNavigation")
        stop.isAccessible = true
        stop.invoke(session)
    }
}
