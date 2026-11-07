package com.vayunmathur.maps.ipc

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The findfamily channel with a fake service on the other end of the
 * Messenger: registration, snapshot parsing through the real handler,
 * and unregistration.
 * Slice-2 coverage for maps (basename F, outside the slice-1 dirs).
 */
@RunWith(AndroidJUnit4::class)
class FamilyLocationServiceSlice2Test {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

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

        fun push(bundle: Bundle?, what: Int = FamilyLocationProtocol.MSG_LOCATIONS) {
            val msg = Message.obtain(null, what)
            msg.data = bundle
            replyTo?.send(msg)
        }
    }

    private fun snapshot(
        ids: LongArray = longArrayOf(1L, 2L),
        names: Array<String> = arrayOf("Ada", "Bo"),
        lats: DoubleArray = doubleArrayOf(37.1, 37.2),
        lngs: DoubleArray = doubleArrayOf(-122.1, -122.2),
        timestamps: LongArray = longArrayOf(100L, 200L),
        batteries: FloatArray? = floatArrayOf(0.5f, 0.9f),
    ): Bundle = Bundle().apply {
        putLongArray(FamilyLocationProtocol.KEY_IDS, ids)
        putStringArray(FamilyLocationProtocol.KEY_NAMES, names)
        putDoubleArray(FamilyLocationProtocol.KEY_LATS, lats)
        putDoubleArray(FamilyLocationProtocol.KEY_LNGS, lngs)
        putLongArray(FamilyLocationProtocol.KEY_TIMESTAMPS, timestamps)
        if (batteries != null) putFloatArray(FamilyLocationProtocol.KEY_BATTERIES, batteries)
    }

    private fun pump() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun `bind registers and snapshots parse through the handler`() {
        val service = FakeService()
        val component = ComponentName("com.vayunmathur.findfamily", ".FamilyService")
        val resolve = ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply {
                packageName = component.packageName
                name = component.className
            }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(FamilyLocationProtocol.ACTION), resolve)
        shadowOf(app).setComponentNameAndServiceForBindService(component, service.messenger.binder)

        val updates = mutableListOf<List<FamilyMember>>()
        val client = FamilyLocationClient(app) { updates.add(it) }
        client.bind()
        client.bind() // already bound: no second registration
        pump()
        assertEquals(listOf(FamilyLocationProtocol.MSG_REGISTER), service.received)

        // A well-formed snapshot with batteries.
        service.push(snapshot())
        pump()
        assertEquals(1, updates.size)
        assertEquals(
            FamilyMember(1L, "Ada", 37.1, -122.1, 100L, 0.5f),
            updates.last()[0],
        )
        assertEquals(0.9f, updates.last()[1].battery)

        // Without a battery array the sentinel stands in.
        service.push(snapshot(batteries = null))
        pump()
        assertEquals(-1f, updates.last()[0].battery)

        // Mismatched arrays truncate to the length they agree on.
        service.push(snapshot(names = arrayOf("Only")))
        pump()
        assertEquals(1, updates.last().size)
        assertEquals("Only", updates.last()[0].name)

        // A snapshot missing a required array reads as nobody.
        val broken = snapshot()
        broken.remove(FamilyLocationProtocol.KEY_NAMES)
        service.push(broken)
        pump()
        assertEquals(emptyList(), updates.last())

        // An unrelated message is not a snapshot.
        val before = updates.size
        service.push(snapshot(), what = 99)
        pump()
        assertEquals(before, updates.size)

        // Unbind unregisters exactly once.
        client.unbind()
        pump()
        assertEquals(FamilyLocationProtocol.MSG_UNREGISTER, service.received.last())
        val count = service.received.count { it == FamilyLocationProtocol.MSG_UNREGISTER }
        client.unbind()
        pump()
        assertEquals(count, service.received.count { it == FamilyLocationProtocol.MSG_UNREGISTER })
        assertTrue(service.received.contains(FamilyLocationProtocol.MSG_REGISTER))
    }
}
