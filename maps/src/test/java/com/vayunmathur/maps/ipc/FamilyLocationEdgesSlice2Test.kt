package com.vayunmathur.maps.ipc

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
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
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The findfamily client's failure edges: a denied bind, a service that is
 * already dead at registration, disconnect delivery, malformed snapshots,
 * and the composable's dispose path.
 * Slice-2 coverage for maps (basename F, outside the slice-1 dirs).
 */
@RunWith(AndroidJUnit4::class)
class FamilyLocationEdgesSlice2Test {

    @get:Rule
    val rule = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val component = ComponentName("com.vayunmathur.findfamily", ".FamilyService")

    private class FakeService {
        val received = mutableListOf<Int>()
        var replyTo: Messenger? = null
        val messenger = Messenger(
            Handler(Looper.getMainLooper()) { msg ->
                received.add(msg.what)
                if (msg.what == FamilyLocationProtocol.MSG_REGISTER) replyTo = msg.replyTo
                true
            },
        )
        fun push(bundle: Bundle?) {
            val msg = Message.obtain(null, FamilyLocationProtocol.MSG_LOCATIONS)
            msg.data = bundle
            replyTo?.send(msg)
        }
    }

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

    private fun fullSnapshot(): Bundle = Bundle().apply {
        putLongArray(FamilyLocationProtocol.KEY_IDS, longArrayOf(1L, 2L))
        putStringArray(FamilyLocationProtocol.KEY_NAMES, arrayOf("Ada", "Bo"))
        putDoubleArray(FamilyLocationProtocol.KEY_LATS, doubleArrayOf(37.1, 37.2))
        putDoubleArray(FamilyLocationProtocol.KEY_LNGS, doubleArrayOf(-122.1, -122.2))
        putLongArray(FamilyLocationProtocol.KEY_TIMESTAMPS, longArrayOf(100L, 200L))
        putFloatArray(FamilyLocationProtocol.KEY_BATTERIES, floatArrayOf(0.5f))
    }

    @Test fun `a denied bind is silent and unbind stays silent`() {
        installService(FakeService().messenger.binder)
        shadowOf(app).setThrowInBindService(SecurityException("not co-signed"))
        val updates = mutableListOf<List<FamilyMember>>()
        val client = FamilyLocationClient(app) { updates.add(it) }
        client.bind()
        client.unbind()
        pump()
        assertTrue(updates.isEmpty())
    }

    @Test fun `registering with a dead service is swallowed`() {
        val thread = HandlerThread("dead-service").apply { start() }
        val deadMessenger = Messenger(Handler(thread.looper))
        thread.quit()
        thread.join()
        installService(deadMessenger.binder)
        val client = FamilyLocationClient(app) {}
        // onServiceConnected tries to register; the target is gone, which the
        // client treats as "nothing to stream" rather than a crash.
        client.bind()
        pump()
        client.unbind()
        pump()
    }

    @Test fun `malformed snapshots read as nobody and short batteries default`() {
        val service = FakeService()
        installService(service.messenger.binder)
        val updates = mutableListOf<List<FamilyMember>>()
        val client = FamilyLocationClient(app) { updates.add(it) }
        client.bind()
        pump()

        // Short battery array: the second member falls back to the sentinel.
        service.push(fullSnapshot())
        pump()
        assertEquals(2, updates.last().size)
        assertEquals(0.5f, updates.last()[0].battery)
        assertEquals(-1f, updates.last()[1].battery)

        // Each required array missing in turn reads as an empty snapshot.
        for (key in listOf(
            FamilyLocationProtocol.KEY_IDS,
            FamilyLocationProtocol.KEY_LATS,
            FamilyLocationProtocol.KEY_LNGS,
            FamilyLocationProtocol.KEY_TIMESTAMPS,
        )) {
            val broken = fullSnapshot()
            broken.remove(key)
            service.push(broken)
            pump()
            assertEquals(emptyList(), updates.last(), "missing $key")
        }

        // No payload at all.
        service.push(null)
        pump()
        assertEquals(emptyList(), updates.last())
        client.unbind()
        pump()
    }

    @Test fun `disconnect on unbind and dispose both tear down quietly`() {
        val service = FakeService()
        installService(service.messenger.binder)
        shadowOf(app).setUnbindServiceCallsOnServiceDisconnected(true)

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
        assertEquals(emptyList(), seen)
        assertEquals(listOf(FamilyLocationProtocol.MSG_REGISTER), service.received)

        // Leaving the composition unbinds and clears.
        show = false
        rule.waitForIdle()
        pump()
        assertEquals(FamilyLocationProtocol.MSG_UNREGISTER, service.received.last())
    }
}
