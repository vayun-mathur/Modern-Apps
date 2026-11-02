package com.vayunmathur.globalesim.platform

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stripe.android.PaymentConfiguration
import com.vayunmathur.globalesim.data.AnonymousIdStore
import com.vayunmathur.globalesim.data.StripeKeyStore
import com.vayunmathur.globalesim.data.api.EsimApi
import com.vayunmathur.globalesim.data.api.ProfileDto
import com.vayunmathur.globalesim.data.api.RateDto
import com.vayunmathur.globalesim.data.api.StatusResponse
import com.vayunmathur.globalesim.domain.upgradeCheckoutUrl
import java.io.IOException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** UI state for the single-eSIM storefront. */
data class EsimUiState(
    val loading: Boolean = false,
    val statusLoaded: Boolean = false,
    val refreshing: Boolean = false,
    /** This install's account id, shown to the user as the "SIM ID". */
    val simId: String? = null,
    val profile: ProfileDto? = null,
    val rates: List<RateDto> = emptyList(),
    val pendingClientSecret: String? = null,
    /** Pending crypto checkout; cleared once the credit lands or the user cancels. */
    val pendingInvoiceUrl: String? = null,
    /** Paid but not yet in the wallet (eSIM being created, or funding retrying). */
    val pendingCreditCents: Long = 0,
    /** The balance shown is last-known; the server couldn't read it live. */
    val balanceStale: Boolean = false,
    /** One-shot snackbar text, consumed by the tab host. */
    val message: String? = null,
)

class EsimViewModel(app: Application) : AndroidViewModel(app) {
    private val idStore = AnonymousIdStore(app.applicationContext)
    private val keyStore = StripeKeyStore(app.applicationContext)

    var state by mutableStateOf(EsimUiState())
        private set

    private var creditWatch: Job? = null
    private var creditBaselineCents: Long = 0
    private var watchingCrypto = false

    init {
        viewModelScope.launch {
            // Mint the anonymous id only. The server creates the eSIM itself
            // once the account's first payment lands.
            state = state.copy(simId = idStore.getOrCreate())
            prefetchStripeKey()
            refreshRates()
            refreshStatus()
        }
    }

    /**
     * Fetch + cache the publishable key at startup (silent on failure — the
     * purchase flow fetches it on demand anyway). Warms the cache
     * [StripeKeyStore] so the next cold start can create the Google Pay
     * launcher in onCreate.
     */
    private suspend fun prefetchStripeKey() {
        runCatching { EsimApi.stripeKey().publishableKey }
            .onSuccess { key ->
                if (key.isNotBlank()) {
                    keyStore.save(key)
                    PaymentConfiguration.init(getApplication(), key)
                }
            }
    }

    private suspend fun accountId(): String = idStore.getOrCreate()

    fun dismissMessage() {
        state = state.copy(message = null)
    }

    /** One-shot message from non-composable callers (e.g. payment launcher callbacks). */
    fun notifyMessage(text: String) {
        state = state.copy(message = text)
    }

    fun refreshStatus() {
        viewModelScope.launch {
            state = state.copy(loading = true)
            loadStatus()
            state = state.copy(loading = false)
        }
    }

    /** Pull-to-refresh: reload status + balance without the full-card spinner. */
    fun refresh() {
        viewModelScope.launch {
            state = state.copy(refreshing = true)
            loadStatus()
            state = state.copy(refreshing = false)
        }
    }

    private suspend fun loadStatus() {
        runCatching { EsimApi.status(accountId()) }
            .onSuccess { if (applyStatus(it)) creditWatch?.cancel() }
            .onFailure { state = state.copy(message = friendly(it)) }
        state = state.copy(statusLoaded = true)
    }

    /**
     * Apply a status read. The server owns the open crypto checkout and any
     * credit still on its way, so its view wins: this restores Reopen/Cancel
     * (and the watcher) after a restart. Returns true when an in-flight
     * payment watch has nothing left to wait for.
     */
    private suspend fun applyStatus(status: StatusResponse): Boolean {
        val profile = status.profile
        val landed = profile != null && !status.balanceStale &&
            profile.walletUsdCents > creditBaselineCents
        val serverPending = status.pendingInvoiceUrl?.let(::upgradeCheckoutUrl)
        state = state.copy(
            profile = profile,
            pendingInvoiceUrl = serverPending,
            pendingCreditCents = status.pendingCreditCents,
            balanceStale = status.balanceStale,
        )
        val stillOwed = status.pendingCreditCents > 0
        if ((serverPending != null || stillOwed) && creditWatch?.isActive != true) {
            captureCreditBaseline()
            watchForCredit(crypto = serverPending != null)
        }
        return when {
            stillOwed -> false
            landed -> true
            // Checkout closed with nothing credited: failed, expired, or too small to count.
            watchingCrypto && serverPending == null -> true
            else -> false
        }
    }

    /**
     * Poll status until the payment shows up in the wallet. Both rails fund
     * the wallet from a server webhook, and NOWPayments can report `finished`
     * before that webhook lands, so the balance is the only reliable "paid"
     * signal. Lives in viewModelScope so polling continues while the user is
     * in the browser and the balance is current on return.
     */
    private fun watchForCredit(crypto: Boolean) {
        creditWatch?.cancel()
        watchingCrypto = crypto
        creditWatch = viewModelScope.launch {
            try {
                val started = TimeSource.Monotonic.markNow()
                while (true) {
                    if (started.elapsedNow() >= CREDIT_WATCH_TIMEOUT) {
                        state = state.copy(message = "Payment not detected yet. Pull down to check again later.")
                        break
                    }
                    delay(if (started.elapsedNow() < FAST_POLL_WINDOW) FAST_POLL else SLOW_POLL)
                    val status = runCatching { EsimApi.status(accountId()) }.getOrNull() ?: continue
                    // Data usage can drain the wallet mid-watch; track the low so a
                    // top-up still reads as a rise. Stale reads say nothing.
                    status.profile?.takeIf { !status.balanceStale }?.let {
                        creditBaselineCents = minOf(creditBaselineCents, it.walletUsdCents)
                    }
                    if (applyStatus(status)) break
                }
            } finally {
                // A replacement watch may already own the flag.
                if (creditWatch === coroutineContext[Job]) watchingCrypto = false
            }
        }
    }

    private fun captureCreditBaseline() {
        creditBaselineCents = state.profile?.walletUsdCents ?: 0
    }

    fun refreshRates() {
        viewModelScope.launch {
            runCatching { EsimApi.rates() }
                .onSuccess { state = state.copy(rates = it.rates) }
                .onFailure { state = state.copy(message = friendly(it)) }
        }
    }

    /**
     * Start a card purchase: fetch the publishable key (once per process)
     * and a PaymentIntent for [amountCents]. The screen presents the
     * PaymentSheet with the resulting client secret; the server credits
     * the wallet via the Stripe webhook.
     */
    fun startCardPurchase(amountCents: Long, publishableKey: String?, onKey: (String) -> Unit) {
        viewModelScope.launch {
            state = state.copy(loading = true, pendingClientSecret = null)
            captureCreditBaseline()
            runCatching {
                val key = publishableKey ?: EsimApi.stripeKey().publishableKey
                key to EsimApi.stripeIntent(accountId(), amountCents).clientSecret
            }
                .onSuccess { (key, secret) ->
                    onKey(key)
                    state = state.copy(loading = false, pendingClientSecret = secret)
                }
                .onFailure { state = state.copy(loading = false, message = paymentError(it)) }
        }
    }

    /** Clear the pending sheet secret; on success, watch for the webhook credit. */
    fun finishCardPurchase(paid: Boolean) {
        state = state.copy(pendingClientSecret = null, loading = false)
        if (paid) watchForCredit(crypto = false)
    }

    /** Launcher failure: surface the error, then clear exactly like a cancel. */
    fun finishCardPurchaseWithError(error: String) {
        state = state.copy(pendingClientSecret = null, loading = false, message = error)
    }

    /**
     * Start a crypto purchase: create a NOWPayments invoice for [amountCents]
     * face value. The screen opens the hosted checkout URL; the server
     * credits the wallet + 5% bonus via the IPN webhook. The server allows
     * one open checkout per account and hands back that one if it exists.
     */
    fun startCryptoPurchase(amountCents: Long, onInvoiceReady: (String) -> Unit) {
        viewModelScope.launch {
            state = state.copy(loading = true)
            captureCreditBaseline()
            runCatching { EsimApi.nowPayInvoice(accountId(), amountCents) }
                .onSuccess {
                    // NOWPayments sometimes returns a cleartext http link,
                    // which cleartext blocking rejects — upgrade once here so
                    // every consumer (state, open callback) is secure.
                    val secureUrl = upgradeCheckoutUrl(it.invoiceUrl)
                    state = state.copy(
                        loading = false,
                        pendingInvoiceUrl = secureUrl,
                        message = if (it.existing) "Reopened your open crypto checkout." else null,
                    )
                    watchForCredit(crypto = true)
                    onInvoiceReady(secureUrl)
                }
                .onFailure {
                    val message = if (it is EsimApi.ApiError && it.statusCode == 409) {
                        "A crypto payment is already starting."
                    } else {
                        paymentError(it)
                    }
                    state = state.copy(loading = false, message = message)
                }
        }
    }

    /** Cancel the open crypto checkout here and on the server, freeing the slot. */
    fun clearPendingInvoice() {
        creditWatch?.cancel()
        state = state.copy(pendingInvoiceUrl = null, loading = false)
        viewModelScope.launch {
            runCatching { EsimApi.nowPayCancel(accountId()) }
                .onFailure { state = state.copy(message = friendly(it)) }
        }
    }

    /** Adopt the SIM ID copied from another phone as this phone's account id. */
    fun claim(simId: String) {
        val id = simId.trim()
        viewModelScope.launch {
            state = state.copy(loading = true)
            runCatching { EsimApi.claim(id, accountId()) }
                .onSuccess {
                    creditWatch?.cancel()
                    idStore.replace(id)
                    state = state.copy(
                        simId = id,
                        loading = false,
                        pendingInvoiceUrl = null,
                        pendingClientSecret = null,
                        message = "eSIM claimed on this phone.",
                    )
                    loadStatus()
                }
                .onFailure {
                    val message = when ((it as? EsimApi.ApiError)?.statusCode) {
                        400 -> "That doesn't look like a SIM ID."
                        404 -> "No eSIM found for that SIM ID."
                        409 -> "This phone already has an eSIM."
                        else -> friendly(it)
                    }
                    state = state.copy(loading = false, message = message)
                }
        }
    }

    private fun paymentError(t: Throwable): String = when ((t as? EsimApi.ApiError)?.statusCode) {
        400 -> "Payments must be between $5 and $20."
        else -> friendly(t)
    }

    private fun friendly(t: Throwable): String = when (val code = (t as? EsimApi.ApiError)?.statusCode) {
        null -> if (t is IOException) "No connection. Check your internet and try again." else "Something went wrong."
        429 -> "Too many requests. Try again in a minute."
        in 500..599 -> "Service unavailable. Try again shortly."
        else -> "Request failed ($code)."
    }

    private companion object {
        val FAST_POLL = 2.seconds
        val SLOW_POLL = 10.seconds
        val FAST_POLL_WINDOW = 2.minutes
        // Crypto settlement waits on block confirmations.
        val CREDIT_WATCH_TIMEOUT = 45.minutes
    }
}
