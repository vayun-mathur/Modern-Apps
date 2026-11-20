package com.vayunmathur.fooddelivery.platform

import android.content.Context
import android.content.SharedPreferences
import com.vayunmathur.library.image.fetchers.HttpImageFetcher
import com.vayunmathur.library.image.ImageLoader
import com.vayunmathur.library.log.Log
import androidx.core.content.edit
import com.vayunmathur.fooddelivery.network.BitesAuth
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide warm-up, run off the main thread.
 *
 * [NetworkClient.init] opens and X.509-parses the bundled DER roots, fills a KeyStore and
 * initialises an SSLContext, and the saved auth token has to be read out of
 * SharedPreferences, Keystore-unsealed and JSON-decoded — none of which may happen on the main thread or a
 * binder thread. Both process entry points ([com.vayunmathur.fooddelivery.FoodDeliveryApplication]
 * and [com.vayunmathur.fooddelivery.intents.OrderLookupIntent], which may launch into a cold
 * process) call [start], which is idempotent; anything that needs the token or the pinned trust
 * anchors awaits [awaitReady] from a coroutine instead of blocking.
 *
 * The persisted token is sealed inside the callbacks installed here (the only writers of
 * `token_json` besides sign-out removal): [BitesAuth.onTokenUpdated] stores only
 * Keystore-sealed ciphertext, and the restore below unseals-or-migrates — a legacy
 * plaintext value is verified as JSON, restored, then rewritten sealed; a sealed value
 * that no longer decrypts (restored on another device, lost key) starts signed-out.
 * Cold-process services rely on this warm-up, so the migration lives here, not in an
 * Activity.
 */
object AppInit {

    private const val PREFS_NAME = "fooddelivery_prefs"
    private const val KEY_TOKEN = "token_json"
    private const val KEY_ALIAS = "fooddelivery_token_key"

    /** Test seam: Robolectric has no AndroidKeyStore; tests install a fake sealer here. */
    internal var sealerForTest: TokenSealer? = null

    private val defaultSealer by lazy { KeystoreTokenSealer(KEY_ALIAS) }

    private fun activeSealer(): TokenSealer = sealerForTest ?: defaultSealer

    private val started = AtomicBoolean(false)
    private val ready = CompletableDeferred<Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appCtx = context.applicationContext
        // Merchant photos load over HTTP(S) through the shared image pipeline;
        // the fetch function is built here from NetworkClient so no
        // library-to-library edge is needed (#884).
        ImageLoader.setDefault(
            ImageLoader.Builder(appCtx).remote { data, _ -> HttpImageFetcher.fetch(data, NetworkClient::getBytes) }.build(),
        )
        scope.launch {
            // Kept in separate try blocks: a trust-bundle failure must not also skip the token
            // restore, or one bad DER root would silently sign the user out and — because
            // onTokenUpdated would never be installed — stop the next login from persisting.
            try {
                // api.deliverycollective.com is on AWS Elastic Beanstalk and serves an ACM cert
                // chaining to Amazon Root CA 1, which FIRST_PARTY (ISRG + GTS only) doesn't carry —
                // pinning to it fails the handshake before any request goes out. STANDARD adds the
                // Amazon roots.
                NetworkClient.init(appCtx, TrustBundle.STANDARD)
            } catch (e: IllegalStateException) {
                Log.status("AppInit", "network warm-up failed", e)
            } catch (e: SecurityException) {
                Log.status("AppInit", "network warm-up failed", e)
            }
            try {
                val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                // The application context, not an Activity: BitesAuth outlives every Activity.
                BitesAuth.onTokenUpdated = { tokenJson ->
                    // Only sealed ciphertext is persisted; on seal failure keep the
                    // previous value rather than writing the token in plaintext.
                    val sealed = activeSealer().seal(tokenJson)
                    if (sealed == null) {
                        Log.status("AppInit", "token sealing failed; token not persisted")
                    } else {
                        prefs.edit { putString(KEY_TOKEN, sealed) }
                    }
                }
                BitesAuth.onTokenCleared = { prefs.edit { remove(KEY_TOKEN) } }
                prefs.getString(KEY_TOKEN, null)?.let { stored -> restorePersistedToken(prefs, stored) }
            } catch (e: SecurityException) {
                Log.status("AppInit", "token warm-up failed", e)
            } catch (e: IllegalArgumentException) {
                Log.status("AppInit", "token warm-up failed", e)
            }
            ready.complete(Unit)
        }
    }

    /**
     * Restores the persisted token, migrating legacy plaintext in place: a non-sealed
     * value is verified as JSON first (a corrupt value is left untouched and never
     * sealed over), restored, then rewritten sealed only if the stored value has not
     * changed since the read. A sealed value that cannot be unsealed (restored on
     * another device, lost Keystore key) starts signed-out — never a crash.
     */
    private fun restorePersistedToken(prefs: SharedPreferences, stored: String) {
        val sealer = activeSealer()
        if (!sealer.isSealed(stored)) {
            try {
                kotlinx.serialization.json.Json.parseToJsonElement(stored)
            } catch (e: IllegalArgumentException) {
                Log.status("AppInit", "stored token is not valid JSON; not restoring", e)
                return
            }
            BitesAuth.restoreToken(stored)
            val sealed = sealer.seal(stored) ?: return
            if (prefs.getString(KEY_TOKEN, null) == stored) {
                prefs.edit { putString(KEY_TOKEN, sealed) }
            }
            return
        }
        val plain = sealer.unseal(stored)
        if (plain == null) {
            Log.status("AppInit", "stored token could not be unsealed; starting signed out")
            return
        }
        BitesAuth.restoreToken(plain)
    }

    suspend fun awaitReady() {
        ready.await()
    }
}
