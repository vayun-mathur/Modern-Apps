package com.vayunmathur.findfamily.ipc

import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.LocationSource
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TestFindFamilyRepository
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.domain.FamilyLocationProtocol
import com.vayunmathur.findfamily.service.FamilyLocationService
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * [FamilyLocationService] streaming over the installed in-memory singleton:
 * a registered Messenger client receives parallel-array snapshots built
 * from genuine Room flows, and unregistration/unbind stops the push.
 */
@RunWith(AndroidJUnit4::class)
class FamilyLocationServiceRealRepoTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setup() {
        runCatching {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
    }

    @Test fun registeredClientReceivesSeededSnapshot() { runBlocking {
        val repo = TestFindFamilyRepository.install(app)
        val alice = repo.upsertUser(User(
            name = "Alice", photo = null, locationName = "Home", sendingEnabled = true,
            requestStatus = RequestStatus.MUTUAL_CONNECTION, lastLocationChangeTime = Clock.System.now(),
        ))
        val bob = repo.upsertUser(User(
            name = "Bob", photo = null, locationName = "Work", sendingEnabled = true,
            requestStatus = RequestStatus.MUTUAL_CONNECTION, lastLocationChangeTime = Clock.System.now(),
        ))
        val now = Clock.System.now()
        repo.upsertLocation(LocationValue(
            userid = alice, coord = Coord(10.0, 20.0), speed = 0f, acc = 5f,
            timestamp = now, battery = 77f, reportedAt = now, source = LocationSource.LIVE,
        ))
        repo.upsertLocation(LocationValue(
            userid = bob, coord = Coord(30.0, 40.0), speed = 0f, acc = 5f,
            timestamp = now, battery = 55f, reportedAt = now, source = LocationSource.LIVE,
        ))

        val received = mutableListOf<Bundle>()
        val client = Messenger(Handler(Looper.getMainLooper()) { msg ->
            if (msg.what == FamilyLocationProtocol.MSG_LOCATIONS) {
                received.add(Bundle(msg.data))
                true
            } else {
                false
            }
        })

        val controller = Robolectric.buildService(FamilyLocationService::class.java).create()
        val service = controller.get()
        val binder = service.onBind(null)
        // Register through the service's incoming Messenger, as a maps client would.
        val incoming = Messenger(binder)
        incoming.send(Message.obtain(null, FamilyLocationProtocol.MSG_REGISTER).apply { replyTo = client })

        val deadline = System.currentTimeMillis() + 8_000
        while (received.isEmpty() && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        assertTrue(received.isNotEmpty(), "client received a locations snapshot")
        val snapshot = received.last()
        val names = snapshot.getStringArray(FamilyLocationProtocol.KEY_NAMES)?.toList().orEmpty()
        assertTrue(names.containsAll(listOf("Alice", "Bob")), "snapshot names: $names")
        assertEquals(2, snapshot.getLongArray(FamilyLocationProtocol.KEY_IDS)?.size)
        assertEquals(2, snapshot.getDoubleArray(FamilyLocationProtocol.KEY_LATS)?.size)
        assertTrue(snapshot.getFloatArray(FamilyLocationProtocol.KEY_BATTERIES)?.toList()?.contains(77f) == true)

        incoming.send(Message.obtain(null, FamilyLocationProtocol.MSG_UNREGISTER).apply { replyTo = client })
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(false, service.onUnbind(null))
        controller.destroy()
    }
} }
