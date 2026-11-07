package com.vayunmathur.maps.ipc

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The findfamily client against a binder that is not a Messenger at all
 * (register/unregister fail as RemoteExceptions), an unbind the framework
 * rejects, and the composable receiving a real push.
 * Slice-2 coverage for maps (basename F, outside the slice-1 dirs).
 */
@RunWith(AndroidJUnit4::class)
class FamilyLocationBinderSlice2Test {

    @get:Rule
    val rule = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val component = ComponentName("com.vayunmathur.findfamily", ".FamilyService")

    private fun installService(binder: android.os.IBinder) {
        val resolve = ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply {
                packageName = component.packageName
                name = component.className
            }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(FamilyLocationProtocol.ACTION), resolve)
        shadowOf(app).setComponentNameAndServiceForBindService(component, binder)
    }

    private fun pump() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun `a non-messenger binder fails registration quietly`() {
        installService(Binder())
        val client = FamilyLocationClient(app) {}
        client.bind()
        pump()
        // Unregistering against the same dead target is equally quiet, and a
        // framework that says "never bound" is swallowed too.
        shadowOf(app).setUnbindServiceShouldThrowIllegalArgument(true)
        client.unbind()
        pump()
        client.unbind()
        pump()
    }

    @Test fun `the composable receives a push and clears it on dispose`() {
        val received = mutableListOf<Int>()
        var replyTo: Messenger? = null
        val service = Messenger(
            Handler(Looper.getMainLooper()) { msg ->
                received.add(msg.what)
                if (msg.what == FamilyLocationProtocol.MSG_REGISTER) replyTo = msg.replyTo
                true
            },
        )
        installService(service.binder)

        var show by mutableStateOf(true)
        var seen: List<FamilyMember>? = null
        rule.setContent {
            if (show) {
                val members = rememberFamilyMembers()
                seen = members.value
            }
        }
        rule.waitForIdle()
        pump()
        assertEquals(listOf(FamilyLocationProtocol.MSG_REGISTER), received)

        val push = Message.obtain(null, FamilyLocationProtocol.MSG_LOCATIONS)
        push.data = Bundle().apply {
            putLongArray(FamilyLocationProtocol.KEY_IDS, longArrayOf(7L))
            putStringArray(FamilyLocationProtocol.KEY_NAMES, arrayOf("Ada"))
            putDoubleArray(FamilyLocationProtocol.KEY_LATS, doubleArrayOf(37.1))
            putDoubleArray(FamilyLocationProtocol.KEY_LNGS, doubleArrayOf(-122.1))
            putLongArray(FamilyLocationProtocol.KEY_TIMESTAMPS, longArrayOf(100L))
        }
        replyTo?.send(push)
        rule.waitForIdle()
        pump()
        assertEquals(listOf(FamilyMember(7L, "Ada", 37.1, -122.1, 100L, -1f)), seen)

        show = false
        rule.waitForIdle()
        pump()
        assertEquals(FamilyLocationProtocol.MSG_UNREGISTER, received.last())
    }
}
