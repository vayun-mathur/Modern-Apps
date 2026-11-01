package com.vayunmathur.fooddelivery.platform

import android.content.Context
import com.vayunmathur.fooddelivery.data.RemoteImageFetchers
import com.vayunmathur.library.image.ImageLoader
import com.vayunmathur.library.log.Log
import androidx.core.content.edit
import com.vayunmathur.fooddelivery.api.BitesAuth
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
 * SharedPreferences and JSON-decoded — none of which may happen on the main thread or a
 * binder thread. Both process entry points ([com.vayunmathur.fooddelivery.FoodDeliveryApplication]
 * and [com.vayunmathur.fooddelivery.intents.OrderLookupIntent], which may launch into a cold
 * process) call [start], which is idempotent; anything that needs the token or the pinned trust
 * anchors awaits [awaitReady] from a coroutine instead of blocking.
 */
object AppInit {

    private const val PREFS_NAME = "fooddelivery_prefs"
    private const val KEY_TOKEN = "token_json"

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
            ImageLoader.Builder(appCtx).remote(RemoteImageFetchers::network).build(),
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
                    prefs.edit { putString(KEY_TOKEN, tokenJson) }
                }
                BitesAuth.onTokenCleared = { prefs.edit { remove(KEY_TOKEN) } }
                prefs.getString(KEY_TOKEN, null)?.let { BitesAuth.restoreToken(it) }
            } catch (e: SecurityException) {
                Log.status("AppInit", "token warm-up failed", e)
            } catch (e: IllegalArgumentException) {
                Log.status("AppInit", "token warm-up failed", e)
            }
            ready.complete(Unit)
        }
    }

    suspend fun awaitReady() {
        ready.await()
    }
}
