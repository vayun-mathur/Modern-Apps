package com.vayunmathur.findfamily.platform

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.DirectBootStore
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TestFindFamilyRepository
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.PoweredOffKeyStore
import com.vayunmathur.findfamily.network.Networking
import com.vayunmathur.library.network.WebSocketClient
import com.vayunmathur.library.network.WsSession
import com.vayunmathur.library.util.DataStoreUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import kotlin.io.encoding.Base64
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * [PoweredOffBeacon] opt-in lifecycle over the direct-boot store with a
 * real PQC identity and a fake live socket: enabling provisions readable
 * keys, grants deliver signed key material to a peer, and revoking rotates
 * the beacon secret while "off" clears the roster and our own entry.
 *
 * The NearbyManager controller arm itself is device-bound and is not
 * exercised here (see `armForShutdown`); `isSupported` must simply answer
 * false on this host rather than throw.
 */
@RunWith(AndroidJUnit4::class)
class PoweredOffBeaconRealRepoTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @BeforeTest
    fun setup() = runBlocking {
        val repo = TestFindFamilyRepository.install(ctx)
        Networking.stopLive()
        Networking.init(repo, DataStoreUtils.getInstance(ctx), "Me")
        Networking.repository = repo
        val clientConstructor = WebSocketClient::class.java
            .getDeclaredConstructor(
                Socket::class.java, java.io.InputStream::class.java,
                java.io.OutputStream::class.java, Map::class.java, Map::class.java,
            ).apply { isAccessible = true }
        val client = clientConstructor.newInstance(
            Socket(), ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(),
            emptyMap<String, List<String>>(), emptyMap<String, String>(),
        )
        Networking.wsSession = WsSession::class.java
            .getDeclaredConstructor(WebSocketClient::class.java)
            .apply { isAccessible = true }
            .newInstance(client)
        Unit
    }

    private fun ownStore() = PoweredOffKeyStore(DirectBootStore.store(ctx))

    @Test fun optInProvisionsKeysAndOptOutClearsThem() = runBlocking {
        assertFalse(PoweredOffBeacon.isEnabled(ctx))
        PoweredOffBeacon.setEnabled(ctx, true)
        assertTrue(PoweredOffBeacon.isEnabled(ctx))
        val store = ownStore()
        assertTrue(store.canRead(Networking.userid), "own sightings readable after opt-in")
        assertNotNull(store.secret(Networking.userid))
        assertTrue(PoweredOffBeacon.recoveryGrantees(ctx).isEmpty())
        PoweredOffBeacon.setEnabled(ctx, false)
        assertFalse(PoweredOffBeacon.isEnabled(ctx))
        assertFalse(store.canRead(Networking.userid))
        assertTrue(PoweredOffBeacon.recoveryGrantees(ctx).isEmpty())
    }

    @Test fun grantAndRevokeRecoveryRoundTrip() = runBlocking {
        val repo = TestFindFamilyRepository.install(ctx)
        Networking.repository = repo
        PoweredOffBeacon.setEnabled(ctx, true)
        val bundle = Base64.encode(Networking.pqcIdentity.publicBundle)
        val peerId = repo.upsertUser(User(
            name = "Finder", photo = null, locationName = "Home", sendingEnabled = true,
            requestStatus = RequestStatus.MUTUAL_CONNECTION, lastLocationChangeTime = Clock.System.now(),
            pqcEncryptionKey = bundle, outboundPairId = "pair",
        ))
        val store = ownStore()
        val secretBefore = store.secret(Networking.userid)
        assertNotNull(secretBefore)
        assertTrue(PoweredOffBeacon.grantRecovery(ctx, peerId))
        assertEquals(setOf(peerId), PoweredOffBeacon.recoveryGrantees(ctx))
        // Revoking rotates the beacon secret and empties the roster.
        assertTrue(PoweredOffBeacon.revokeRecovery(ctx, peerId))
        assertTrue(PoweredOffBeacon.recoveryGrantees(ctx).isEmpty())
        val secretAfter = store.secret(Networking.userid)
        assertNotNull(secretAfter)
        assertFalse(secretBefore.contentEquals(secretAfter), "secret rotated on revoke")
        assertTrue(store.canRead(Networking.userid))
        PoweredOffBeacon.setEnabled(ctx, false)
    }

    @Test fun grantWithoutProvisionedSecretFails() = runBlocking {
        PoweredOffBeacon.setEnabled(ctx, false)
        // No secret was ever provisioned for this identity in this test's store.
        val store = ownStore()
        store.forget(Networking.userid)
        assertFalse(PoweredOffBeacon.grantRecovery(ctx, 999L))
    }

    @Test fun supportCheckAnswersFalseWithoutThrowing() = runBlocking {
        // NearbyManager mode query needs BLUETOOTH_PRIVILEGED on a real
        // device and no manager at all may exist here; either way, false.
        assertFalse(PoweredOffBeacon.isSupported(ctx))
    }
}
