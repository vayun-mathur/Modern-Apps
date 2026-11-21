package com.vayunmathur.shop.domain

import com.vayunmathur.shop.data.CartLine
import com.vayunmathur.shop.data.CheckoutInputs
import com.vayunmathur.shop.data.InFlightCheckout
import com.vayunmathur.shop.data.CheckoutPhase
import com.vayunmathur.shop.data.NativeCheckoutState
import com.vayunmathur.shop.data.PlacedOrder
import com.vayunmathur.shop.data.UcpCheckout
import com.vayunmathur.shop.data.SubmittedLine
import com.vayunmathur.shop.data.UcpError
import com.vayunmathur.shop.network.ShopBackend
import com.vayunmathur.shop.network.ShopBackendException
import com.vayunmathur.shop.network.ShopHttpBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import java.time.Instant
import java.util.UUID

/** Deliberately has no completion operation. Tests never need a merchant or payment credentials. */
internal interface NativeCheckoutGateway {
    suspend fun create(
        shop: String,
        lines: List<CartLine>,
        inputs: CheckoutInputs,
        intent: String,
    ): Result<UcpCheckout>
    suspend fun update(
        shop: String,
        sessionId: String,
        lines: List<CartLine>,
        inputs: CheckoutInputs,
        intent: String,
    ): Result<UcpCheckout>
    suspend fun get(shop: String, sessionId: String): Result<UcpCheckout>
}

internal class ServerCheckoutGateway(
    private val backend: ShopBackend = ShopHttpBackend,
) : NativeCheckoutGateway {
    override suspend fun create(
        shop: String,
        lines: List<CartLine>,
        inputs: CheckoutInputs,
        intent: String,
    ): Result<UcpCheckout> =
        backend.createCheckout(shop, lines, inputs, intent)
    override suspend fun update(
        shop: String,
        sessionId: String,
        lines: List<CartLine>,
        inputs: CheckoutInputs,
        intent: String,
    ): Result<UcpCheckout> =
        backend.updateCheckout(shop, sessionId, lines, inputs, intent)
    override suspend fun get(shop: String, sessionId: String): Result<UcpCheckout> =
        backend.getCheckout(shop, sessionId)
}

internal fun interface PendingCheckoutSink { suspend fun save(order: PlacedOrder) }

/** Single-owner ephemeral state; mutex guards each merchant against overlapping writes and reads. */
private const val MAX_QUANTITY = 99

internal class NativeCheckoutCoordinator(
    private val gateway: NativeCheckoutGateway = ServerCheckoutGateway(),
    private val now: () -> Long = System::currentTimeMillis,
    private val newIntent: () -> String = { UUID.randomUUID().toString() },
    private val changed: (List<NativeCheckoutState>) -> Unit = {},
    /** Awaited with the in-flight record BEFORE every write, and again once it resolves. */
    private val intentSink: suspend (InFlightCheckout) -> Unit = {},
    /** Called when a write is definitively rejected, so no in-flight record remains. */
    private val intentDone: suspend (platformId: String, shopDomain: String) -> Unit = { _, _ -> },
) {
    private val states = linkedMapOf<Pair<String, String>, NativeCheckoutState>()
    private val locks = mutableMapOf<Pair<String, String>, Mutex>()
    private val createdAt = mutableMapOf<Pair<String, String>, Long>()
    val snapshot: List<NativeCheckoutState> get() = states.values.toList()

    private fun publish(key: Pair<String, String>, state: NativeCheckoutState) {
        states[key] = state
        changed(snapshot)
    }

    fun edit(platform: String, shop: String, inputs: CheckoutInputs) {
        val key = platform to shop
        val state = states[key] ?: NativeCheckoutState(platform, shop)
        if (!state.busy) publish(key, state.copy(inputs = inputs))
    }

    fun cartChanged(lines: List<CartLine>) {
        states.toMap().forEach { (key, state) ->
            val cartForKey = lines.filter { it.platformId == key.first && it.shopDomain == key.second }
            if (state.checkout != null && state.submittedLines != cartForKey) {
                publish(
                    key,
                    state.copy(
                        phase = if (state.phase == CheckoutPhase.Uncertain) {
                            CheckoutPhase.Uncertain
                        } else {
                            CheckoutPhase.BrowserRequired
                        },
                        message = "The cart changed. This session no longer represents your cart; " +
                            "review it on the merchant website.",
                    ),
                )
            }
        }
    }

    fun forget(platform: String, shop: String) {
        val key = platform to shop
        if (states.remove(key) != null) changed(snapshot)
        createdAt.remove(key)
    }

    fun refreshExpiry() {
        states.toMap().forEach { (key, state) ->
            val checkout = state.checkout
            val expirable = !state.busy && state.phase != CheckoutPhase.Uncertain
            if (checkout != null && expirable && expired(key, checkout)) {
                publish(
                    key,
                    state.copy(
                        phase = CheckoutPhase.Expired,
                        message = "This checkout session expired. Continue on the merchant website.",
                    ),
                )
            }
        }
    }

    private fun expired(key: Pair<String, String>, checkout: UcpCheckout): Boolean = checkout.expiresAt?.let {
        runCatching { Instant.parse(it).toEpochMilli() <= now() }.getOrDefault(true)
    } ?: (now() - (createdAt[key] ?: now()) !in 0 until 6 * 60 * 60 * 1000L)


    /** True when [submitted] lines match [lines] by variant and quantity. */
    private fun cartMatches(
        submitted: List<SubmittedLine>,
        lines: List<CartLine>,
    ): Boolean = submitted.size == lines.size &&
        submitted.all { sub ->
            lines.any { it.variantId == sub.variantId && it.quantity == sub.quantity }
        }

    /** Guards a submit; publishes the refusal reason and returns true when blocked. */
    private fun submitBlocked(
        key: Pair<String, String>,
        state: NativeCheckoutState,
        platform: String,
        shop: String,
        lines: List<CartLine>,
    ): Boolean {
        if (state.phase in setOf(CheckoutPhase.Uncertain, CheckoutPhase.BrowserRequired, CheckoutPhase.Expired)) {
            return true
        }
        if (lines.isEmpty() || lines.any { it.shopDomain != shop || it.platformId != platform }) return true
        if (lines.any { it.quantity !in 1..MAX_QUANTITY }) return true
        state.inputs.validationError()?.let {
            publish(key, state.copy(phase = CheckoutPhase.Error, message = it))
            return true
        }
        return previousBlocked(key, state, lines)
    }

    /** Previous-session guards for a submit; publishes the refusal and returns true when blocked. */
    private fun previousBlocked(
        key: Pair<String, String>,
        state: NativeCheckoutState,
        lines: List<CartLine>,
    ): Boolean {
        val previous = state.checkout
        if (previous != null && expired(key, previous)) {
            publish(
                key,
                state.copy(
                    phase = CheckoutPhase.Expired,
                    message = "This session expired. Continue on the merchant website.",
                ),
            )
            return true
        }
        if (previous != null && state.submittedLines != lines) {
            publish(
                key,
                state.copy(
                    phase = CheckoutPhase.BrowserRequired,
                    message = "The session's cart changed. Continue on the merchant website.",
                ),
            )
            return true
        }
        if (previous != null && state.submittedInputs == state.inputs) return true
        if (previous != null && state.submittedInputs?.hasAddress == true && !state.inputs.hasAddress) {
            publish(
                key,
                state.copy(
                    phase = CheckoutPhase.BrowserRequired,
                    message = "Removing a submitted shipping address requires the merchant website.",
                ),
            )
            return true
        }
        return false
    }

    /** The single attempted write for a submit; publishes the refusal and returns null on failure. */
    private suspend fun executeWrite(
        key: Pair<String, String>,
        state: NativeCheckoutState,
        previous: UcpCheckout?,
        shop: String,
        lines: List<CartLine>,
    ): Pair<UcpCheckout, String>? {
        // The server builds the exact dated projection and owns the idempotency
        // ledger: a write is attempted exactly once and never auto-retried.
        // The first write carries the whole profile (name, contact and address); only
        // shipping-option choices wait until the merchant has offered them.
        val sentInputs = if (previous == null) state.inputs.copy(options = emptyMap()) else state.inputs
        val intent = newIntent()
        // Persist the intent BEFORE the write goes out: process death from here on
        // leaves a record the next launch reconciles instead of re-creating.
        intentSink(
            InFlightCheckout(
                intent = intent,
                platformId = key.first,
                shopDomain = key.second,
                storeName = lines.firstNotNullOfOrNull { it.storeName.ifBlank { null } }.orEmpty(),
                lines = lines.map { it.copy(imageUrl = "") },
                checkoutId = previous?.checkoutId,
                trackingToken = previous?.trackingToken.orEmpty(),
                status = InFlightCheckout.STATUS_INFLIGHT,
                createdAtMs = now(),
            ),
        )
        val checkout = (if (previous == null) {
            gateway.create(shop, lines.toList(), sentInputs, intent)
        } else {
            gateway.update(shop, previous.checkoutId, lines.toList(), sentInputs, intent)
        }).getOrElse {
            val unsupported = (it as? ShopBackendException)?.error as? UcpError.Unsupported
            if (unsupported != null) {
                // A definitive rejection created nothing: no in-flight record remains.
                intentDone(key.first, key.second)
            } else {
                intentSink(
                    InFlightCheckout(
                        intent = intent,
                        platformId = key.first,
                        shopDomain = key.second,
                        storeName = lines.firstNotNullOfOrNull { it.storeName.ifBlank { null } }.orEmpty(),
                        lines = lines.map { it.copy(imageUrl = "") },
                        checkoutId = previous?.checkoutId,
                        trackingToken = previous?.trackingToken.orEmpty(),
                        status = InFlightCheckout.STATUS_UNCERTAIN,
                        createdAtMs = now(),
                    ),
                )
            }
            publish(
                key,
                state.copy(
                    phase = if (unsupported != null) CheckoutPhase.BrowserRequired else CheckoutPhase.Uncertain,
                    handoffUrl = unsupported?.continueUrl?.let(MerchantUrls::httpsUrl),
                    message = unsupported?.reason
                        ?: ("The request may have reached the merchant. " +
                            "Do not retry; check the session or use its website."),
                ),
            )
            return null
        }
        if (previous != null && checkout.checkoutId != previous.checkoutId) {
            publish(
                key,
                state.copy(
                    phase = CheckoutPhase.Uncertain,
                    message = "The merchant returned a different session. Do not retry.",
                ),
            )
            return null
        }
        createdAt.putIfAbsent(key, now())
        return checkout to intent
    }

    /** Publishes the post-write state and persists the pending-order projection. */
    private suspend fun publishResult(
        key: Pair<String, String>,
        state: NativeCheckoutState,
        previous: UcpCheckout?,
        checkout: UcpCheckout,
        lines: List<CartLine>,
        sink: PendingCheckoutSink,
        intent: String = "",
    ) {
        val sentInputs = if (previous == null) state.inputs.copy(options = emptyMap()) else state.inputs
        val responseMatchesCart = cartMatches(checkout.submittedLines, lines)
        val reason = checkout.review.browserOnlyReason ?: if (!responseMatchesCart) {
            "The merchant returned changed or unsupported line items. Review them on its website."
        } else {
            null
        }
        val next = state.copy(
            checkout = checkout,
            submittedInputs = sentInputs,
            submittedLines = lines.toList(),
            phase = if (expired(key, checkout)) {
                CheckoutPhase.Expired
            } else if (reason != null) {
                CheckoutPhase.BrowserRequired
            } else {
                CheckoutPhase.Reviewing
            },
            message = reason,
        )
        publish(key, next)
        // Persist only the existing pending history projection, never buyer, address, messages or raw response.
        sink.save(
            PlacedOrder(
                checkoutId = checkout.checkoutId,
                placedAtMs = now(),
                total = checkout.totals.total,
                itemCount = lines.sumOf { it.quantity },
                continueUrl = MerchantUrls.httpsUrl(checkout.continueUrl).orEmpty(),
                status = "pending",
                platformId = key.first,
                shopDomain = key.second,
                storeName = lines.firstNotNullOfOrNull { it.storeName.ifBlank { null } }.orEmpty(),
                lines = lines.map { it.copy(imageUrl = "") },
                trackingToken = checkout.trackingToken,
                totalEntries = checkout.totalEntries.map { it.copy(label = it.type) },
                expiresAt = checkout.expiresAt,
                version = checkout.version,
            ),
        )
        // The write resolved: the in-flight record now names the session, so a
        // death inside the embedded checkout reconciles via getCheckout instead
        // of creating a second session.
        if (intent.isNotBlank()) {
            intentSink(
                InFlightCheckout(
                    intent = intent,
                    platformId = key.first,
                    shopDomain = key.second,
                    storeName = lines.firstNotNullOfOrNull { it.storeName.ifBlank { null } }.orEmpty(),
                    lines = lines.map { it.copy(imageUrl = "") },
                    checkoutId = checkout.checkoutId,
                    trackingToken = checkout.trackingToken,
                    status = InFlightCheckout.STATUS_PENDING,
                    createdAtMs = createdAt[key] ?: now(),
                ),
            )
        }
    }
    suspend fun submit(platform: String, shop: String, lines: List<CartLine>, sink: PendingCheckoutSink) {
        val key = platform to shop
        val lock = locks.getOrPut(key) { Mutex() }
        if (!lock.tryLock()) return
        var mutationStarted = false
        try {
            val state = states[key] ?: NativeCheckoutState(platform, shop)
            if (submitBlocked(key, state, platform, shop, lines)) return
            val previous = state.checkout
            publish(
                key,
                state.copy(
                    phase = if (previous == null) CheckoutPhase.Preparing else CheckoutPhase.Updating,
                    message = null,
                ),
            )
            mutationStarted = true
            val (checkout, intent) = executeWrite(key, state, previous, shop, lines) ?: return
            publishResult(key, state, previous, checkout, lines, sink, intent)
        } catch (cancelled: CancellationException) {
            if (mutationStarted) {
                states[key]?.let {
                    publish(
                        key,
                        it.copy(
                            phase = CheckoutPhase.Uncertain,
                            message = "Request interrupted. Do not retry; use the merchant website.",
                        ),
                    )
                }
            }
            throw cancelled
        } catch (_: Exception) {
            states[key]?.let {
                publish(
                    key,
                    it.copy(
                        phase = if (mutationStarted) CheckoutPhase.Uncertain else CheckoutPhase.Error,
                        message = if (mutationStarted) {
                            "The session may have changed. Do not retry; use the merchant website."
                        } else {
                            "Checkout is unavailable."
                        },
                    ),
                )
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * Seeds state from a persisted in-flight record after process death. A record
     * with a session id can be refreshed via [reconcile]'s GET; one without (death
     * during the create, unknown outcome) restores as Uncertain so it can never be
     * replayed — there is no query-by-intent to prove what the server did.
     */
    fun restore(record: InFlightCheckout) {
        val key = record.platformId to record.shopDomain
        if (states[key]?.busy == true) return
        val checkout = record.checkoutId?.takeIf { it.isNotBlank() }?.let {
            UcpCheckout(checkoutId = it, trackingToken = record.trackingToken)
        }
        if (checkout != null) createdAt.putIfAbsent(key, record.createdAtMs)
        publish(
            key,
            NativeCheckoutState(
                platformId = record.platformId,
                shopDomain = record.shopDomain,
                phase = if (checkout != null && record.status == InFlightCheckout.STATUS_PENDING) {
                    CheckoutPhase.Reviewing
                } else {
                    CheckoutPhase.Uncertain
                },
                checkout = checkout,
                message = if (checkout == null) {
                    "A checkout may have been started for this store. " +
                        "Do not retry; check your orders or the merchant website."
                } else {
                    null
                },
                submittedLines = record.lines,
            ),
        )
    }

    suspend fun reconcile(platform: String, shop: String) {
        val key = platform to shop
        val lock = locks.getOrPut(key) { Mutex() }
        if (!lock.tryLock()) return
        try {
            val state = states[key] ?: return
            val checkout = state.checkout ?: return
            val refreshed = gateway.get(shop, checkout.checkoutId).getOrNull() ?: return
            if (refreshed.checkoutId != checkout.checkoutId) return
            // A GET can show authoritative review but cannot prove that an uncertain write was applied.
            val responseMatchesCart = refreshed.submittedLines.isEmpty() ||
                cartMatches(refreshed.submittedLines, state.submittedLines)
            val browserReason = refreshed.review.browserOnlyReason ?: if (!responseMatchesCart) {
                "The merchant session's line items changed. Continue on its website."
            } else {
                null
            }
            publish(
                key,
                state.copy(
                    checkout = refreshed,
                    phase = if (state.phase == CheckoutPhase.Uncertain) {
                        CheckoutPhase.Uncertain
                    } else if (browserReason != null) {
                        CheckoutPhase.BrowserRequired
                    } else {
                        state.phase
                    },
                    message = if (state.phase == CheckoutPhase.Uncertain) {
                        "Session refreshed. The uncertain write will not be replayed; " +
                            "continue on the merchant website."
                    } else {
                        browserReason ?: state.message
                    },
                ),
            )
            refreshExpiry()
        } finally { lock.unlock() }
    }
}
