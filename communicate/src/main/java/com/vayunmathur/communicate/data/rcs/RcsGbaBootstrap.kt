package com.vayunmathur.communicate.data.rcs

import androidx.core.net.toUri
import android.content.Context
import android.net.Uri
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telephony.gba.TlsParams
import android.telephony.gba.UaSecurityProtocolIdentifier
import android.telephony.`TelephonyManager$BootstrapAuthenticationCallback`
import com.vayunmathur.library.log.Log
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Live GBA bootstrapping (3GPP TS 33.220) for FT-over-HTTP digest auth.
 *
 * Mirrors TestRcsApp's `GbaAuthenticationProvider.provideCredentials`, rebuilt
 * without Guava/Apache-Http: carrier-config UA security params →
 * `UaSecurityProtocolIdentifier` → `TelephonyManager.
 * bootstrapAuthenticationRequest` → btId + key as the digest
 * username/password (base64 key, exactly like the reference
 * `GbaCredentials`).
 *
 * Reachability (verified against the SDK jar + AOSP main):
 * - `android.telephony.gba.*` is absent from `android.jar`, so same-FQN
 *   `compileOnly` stubs (`library:aosp-stubs`) provide the compile surface
 *   and the framework provides the runtime classes — the same mechanism as
 *   the existing `SipDelegateManager` path. No reflection on the data path.
 * - The request method itself lives on the SDK-visible `TelephonyManager`,
 *   so one reflective `getMethod` bridges it (identical precedent to
 *   `RcsHiddenApi.sipDelegateManager` — lint-clean).
 * - The callback is a hidden *class*: subclassing (not Proxy) via the
 *   `$`-named stub, with virtual dispatch at runtime.
 * - Permission is `PERFORM_IMS_SINGLE_REGISTRATION` (`internal|role`,
 *   SMS-role granted — declared in the manifest; TestRcsApp documents the
 *   same grant).
 *
 * Every failure (no privilege, hidden-API denial, modem refusal, timeout)
 * returns null and the uploader degrades to [RcsGbaAuth.injected] / plain
 * digest. Never throws.
 */
object RcsGbaBootstrap {
    private const val TAG = "RcsGbaBootstrap"

    /** Bound on the modem round trip (bootstrap can take seconds). */
    private const val TIMEOUT_MS = 30_000L

    // Carrier-config key strings (hidden constants; AOSP values verified).
    private const val KEY_ORG = "gba_ua_security_organization_int"
    private const val KEY_PROTOCOL = "gba_ua_security_protocol_int"
    private const val KEY_CIPHER_SUITE = "gba_ua_tls_cipher_suite_int"

    private val executor: Executor = Executors.newSingleThreadExecutor()

    /**
     * Bootstrap NAF credentials for [nafUrl] (the FT content-server URI).
     * [force] re-bootstraps even with cached keys (post-401 retry, mirroring
     * TestRcsApp's `GbaRequestExecutor`). Null on any failure.
     */
    suspend fun bootstrap(context: Context, nafUrl: String, force: Boolean): RcsGbaAuth.GbaCredentials? {
        if (!RcsFeature.enabled || nafUrl.isBlank()) return null
        return withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val done: (RcsGbaAuth.GbaCredentials?) -> Unit = { creds ->
                    if (cont.isActive) cont.resume(creds)
                }
                runCatching {
                    requestBootstrap(context.applicationContext, nafUrl, force, done)
                }.onFailure {
                    Log.status(TAG, "GBA bootstrap setup failed", it)
                    done(null)
                }
            }
        }
    }

    private fun requestBootstrap(
        context: Context,
        nafUrl: String,
        force: Boolean,
        done: (RcsGbaAuth.GbaCredentials?) -> Unit,
    ) {
        val subId = SubscriptionManager.getDefaultSmsSubscriptionId()
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            done(null)
            return
        }
        val tm = context.getSystemService(TelephonyManager::class.java)?.let { base ->
            runCatching { base.createForSubscriptionId(subId) }.getOrNull() ?: base
        } ?: run {
            done(null)
            return
        }
        val spId = buildSecurityProtocolId(context, subId) ?: run {
            done(null)
            return
        }
        val callback = object : `TelephonyManager$BootstrapAuthenticationCallback`() {
            override fun onKeysAvailable(gbaKey: ByteArray, transactionId: String) {
                if (gbaKey.isEmpty() || transactionId.isBlank()) {
                    done(null)
                } else {
                    done(RcsGbaAuth.GbaCredentials(btId = transactionId, key = gbaKey))
                }
            }

            override fun onAuthenticationFailure(reason: Int) {
                Log.status(TAG, "GBA authentication failure reason=$reason")
                done(null)
            }
        }
        // One reflective hop: the method is @SystemApi/hidden on the
        // SDK-visible TelephonyManager (same precedent as RcsHiddenApi).
        val method = tm.javaClass.getMethod(
            "bootstrapAuthenticationRequest",
            Int::class.javaPrimitiveType,
            Uri::class.java,
            UaSecurityProtocolIdentifier::class.java,
            Boolean::class.javaPrimitiveType,
            Executor::class.java,
            `TelephonyManager$BootstrapAuthenticationCallback`::class.java,
        )
        try {
            method.invoke(
                tm,
                TelephonyManager.APPTYPE_ISIM,
                nafUrl.toUri(),
                spId,
                force,
                executor,
                callback,
            )
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // Framework-side refusal (no privilege, modem error): degrade.
            Log.status(TAG, "GBA request refused", e.cause ?: e)
            done(null)
        }
    }

    /**
     * Build the UA security protocol id from carrier config (TestRcsApp
     * `GbaAuthenticationProvider` logic): org/protocol/cipher-suite ints,
     * defaulting the NULL suite to AES_128_CBC_SHA. Null when the config is
     * unreadable.
     */
    private fun buildSecurityProtocolId(context: Context, subId: Int): UaSecurityProtocolIdentifier? {
        return runCatching {
            val ccm = context.getSystemService(CarrierConfigManager::class.java)
                ?: return null
            // The keyed getter is API 34+; on 31-33 read the whole bundle.
            val bundle = try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ccm.getConfigForSubId(subId, KEY_ORG, KEY_PROTOCOL, KEY_CIPHER_SUITE)
                } else {
                    @Suppress("DEPRECATION")
                    ccm.getConfigForSubId(subId)
                }
            } catch (_: SecurityException) {
                return null
            } catch (_: Exception) {
                return null
            } ?: return null
            val org = bundle.getInt(KEY_ORG, UaSecurityProtocolIdentifier.ORG_NONE)
            val protocol = bundle.getInt(
                KEY_PROTOCOL,
                UaSecurityProtocolIdentifier.UA_SECURITY_PROTOCOL_3GPP_HTTP_DIGEST_AUTHENTICATION,
            )
            var suite = bundle.getInt(KEY_CIPHER_SUITE, TlsParams.TLS_NULL_WITH_NULL_NULL)
            if (suite == TlsParams.TLS_NULL_WITH_NULL_NULL) {
                suite = TlsParams.TLS_RSA_WITH_AES_128_CBC_SHA
            }
            UaSecurityProtocolIdentifier.Builder()
                .setOrg(org)
                .setProtocol(protocol)
                .setTlsCipherSuite(suite)
                .build()
        }.getOrElse {
            Log.status(TAG, "GBA security-protocol build failed", it)
            null
        }
    }
}
