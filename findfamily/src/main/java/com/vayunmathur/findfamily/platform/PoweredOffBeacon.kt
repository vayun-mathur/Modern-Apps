@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.findfamily.platform

import android.content.Context
import android.nearby.NearbyManager
import android.os.Build
import com.vayunmathur.library.log.Log
import com.vayunmathur.e2ee.E2eeKeyStore
import com.vayunmathur.findfamily.BuildConfig
import com.vayunmathur.findfamily.data.DirectBootStore
import com.vayunmathur.findfamily.data.PoweredOffKeyStore
import com.vayunmathur.findfamily.domain.PoweredOffProtocol
import com.vayunmathur.findfamily.domain.PoweredOffRecovery
import com.vayunmathur.findfamily.domain.RecoveryDistribution
import com.vayunmathur.findfamily.domain.poweredOffGrantSigningBytes
import com.vayunmathur.findfamily.network.Networking
import com.vayunmathur.findfamily.network.registerPoweredOffEids
import com.vayunmathur.findfamily.domain.PoweredOffGrant
import com.vayunmathur.findfamily.domain.UwbEnvelope
import com.vayunmathur.findfamily.domain.UwbEnvelopeKind
import com.vayunmathur.library.util.DataStoreUtils
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/**
 * Powered-off finding, beacon half: hands the Bluetooth controller a batch of rotating ephemeral
 * identifiers just before the device powers down, so a family member walking past can still
 * sight it.
 *
 * Off unless the user has explicitly turned it on. See [isEnabled].
 *
 * The scheme itself lives in [PoweredOffProtocol] and is deliberately **not** restated here — the
 * finder half derives from the same object, and two copies of a derivation that must agree
 * byte-for-byte is exactly how this feature fails silently. This class only decides *when* to
 * arm, *what* to persist, and *what* to tell the user.
 *
 * ## What the platform does with the list
 *
 * `NearbyManager.setPoweredOffFindingEphemeralIds` copies it into the Bluetooth controller's own
 * memory over vendor HCI `0xfd62`. Once the AP is down the controller — not Android — advertises
 * it: ~20 s delay, then index 0, advancing one index every 1024 s at a 2 s advertising interval.
 *
 * The consequences are all in [PoweredOffProtocol]: 256 keys maximum because the key index is one
 * byte on the wire, so 3.03 days of coverage per shutdown, and unspecified behaviour when the
 * list runs out. That last one is the reason for [ARM_CONTROLLER] below.
 */
object PoweredOffBeacon {

    private const val TAG = "FF-POF"

    /**
     * Whether to actually switch the controller on.
     *
     * Everything else in this class runs regardless: EIDs are derived, registered with the relay
     * and persisted, and the user-facing switch works. This gates only the final
     * `setPoweredOffFindingMode(ENABLED)` — the step that makes a switched-off phone transmit.
     *
     * It is dev-only on purpose, and the reason is not caution for its own sake. What the
     * controller does when it walks past the last key is not defined in the AIDL, the HAL, or any
     * test we can read; the walk happens in closed Broadcom firmware. If it stops, the feature
     * quietly expires after three days. If it wraps, the phone replays the same 256 identifiers
     * on a loop until the battery dies, and an observer who sees one twice knows it is the same
     * device — a fixed trackable identifier emitted by a phone the user believes is off. We
     * cannot test which, cannot fix it if it is the bad one (the SoC is down; nothing can refill
     * the buffer), and the app's threat model includes the people best placed to notice.
     *
     * The team's position is that stopping is far more likely, because Google ships this same
     * controller on retail Pixels and a static identifier would trip the unwanted-tracking
     * standard they co-authored. That is inference, not proof, and it is not enough to ship a
     * transmitter on. Flip this to unconditional when someone documents the firmware behaviour.
     */
    private val ARM_CONTROLLER = BuildConfig.DEV_BUILD

    private const val KEY_ENABLED = "pof_enabled"

    /**
     * Device-protected, for the same reason [DirectBootStore] exists: arming runs from a
     * broadcast receiver that can fire before first unlock, because a battery-low shutdown does
     * not wait for a passcode. Credential-encrypted storage is unreadable exactly then.
     *
     * **Every reader of [PoweredOffKeyStore] must pass this same store**, and nothing enforces
     * that — the class takes whatever [com.vayunmathur.library.util.DataStoreUtils] it is handed.
     * Writing here and reading from the credential-encrypted store makes `canRead` false forever:
     * no exception, no failed call, nothing in logcat, just zero sightings retrieved. That has
     * already happened once between the two halves of this feature. If a third caller appears,
     * make the store choice internal to [PoweredOffKeyStore] rather than repeating this comment.
     */
    private fun keys(context: Context) = PoweredOffKeyStore(DirectBootStore.store(context))

    /** Adapts the direct-boot store to the e2ee module's storage abstraction. */
    private class DirectBootKeyStore(private val ds: DataStoreUtils) : E2eeKeyStore {
        override suspend fun getBytes(name: String): ByteArray? = ds.getByteArrayAwait(name)
        override suspend fun setBytes(name: String, value: ByteArray, onlyIfAbsent: Boolean) =
            ds.setByteArray(name, value, onlyIfAbsent)
    }

    /**
     * The recovery keypair and its grant roster, on the **same** direct-boot store as [keys] for
     * the reason spelled out above: two stores is how this feature has already broken once, and
     * the recovery private key has to be filed into [PoweredOffKeyStore] alongside the beacon
     * secret anyway.
     *
     * Device-protected storage is not credential-encrypted, so this is a key sitting outside the
     * passcode. That is not a regression — the identity key it replaces was already mirrored here
     * as `ff_pqcKemPriv` — and it is a strict improvement, because what is exposed now only opens
     * powered-off sightings instead of every location the user has ever shared.
     */
    private fun recovery(context: Context) =
        PoweredOffRecovery(DirectBootKeyStore(DirectBootStore.store(context)))

    /**
     * Opt-in, default OFF, and it must stay default OFF. A phone the user believes is switched
     * off does not start advertising because a default said so.
     */
    suspend fun isEnabled(context: Context): Boolean =
        DirectBootStore.store(context).getBooleanAwait(KEY_ENABLED, false)

    /**
     * Turn the beacon on or off.
     *
     * Turning it on mints the beacon secret and a dedicated recovery keypair, and files both
     * under the owner's own userid — so sightings are readable here, and can be made readable by
     * a family member the user explicitly picks. Nobody is picked by default; see
     * [grantRecovery].
     *
     * Turning it off clears the controller as well as the flag, so a device armed by an earlier
     * shutdown stops at the next one instead of running out its three days. It also drops the
     * recovery keypair and the whole grant roster: leaving the roster behind would silently
     * re-entitle everyone on it the next time the switch went back on, which is not what "off"
     * looked like it meant.
     */
    suspend fun setEnabled(context: Context, enabled: Boolean) {
        DirectBootStore.store(context).setBoolean(KEY_ENABLED, enabled)
        if (enabled) {
            provisionKeys(context)
        } else {
            recovery(context).clear()
            // Also drop this device's own entry, or `canRead` stays true and the retrieval loop
            // keeps querying 258 handles every five minutes for a beacon that is switched off.
            Networking.userid.takeIf { it != 0L }?.let { keys(context).forget(it) }
            disarm(context)
        }
    }

    /**
     * The system service, or null if this build cannot have it.
     *
     * The SDK_INT gate is not belt and braces. `:library:aosp-stubs` is `compileOnly`, so
     * `android.nearby.NearbyManager` is a compile-time promise about a class that only exists at
     * runtime from API 35 — and findfamily's minSdk is 31. Touching `NearbyManager::class.java`
     * on API 31-34 raises NoClassDefFoundError, which is an [Error] and would sail straight past
     * every `catch (e: Exception)` below and out through the shutdown receiver. Resolving the
     * class must be guarded by the version check, not by a try block.
     */
    private fun manager(context: Context): NearbyManager? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
        return context.getSystemService(NearbyManager::class.java)
    }

    /**
     * Whether this device can do powered-off finding, as far as we are allowed to ask.
     *
     * There is no clean test. The framework's own `isPoweredOffFindingSupported()` is private,
     * and the one public method that reports it — `getPoweredOffFindingMode()` — is itself behind
     * BLUETOOTH_PRIVILEGED. So where findfamily is not a priv-app we cannot distinguish "the chip
     * cannot do this" from "we are not allowed to ask". Both mean it will not work here, so both
     * return false and the UI says the same thing.
     */
    suspend fun isSupported(context: Context): Boolean {
        val manager = manager(context) ?: return false
        return runCatching {
            manager.getPoweredOffFindingMode() != NearbyManager.POWERED_OFF_FINDING_MODE_UNSUPPORTED
        }.getOrDefault(false)
    }

    /**
     * Mint and file the keys needed to read this device's future sightings.
     *
     * Done at opt-in rather than at shutdown because it writes to storage and the shutdown window
     * is measured in seconds.
     *
     * What gets filed is the **raw ML-KEM private DER** of the dedicated recovery keypair, and
     * NOT a composite `[4B kemPrivLen][kemPriv][dsaPriv]` bundle of the kind `TrackerProtocol`
     * uses. A tracker's identity is minted by the phone on the tracker's behalf and is carried as
     * that composite; a powered-off phone is not a tracker. The distinction is invisible at the
     * type level — both are `ByteArray` — and getting it wrong is silent: the bundle parser reads
     * the DER's leading SEQUENCE header as a length field and every decrypt fails into a log
     * line. It has already happened once.
     *
     * ## Why this is no longer `ff_pqcKemPriv`
     * It used to be, and that is exactly what made the feature unshareable. A powered-off phone
     * cannot decrypt its own sightings, so somebody else has to hold the key — and the identity
     * private key decrypts every live location this user publishes to anyone, forever. Handing
     * that to a family member to help find a lost phone is not a trade worth making, so sightings
     * are sealed to a key that opens sightings and nothing else. See [PoweredOffRecovery] for the
     * grant model and for the two things rotation cannot undo.
     *
     * Unconditionally re-files rather than returning early when the store already has something:
     * [PoweredOffRecovery.revoke] rotates, and a stale private key left here would leave the
     * owner's own device unable to read its own sightings — silently, like every other failure in
     * this feature.
     */
    private suspend fun provisionKeys(context: Context) {
        val userId = Networking.userid
        if (userId == 0L) {
            Log.status(TAG, "no identity yet, deferring key provisioning")
            return
        }
        val store = keys(context)
        val secret = store.secret(userId)
            ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
        store.save(userId, secret, recovery(context).ensure().kemPrivate)
    }

    // ------------------------------------------------------------------
    // Recovery-key sharing: who, besides this device, can find it when it is off
    // ------------------------------------------------------------------

    /** Peers currently able to decrypt this device's sightings. Empty until an explicit grant. */
    suspend fun recoveryGrantees(context: Context): Set<Long> = recovery(context).grantees()

    /**
     * Let [peerId] find this phone while it is switched off, and send them the keys to do it.
     *
     * Returns false when there is nothing to send yet (no identity, or the beacon has never been
     * switched on so there is no secret) or the peer could not be reached. A false here leaves
     * the roster updated but the peer without keys; re-granting redelivers, which is the intended
     * repair and is why [PoweredOffRecovery.grant] is not idempotent about delivery.
     *
     * What [peerId] can do afterwards, stated because the UI has to say it: decrypt this device's
     * powered-off sightings, *and* derive its EIDs from the beacon secret and recognise it in the
     * wild. The secret is not optional — [PoweredOffProtocol.recentHandles] is what tells them
     * which handles to ask the relay for — so the second capability comes with the first.
     */
    suspend fun grantRecovery(context: Context, peerId: Long): Boolean {
        val userId = Networking.userid
        if (userId == 0L) return false
        val secret = keys(context).secret(userId) ?: run {
            Log.status(TAG, "no beacon secret yet, cannot grant recovery")
            return false
        }
        val distribution = recovery(context).grant(peerId)
        return deliver(distribution, secret)
    }

    /**
     * Withdraw [peerId]'s ability to find this phone when it is off.
     *
     * Rotates **both** halves of what they were given, because they do two different things and
     * only rotating one leaves the other alive:
     *  - the recovery keypair, so nothing sealed from now on is readable with their copy;
     *  - the beacon secret, so they can no longer derive this phone's EIDs. Without this they
     *    could go on recognising and following the powered-off phone indefinitely — the exact
     *    capability the grant UI warns about, and therefore the exact capability revoking has to
     *    take back.
     *
     * Re-files both locally so this device can still read its own sightings, then redelivers to
     * everyone still granted. Returns false if any redelivery failed; the revocation itself has
     * already taken effect locally either way.
     *
     * **Forward-only.** [peerId] can still open any ciphertext they already fetched, and the new
     * public half does not reach finders until this phone next registers on shutdown, so a beacon
     * window already in flight keeps being sealed to the old key. Rotation also retires the
     * previous private key, so sightings still sitting on the relay from the current window
     * become unreadable to the owner as well.
     */
    suspend fun revokeRecovery(context: Context, peerId: Long): Boolean {
        val userId = Networking.userid
        if (userId == 0L) return false
        val store = keys(context)
        val distribution = recovery(context).revoke(peerId)
        val freshSecret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        // Before anything goes out: the owner reads their own sightings through the same store,
        // so a rotation that forgot this would revoke the user from themselves.
        store.save(userId, freshSecret, distribution.kemPrivate)
        return deliver(distribution, freshSecret)
    }

    /** Seal [distribution] to each recipient's own public bundle and send. */
    private suspend fun deliver(distribution: RecoveryDistribution, secret: ByteArray): Boolean {
        val owner = Networking.userid
        var allOk = true
        for (peer in distribution.to) {
            // Signed per recipient, because the recipient is inside the signed bytes: a grant
            // lifted off one peer cannot be replayed at another.
            val signature = Networking.signAsMe(
                poweredOffGrantSigningBytes(owner, peer, distribution.epoch, secret, distribution.kemPrivate)
            )
            if (signature == null) {
                Log.status(TAG, "cannot sign recovery grant, not delivering to ${peer.toULong()}")
                allOk = false
                continue
            }
            val grant = PoweredOffGrant(
                epoch = distribution.epoch,
                secretB64 = Base64.encode(secret),
                recoveryPrivB64 = Base64.encode(distribution.kemPrivate),
                sigB64 = Base64.encode(signature),
            )
            // publishUwbMessage resolves the peer's existing ML-KEM bundle and seals with
            // Pqc.encryptTo before the frame reaches the socket, so the relay only ever forwards
            // bytes it has no key for.
            val ok = Networking.publishUwbMessage(grantEnvelope(grant), peer)
            if (!ok) Log.status(TAG, "could not deliver recovery keys to ${peer.toULong()}")
            allOk = allOk && ok
        }
        return allOk
    }

    private fun grantEnvelope(grant: PoweredOffGrant) = UwbEnvelope(
        // Nothing correlates these to a ranging session; the field is required by the shared
        // envelope and a fresh id keeps them from matching any `UwbSessionManager` await.
        sessionId = Uuid.random().toString(),
        sender = Networking.userid.toULong(),
        senderPlatform = "android",
        kind = UwbEnvelopeKind.POF_GRANT,
        recovery = grant,
    )

    /**
     * Hand a fresh batch to the controller and, if [ARM_CONTROLLER], switch advertising on.
     * Called from [FinalLocationReporter] on the shutdown broadcast.
     *
     * Returns the EID count armed, or null if nothing was armed — which is an ordinary outcome,
     * not an error. Everything is logged rather than thrown: this runs inside the broadcast that
     * blocks the user's power-off and nothing here is worth delaying that for.
     *
     * The EIDs are derived here rather than precomputed because index 0 must be the slot
     * containing the shutdown instant, which is only known now. The cost is 256 HMAC-SHA256
     * invocations over a 14-byte message — negligible beside the ~22 vendor HCI commands the
     * framework then issues to move the result into the controller.
     */
    // Broad trailing catch is deliberate: the controller call crosses into the vendor
    // Bluetooth stack, whose failures surface as undocumented runtime exceptions.
    @Suppress("TooGenericExceptionCaught")
    suspend fun armForShutdown(context: Context): Int? {
        if (!isEnabled(context)) return null

        val userId = Networking.userid
        if (userId == 0L) return null
        val secret = keys(context).secret(userId) ?: run {
            Log.status(TAG, "no beacon secret, not armed")
            return null
        }

        val eids = PoweredOffProtocol.armingEids(secret)

        // Before the radios go down, and before the controller is told anything. Once the phone
        // is off it cannot re-register and will not self-heal, so a beacon the relay has never
        // heard of would advertise for three days into nothing.
        //
        // The recovery bundle goes up with the EIDs because this is the only moment it can: it is
        // what finders will seal to for the whole window, and a rotation since the last shutdown
        // does not reach the relay until now.
        val recoveryKeys = recovery(context).ensure()
        // `ensure` mints when the keypair is blank, and it is blank after `setEnabled(false)`.
        // Registering a public half whose private half is not in the sighting store would leave
        // the owner unable to read their own sightings, so re-file before registering — the same
        // trap `revokeRecovery` guards against.
        keys(context).save(userId, secret, recoveryKeys.kemPrivate)
        if (!Networking.registerPoweredOffEids(userId, eids, recoveryKeys.publicBundle)) {
            Log.status(TAG, "relay did not accept the EID list, not arming")
            return null
        }

        val manager = manager(context)
        if (manager == null) {
            Log.status(TAG, "no NearbyManager on this build")
            return null
        }

        return try {
            manager.setPoweredOffFindingEphemeralIds(eids)
            if (ARM_CONTROLLER) {
                manager.setPoweredOffFindingMode(NearbyManager.POWERED_OFF_FINDING_MODE_ENABLED)
                Log.status(TAG, "armed ${eids.size} EIDs (~3 days)")
            } else {
                Log.status(TAG, "loaded ${eids.size} EIDs; controller NOT enabled (release build)")
            }
            eids.size
        } catch (e: UnsupportedOperationException) {
            // Neither ro. nor persist.bluetooth.finder.supported is set. There is no public
            // predicate to ask first, so catching this is the supported way to feature-detect.
            Log.status(TAG, "powered-off finding unsupported on this device", e)
            null
        } catch (e: SecurityException) {
            // BLUETOOTH_PRIVILEGED is signature|privileged. findfamily only holds it when it is
            // installed as a MAOS priv-app with a privapp-permissions entry, which as of today it
            // is not — vendor/modern-apps/Android.bp declares it an ordinary user app.
            Log.status(TAG, "BLUETOOTH_PRIVILEGED not held, beacon not armed", e)
            null
        } catch (e: IllegalStateException) {
            // setPoweredOffFindingMode(ENABLED) requires Bluetooth and location both on. The EIDs
            // are already in the controller but will not be advertised, which is correct: the
            // user turned a radio off.
            Log.status(TAG, "Bluetooth or location is off, beacon not armed", e)
            null
        } catch (e: Exception) {
            // Vendor HAL boundary: the controller call above crosses into the Bluetooth
            // stack, whose failures surface as undocumented runtime exceptions.
            Log.status(TAG, "arming failed", e)
            null
        }
    }

    private fun disarm(context: Context) {
        val manager = manager(context) ?: return
        runCatching {
            manager.setPoweredOffFindingMode(NearbyManager.POWERED_OFF_FINDING_MODE_DISABLED)
        }.onFailure { Log.status(TAG, "disarm skipped: ${it.javaClass.simpleName}") }
    }
}
