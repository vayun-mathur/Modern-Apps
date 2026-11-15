package com.vayunmathur.auto.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ServiceInfo
import android.os.Looper
import android.telecom.CallAudioState
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.auto.telephony.CarProjectionInCallService
import com.vayunmathur.library.carhost.HostTemplate
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Host bind path (service present in PackageManager) and the bound
 * [CarProjectionInCallService] companion actions with an empty call list.
 */
@RunWith(RobolectricTestRunner::class)
class HostBindAndInCallTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private fun idleMain() = Shadows.shadowOf(Looper.getMainLooper()).idle()
    private val component = ComponentName("com.example.maps", "com.example.maps.Svc")

    private fun installCarService() {
        val serviceInfo = ServiceInfo().apply {
            packageName = component.packageName
            name = component.className
            applicationInfo = ApplicationInfo().apply { packageName = component.packageName }
        }
        val pkg = PackageInfo().apply {
            packageName = component.packageName
            services = arrayOf(serviceInfo)
            applicationInfo = serviceInfo.applicationInfo
        }
        runCatching { Shadows.shadowOf(ctx.packageManager).installPackage(pkg) }
    }

    @Test fun `bind with a present service connects and disconnect callbacks post panes`() {
        installCarService()
        val templates = mutableListOf<HostTemplate>()
        val session = HostSession(ctx, component, "nav", "Maps") { templates += it }
        assertTrue(session.hasService(), "installed car service should resolve")
        session.bind()
        session.bind() // already bound: no-op
        val connField = HostSession::class.java.getDeclaredField("connection").apply { isAccessible = true }
        val conn = connField.get(session) as ServiceConnection
        // Framework delivers the connect; drive the handshake thread to completion.
        conn.onServiceDisconnected(component)
        idleMain()
        assertTrue(templates.any { it is HostTemplate.Pane && it.title == null })
        conn.onNullBinding(component)
        idleMain()
        conn.onBindingDied(component)
        idleMain()
        assertTrue(templates.filterIsInstance<HostTemplate.Message>().any { it.message!!.contains("stopped unexpectedly") })
        session.unbind()
        conn.onServiceConnected(component, null) // null binder: posts didn't-respond
        idleMain()
        assertTrue(templates.filterIsInstance<HostTemplate.Message>().any { it.message!!.contains("didn't respond") })
    }

    @Test fun `bound in-call service companion actions tolerate an empty call list`() {
        val controller = Robolectric.buildService(CarProjectionInCallService::class.java)
        val service = controller.create().get()
        assertNull(CarProjectionInCallService.current)
        service.onBind(Intent())
        assertNotNull(CarProjectionInCallService.current)
        // No calls bound: every card action is a safe no-op.
        CarProjectionInCallService.answerCall()
        CarProjectionInCallService.endCall()
        CarProjectionInCallService.toggleHold()
        CarProjectionInCallService.toggleMute()
        service.onCallAudioStateChanged(CallAudioState(false, CallAudioState.ROUTE_EARPIECE, CallAudioState.ROUTE_EARPIECE))
        service.onUnbind(Intent())
        assertNull(CarProjectionInCallService.current)
        controller.destroy()
    }
}
