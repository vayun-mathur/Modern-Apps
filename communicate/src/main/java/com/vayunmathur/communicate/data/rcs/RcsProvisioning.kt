package com.vayunmathur.communicate.data.rcs

import android.telephony.ims.stub.ImsRegistrationImplBase
import android.telephony.ims.feature.MmTelFeature
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telephony.ims.ImsManager
import com.vayunmathur.library.log.Log
import androidx.core.content.ContextCompat
import com.vayunmathur.library.network.NetworkClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Why the RCS transport is unavailable. Surfaced in the UI so an unprovisioned
 * SIM (e.g. Astound with an empty `rcs_config_server_url`) reads as a reason,
 * not a silent failure.
 */
enum class RcsUnavailableReason {
    NoEntitlementUrl,
    NoCarrierPrivilege,
    NotSupported,
    NoSubscription,
    ProvisioningRequired,
    UceDisabled,
    TransportDenied,
    ServiceUnavailable,
    Unknown,
}

/**
 * Lifecycle of the RCS single-registration transport.
 *
 * `Disabled` is the release-build state (gate off). Everything else is dev-only.
 * There are no RCS credentials to persist — provisioning IS the session — so
 * this doubles as the line session: `Available` means signed-in.
 */
sealed interface RcsRegistrationState {
    /** Registration state not yet determined. */
    data object Unknown : RcsRegistrationState
    /** Carrier RCS provisioning is in progress. */
    data object Provisioning : RcsRegistrationState
    /** RCS is provisioned and available. */
    data object Available : RcsRegistrationState
    /** RCS is unavailable, with the reason. */
    data class Unavailable(val reason: RcsUnavailableReason) : RcsRegistrationState
    /** RCS is disabled (release builds / gate off). */
    data object Disabled : RcsRegistrationState
}

/**
 * TS.43 entitlement + carrier-config provisioning check.
 *
 * Reads `rcs_config_server_url` for the active data subscription; an empty URL
 * means the carrier never provisioned RCS (the Astound case) and maps to
 * [RcsUnavailableReason.NoEntitlementUrl]. Never throws; caches the last
 * result. All entry points early-return `Disabled` when the dev gate is off so
 * release builds strip cleanly.
 */
object RcsProvisioning {
    private const val TAG = "RcsProvisioning"

    private val _state = MutableStateFlow<RcsRegistrationState>(RcsRegistrationState.Unknown)
    val state: StateFlow<RcsRegistrationState> = _state.asStateFlow()

    /** Last carrier config URL seen, for the status screen. Null = never probed. */
    @Volatile
    var lastConfigServerUrl: String? = null
        private set

    fun reset() {
        _state.value = if (RcsFeature.enabled) RcsRegistrationState.Unknown else RcsRegistrationState.Disabled
        lastConfigServerUrl = null
    }

    /**
     * Run the provisioning check for [subscriptionId] (defaults to the default
     * SMS subscription, mirroring TestRcsApp). Updates [state]; also returns it.
     */
    suspend fun probe(context: Context, subscriptionId: Int = defaultSubscriptionId()): RcsRegistrationState {
        if (!RcsFeature.enabled) {
            _state.value = RcsRegistrationState.Disabled
            return _state.value
        }
        _state.value = RcsRegistrationState.Provisioning
        _state.value = checkProvisioning(context.applicationContext, subscriptionId)
        return _state.value
    }

    private fun checkProvisioning(context: Context, subscriptionId: Int): RcsRegistrationState {
        if (!SubscriptionManager.isValidSubscriptionId(subscriptionId)) {
            Log.status(TAG, "probe: invalid subId=$subscriptionId")
            return RcsRegistrationState.Unavailable(RcsUnavailableReason.NoSubscription)
        }
        readConfigServerUrl(context, subscriptionId)?.let { return it }
        provisioningGate(context, subscriptionId)?.let { return it }
        singleRegGate(context, subscriptionId)?.let { return it }
        Log.status(TAG, "probe: AVAILABLE for subId=$subscriptionId")
        return RcsRegistrationState.Available
    }

    /** Carrier-config URL probe; returns a terminal state, or null to continue. */
    private fun readConfigServerUrl(context: Context, subscriptionId: Int): RcsRegistrationState? {
        // Carrier config is the source of truth for "does this SIM do RCS".
        // Keyed query avoids the deprecated whole-bundle getter.
        val url = try {
            val ccm = context.getSystemService(CarrierConfigManager::class.java)
                ?: return RcsRegistrationState.Unavailable(
                    RcsUnavailableReason.ServiceUnavailable,
                )
            // The keyed getter is API 34+; on 31-33 fall back to the
            // whole-bundle getter for the subscription and read the key.
            val bundle = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ccm.getConfigForSubId(
                    subscriptionId,
                    CarrierConfigManager.KEY_RCS_CONFIG_SERVER_URL_STRING,
                )
            } else {
                @Suppress("DEPRECATION")
                ccm.getConfigForSubId(subscriptionId)
            }
            bundle?.getString(CarrierConfigManager.KEY_RCS_CONFIG_SERVER_URL_STRING).orEmpty()
        } catch (e: SecurityException) {
            Log.status(TAG, "probe: carrier-config read denied: $e")
            return RcsRegistrationState.Unavailable(RcsUnavailableReason.ServiceUnavailable)
        } catch (e: Exception) {
            Log.status(TAG, "probe: carrier-config read failed: $e")
            return RcsRegistrationState.Unavailable(RcsUnavailableReason.ServiceUnavailable)
        }
        lastConfigServerUrl = url.ifEmpty { null }
        // Note — an empty config URL no longer hard-blocks. MVNOs and
        // Jibe-backed carriers (US Mobile, for one) routinely leave the
        // rcs_config_server_url key empty while still accepting a single-reg
        // delegate; the delegate request itself is the real test, and a denial
        // surfaces as TransportDenied via onFeatureTagStatusChanged. The URL
        // is still recorded for the status screen and the TS.43 entitlement.
        if (url.isBlank()) {
            Log.status(
                "RcsProvisioning",
                "No carrier RCS config URL; attempting delegate creation anyway",
            )
        }
        return null
    }

    /** Provisioning-manager gate; returns a terminal state, or null to continue. */
    private fun provisioningGate(context: Context, subscriptionId: Int): RcsRegistrationState? {
        // Secondary signal: the provisioning manager's status for the
        // call-composer capability (an MmTel capability, so the non-RCS
        // provisioning pair is the correct query). The provisioning manager
        // is API 33+ and needs READ_PRECISE_PHONE_STATE; without either, this
        // gate degrades to "not required" — the carrier-config URL above is
        // the authoritative gate.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return null
        // READ_PRECISE_PHONE_STATE is declared in the manifest; it is only
        // granted to carrier-privileged builds, and the SecurityException
        // catch below degrades to "not required" everywhere else.
        val provisioningRequired = try {
            val ims = context.getSystemService(ImsManager::class.java)
                ?: return null
            val pm = ims.getProvisioningManager(subscriptionId)
            pm.isProvisioningRequiredForCapability(
                MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_CALL_COMPOSER,
                ImsRegistrationImplBase.REGISTRATION_TECH_LTE,
            ) && !pm.getProvisioningStatusForCapability(
                MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_CALL_COMPOSER,
                ImsRegistrationImplBase.REGISTRATION_TECH_LTE,
            )
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
        if (provisioningRequired) {
            Log.status(TAG, "probe: carrier provisioning required (call-composer cap unprovisioned)")
            return RcsRegistrationState.Unavailable(RcsUnavailableReason.ProvisioningRequired)
        }
        return null
    }

    /** Single-registration support gate; returns a terminal state, or null to continue. */
    private fun singleRegGate(context: Context, subscriptionId: Int): RcsRegistrationState? {
        // Tertiary signal, after TestRcsApp's ProvisioningActivity: the hidden
        // single-registration-capability query on the per-sub manager. It is
        // tri-state — a null result (bridge failure, say the role is not yet
        // granted) means unknown rather than not capable, so it never blocks
        // on permission errors.
        val singleRegCapable = runCatching {
            val pm = RcsHiddenApi.provisioningManager(subscriptionId)
                ?: return@runCatching null
            RcsHiddenApi.isSingleRegCapable(pm)
        }.getOrDefault(null)
        if (singleRegCapable == false) {
            // Framework verdict is negative: distinguish "device can't" from
            // "carrier won't". Carrier privilege decides which: without it
            // the carrier blocks every IMS path (delegate SecurityException,
            // direct-socket EPERM, no GBA). hasCarrierPrivileges is public API.
            val privileged = runCatching {
                val tm = context.getSystemService(TelephonyManager::class.java)
                val subTm = runCatching { tm?.createForSubscriptionId(subscriptionId) }
                    .getOrNull() ?: tm
                subTm?.hasCarrierPrivileges() == true
            }.getOrDefault(false)
            Log.status(TAG, "probe: single-reg verdict=false privileged=$privileged subId=$subscriptionId")
            return if (privileged) {
                RcsRegistrationState.Unavailable(RcsUnavailableReason.NotSupported)
            } else {
                RcsRegistrationState.Unavailable(RcsUnavailableReason.NoCarrierPrivilege)
            }
        }
        // Single-registration support gate: without it there is no delegate to create.
        // The feature constant is @SystemApi/hidden; the AOSP value is used directly.
        val singleReg = context.packageManager.hasSystemFeature(FEATURE_SINGLE_REG)
        Log.status(TAG, "probe: singleRegCapable=$singleRegCapable hasFeature=$singleReg")
        if (!singleReg) {
            return RcsRegistrationState.Unavailable(RcsUnavailableReason.NotSupported)
        }
        return null
    }

    /**
     * TS.43 entitlement check against the carrier config server (§7.1).
     * Real flow: GET `<url>?vers=<version>&terminal=<model>` with the
     * proper Accept header; a 200 whose body names an RCS config endpoint
     * means entitled. Best-effort: any failure maps to false, never throws.
     * The carrier URL is dynamic, so system trust is used.
     */
    suspend fun entitlementCheck(url: String): Boolean {
        if (!RcsFeature.enabled || url.isBlank()) return false
        return runCatching {
            val terminal = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
            val query = url + (if (url.contains("?")) "&" else "?") +
                "vers=1.0&terminal=${java.net.URLEncoder.encode(terminal, "UTF-8")}"
            val response = NetworkClient.performRequest(
                url = query,
                method = "GET",
                headers = mapOf("Accept" to "text/xml, application/xml, text/plain"),
                useSystemTrust = true,
            )
            if (!response.isSuccess) return@runCatching false
            // Entitled responses name the config server / token. Absent both
            // but HTTP-200 → treat as entitled (some carriers return empty).
            val body = response.body
            body.isBlank() || body.contains("config", ignoreCase = true) ||
                body.contains("token", ignoreCase = true) ||
                body.contains("rcs", ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun defaultSubscriptionId(): Int = runCatching {
        // TestRcsApp keys everything off the default SMS subscription.
        SubscriptionManager.getDefaultSmsSubscriptionId()
    }.getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)

    fun hasPhoneStatePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Device-level single-reg feature flag (framework capability, not carrier
     * verdict). True on your Pixel 8 even when the carrier profile reports
     * single-reg NOT capable — that combination routes to direct SIP.
     */
    fun deviceSupportsSingleReg(context: Context): Boolean =
        context.packageManager.hasSystemFeature(FEATURE_SINGLE_REG)

    // AOSP values from hidden surface (verified against frameworks/base main).
    // Call-composer is an MmTel capability (1 shl 4) and LTE the registration
    // tech, expressed with the public provisioning constants below.
    private const val FEATURE_SINGLE_REG = "android.hardware.telephony.ims.singlereg"
}
