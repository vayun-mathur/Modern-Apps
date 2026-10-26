package com.vayunmathur.shop.platform

import android.content.Context
import com.vayunmathur.library.log.Log
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
 * [NetworkClient.init] opens and X.509-parses the bundled DER roots — none of which may
 * happen on the main thread or a binder thread. Both the launcher entry point
 * ([com.vayunmathur.shop.MainActivity]) and any future intent entry points call [start],
 * which is idempotent; anything that needs the network awaits [awaitReady] from a
 * coroutine instead of blocking.
 */
object AppInit {

    private val started = AtomicBoolean(false)
    private val ready = CompletableDeferred<Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appCtx = context.applicationContext
        scope.launch {
            try {
                // Shopify endpoints (myshopify.com, catalog.shopify.com, cdn.shopify.com)
                // serve public-CA chains, which FIRST_PARTY (ISRG + GTS only) doesn't carry.
                // STANDARD adds the Amazon/DigiCert roots they need.
                NetworkClient.init(appCtx, TrustBundle.STANDARD)
            } catch (e: IllegalStateException) {
                Log.status("AppInit", "network warm-up failed", e)
            } catch (e: SecurityException) {
                Log.status("AppInit", "network warm-up failed", e)
            }
            ready.complete(Unit)
        }
    }

    suspend fun awaitReady() {
        ready.await()
    }
}
