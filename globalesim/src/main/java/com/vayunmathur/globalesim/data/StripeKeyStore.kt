package com.vayunmathur.globalesim.data

import android.content.Context

/**
 * Cached Stripe publishable key (`pk_...`, public by design). Served by
 * `GET /esim/v1/stripe-key` so no key is baked into the client; this cache
 * only avoids refetching it. Synchronous SharedPreferences (not DataStore)
 * so `MainActivity.onCreate` can read it before creating the Stripe
 * launchers — their ActivityResult registration is illegal after STARTED,
 * and `GooglePayLauncher` reads `PaymentConfiguration` at construction.
 */
class StripeKeyStore(private val context: Context) {
    private fun prefs() = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Cached key, or null on first install / if never fetched. */
    fun getCached(): String? = prefs().getString(KEY, null)?.takeIf { it.isNotBlank() }

    fun save(key: String) {
        prefs().edit().putString(KEY, key).apply()
    }

    companion object {
        private const val NAME = "stripe_key"
        private const val KEY = "publishable_key"
    }
}
