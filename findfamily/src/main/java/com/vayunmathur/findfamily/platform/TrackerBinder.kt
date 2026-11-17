package com.vayunmathur.findfamily.platform
import com.vayunmathur.findfamily.domain.TrackerProtocol
import com.vayunmathur.findfamily.data.TrackerStore
import com.vayunmathur.findfamily.network.generatePqcIdentityKeyPair

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.UserKind
import com.vayunmathur.findfamily.network.Networking
import com.vayunmathur.findfamily.network.registerTracker
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Owner-side binding: mint a tracker identity, persist it, register it with the
 * server, and provision the physical device over BLE GATT. Centralised here (rather
 * than in the ViewModel) so the whole tracker feature stays in one package behind
 * `TrackerFeature.enabled`.
 */
object TrackerBinder {
    private const val TAG = "TrackerBinder"

    /**
     * Bind [device] as a new tracker named [name].
     *
     * Steps: generate an ML-KEM/ML-DSA keypair and a 32-byte beacon secret, provision the
     * device over GATT, and only then persist anything — the private bundle and secret in
     * [TrackerStore], the `User(kind = TRACKER)` row, and the server-side registration.
     * Returns true iff the GATT provisioning write succeeds.
     *
     * The provisioning write comes first deliberately. Persisting up front meant every
     * failed bind left an orphan tracker in the family list and orphan secrets in
     * DataStore, which is how a handful of retries turned into a list full of trackers
     * that were never really bound. The cost of this order is a narrow window where a
     * crash between the write and the persist leaves a provisioned device the app doesn't
     * know about; that recovers by long-pressing the button and binding again, which is a
     * far better failure mode.
     */
    suspend fun bind(context: Context, name: String, device: BluetoothDevice): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val repository = FindFamilyRepository.get(context)
                val store = TrackerStore(DataStoreUtils.getInstance(context))

                val keys = Networking.generatePqcIdentityKeyPair()
                val publicBundle = decodeB64(keys.publicBundleB64)
                val privateBundle = decodeB64(keys.privateBundleB64)
                val secret = ByteArray(TrackerProtocol.SECRET_LEN).also { SecureRandom().nextBytes(it) }
                val trackerId = Random.nextLong(from = 1, until = Long.MAX_VALUE)

                if (!TrackerProvisioner(context).provision(device, trackerId, secret)) {
                    Log.status(TAG, "provisioning write failed; not persisting tracker $trackerId")
                    return@runCatching false
                }

                store.save(trackerId, secret, privateBundle, device.address)
                repository.upsertUser(
                    User(
                        name = name,
                        photo = null,
                        locationName = "Unknown Location",
                        sendingEnabled = false,
                        requestStatus = RequestStatus.MUTUAL_CONNECTION,
                        lastLocationChangeTime = Clock.System.now(),
                        encryptionKey = null,
                        id = trackerId,
                        pqcEncryptionKey = keys.publicBundleB64,
                        kind = UserKind.TRACKER,
                    )
                )
                // Best-effort: registration self-heals on the next heartbeat if the
                // socket is momentarily down (see pollTrackerReports).
                runCatching { Networking.registerTracker(trackerId, secret, publicBundle) }
                true
            }.onFailure { Log.status(TAG, "bind failed", it) }.getOrDefault(false)
        }

    private fun decodeB64(s: String): ByteArray =
        android.util.Base64.decode(s, android.util.Base64.NO_WRAP)
}
