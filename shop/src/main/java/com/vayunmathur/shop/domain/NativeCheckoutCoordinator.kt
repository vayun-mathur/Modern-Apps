package com.vayunmathur.shop.domain

import com.vayunmathur.shop.data.*
import com.vayunmathur.shop.network.UcpCheckoutApi
import com.vayunmathur.shop.network.UcpDiscovery
import com.vayunmathur.shop.network.UcpHttpException
import com.vayunmathur.shop.network.UcpService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import java.time.Instant
import java.util.UUID

/** Deliberately has no completion operation. Tests never need a merchant or payment credentials. */
internal interface NativeCheckoutGateway {
    suspend fun resolve(shop: String, version: String? = null): Result<UcpService>
    suspend fun mutate(service: UcpService, lines: List<CartLine>, inputs: CheckoutInputs, previous: UcpCheckout?, intent: String): Result<UcpCheckout>
    suspend fun get(service: UcpService, id: String): Result<UcpCheckout>
}

internal object MerchantCheckoutGateway : NativeCheckoutGateway {
    override suspend fun resolve(shop: String, version: String?): Result<UcpService> = UcpDiscovery.resolve(shop, "dev.ucp.shopping.checkout", version)
    override suspend fun mutate(service: UcpService, lines: List<CartLine>, inputs: CheckoutInputs, previous: UcpCheckout?, intent: String): Result<UcpCheckout> = UcpCheckoutApi.mutate(service, lines, inputs, previous, intent)
    override suspend fun get(service: UcpService, id: String): Result<UcpCheckout> = UcpCheckoutApi.getCheckout(service, id)
}

internal fun interface PendingCheckoutSink { suspend fun save(order: PlacedOrder) }

/** Single-owner ephemeral state; mutex guards each merchant against overlapping writes and reads. */
internal class NativeCheckoutCoordinator(
    private val gateway: NativeCheckoutGateway = MerchantCheckoutGateway,
    private val now: () -> Long = System::currentTimeMillis,
    private val newIntent: () -> String = { UUID.randomUUID().toString() },
    private val changed: (List<NativeCheckoutState>) -> Unit = {},
) {
    private val states = linkedMapOf<Pair<String, String>, NativeCheckoutState>()
    private val locks = mutableMapOf<Pair<String, String>, Mutex>()
    private val services = mutableMapOf<Pair<String, String>, UcpService>()
    private val createdAt = mutableMapOf<Pair<String, String>, Long>()
    val snapshot: List<NativeCheckoutState> get() = states.values.toList()

    private fun publish(key: Pair<String, String>, state: NativeCheckoutState) { states[key] = state; changed(snapshot) }

    fun edit(platform: String, shop: String, inputs: CheckoutInputs) {
        val key = platform to shop
        val state = states[key] ?: NativeCheckoutState(platform, shop)
        if (!state.busy) publish(key, state.copy(inputs = inputs))
    }

    fun cartChanged(lines: List<CartLine>) {
        states.toMap().forEach { (key, state) ->
            if (state.checkout != null && state.submittedLines != lines.filter { it.platformId == key.first && it.shopDomain == key.second }) {
                publish(key, state.copy(phase = if (state.phase == CheckoutPhase.Uncertain) CheckoutPhase.Uncertain else CheckoutPhase.BrowserRequired,
                    message = "The cart changed. This session no longer represents your cart; review it on the merchant website."))
            }
        }
    }

    fun refreshExpiry() {
        states.toMap().forEach { (key, state) ->
            if (!state.busy && state.checkout != null && state.phase != CheckoutPhase.Uncertain && expired(key, state.checkout)) {
                publish(key, state.copy(phase = CheckoutPhase.Expired, message = "This checkout session expired. Continue on the merchant website."))
            }
        }
    }

    private fun expired(key: Pair<String, String>, checkout: UcpCheckout): Boolean = checkout.expiresAt?.let {
        runCatching { Instant.parse(it).toEpochMilli() <= now() }.getOrDefault(true)
    } ?: (now() - (createdAt[key] ?: now()) !in 0 until 6 * 60 * 60 * 1000L)

    suspend fun submit(platform: String, shop: String, lines: List<CartLine>, sink: PendingCheckoutSink) {
        val key = platform to shop
        val lock = locks.getOrPut(key) { Mutex() }
        if (!lock.tryLock()) return
        var mutationStarted = false
        try {
            val state = states[key] ?: NativeCheckoutState(platform, shop)
            if (state.phase in setOf(CheckoutPhase.Uncertain, CheckoutPhase.BrowserRequired, CheckoutPhase.Expired)) return
            if (lines.isEmpty() || lines.any { it.shopDomain != shop || it.platformId != platform || it.quantity !in 1..99 }) return
            state.inputs.validationError()?.let { publish(key, state.copy(phase = CheckoutPhase.Error, message = it)); return }
            val previous = state.checkout
            if (previous != null && expired(key, previous)) {
                publish(key, state.copy(phase = CheckoutPhase.Expired, message = "This session expired. Continue on the merchant website.")); return
            }
            if (previous != null && state.submittedLines != lines) {
                publish(key, state.copy(phase = CheckoutPhase.BrowserRequired, message = "The session's cart changed. Continue on the merchant website.")); return
            }
            if (previous != null && state.submittedInputs == state.inputs) return
            if (previous != null && state.submittedInputs?.hasAddress == true && !state.inputs.hasAddress) {
                publish(key, state.copy(phase = CheckoutPhase.BrowserRequired, message = "Removing a submitted shipping address requires the merchant website.")); return
            }
            publish(key, state.copy(phase = if (previous == null) CheckoutPhase.Preparing else CheckoutPhase.Updating, message = null))
            val service = services[key] ?: gateway.resolve(shop, previous?.version).getOrElse {
                publish(key, state.copy(phase = CheckoutPhase.Error, message = "Merchant checkout discovery failed. Use the merchant website.")); return
            }.also { services[key] = it }
            if (service.version !in CheckoutProjection.versions || service.transport !in setOf("rest", "mcp")) {
                publish(key, state.copy(phase = CheckoutPhase.BrowserRequired, message = "This merchant's checkout version or transport requires its website.")); return
            }
            // Validate everything before handing a write to the transport. No ambiguous failures are retried.
            val sentInputs = if (previous == null) state.inputs.copy(street = "", extendedAddress = "", city = "", region = "", country = "", postalCode = "", options = emptyMap(), shippingRequested = false) else state.inputs
            val projection = runCatching { CheckoutProjection.checkout(service.version, lines, sentInputs, previous, "dev.ucp.shopping.fulfillment" in service.capabilities) }
            if (projection.isFailure) {
                publish(key, state.copy(phase = CheckoutPhase.BrowserRequired, message = "The merchant's writable checkout requirements are unsupported. Use its website.")); return
            }
            val intent = newIntent()
            mutationStarted = true
            val checkout = gateway.mutate(service, lines.toList(), sentInputs, previous, intent).getOrElse {
                val unsupported = (it as? UcpHttpException)?.error is UcpError.Unsupported
                publish(key, state.copy(phase = if (unsupported) CheckoutPhase.BrowserRequired else CheckoutPhase.Uncertain,
                    message = if (unsupported) "These requirements need the merchant website." else "The request may have reached the merchant. Do not retry; check the session or use its website."))
                return
            }
            if (previous != null && checkout.checkoutId != previous.checkoutId || checkout.version != service.version) {
                publish(key, state.copy(phase = CheckoutPhase.Uncertain, message = "The merchant returned a different session. Do not retry.")); return
            }
            createdAt.putIfAbsent(key, now())
            val responseMatchesCart = runCatching { CheckoutProjection.checkout(service.version, lines, sentInputs, checkout, "dev.ucp.shopping.fulfillment" in service.capabilities) }.isSuccess
            val reason = checkout.review.browserOnlyReason ?: if (!responseMatchesCart) "The merchant returned changed or unsupported line items. Review them on its website." else null
            val next = state.copy(checkout = checkout, submittedInputs = sentInputs, submittedLines = lines.toList(),
                phase = if (expired(key, checkout)) CheckoutPhase.Expired else if (reason != null) CheckoutPhase.BrowserRequired else CheckoutPhase.Reviewing,
                message = reason)
            publish(key, next)
            // Persist only the existing pending history projection, never buyer, address, messages or raw response.
            sink.save(PlacedOrder(checkoutId = checkout.checkoutId, placedAtMs = now(), total = checkout.totals.total,
                itemCount = lines.sumOf { it.quantity }, continueUrl = MerchantUrls.httpsUrl(checkout.continueUrl).orEmpty(),
                status = "pending", platformId = platform, shopDomain = shop, lines = lines.map { it.copy(imageUrl = "") },
                totalEntries = checkout.totalEntries.map { it.copy(label = it.type) }, expiresAt = checkout.expiresAt, version = checkout.version))
        } catch (cancelled: CancellationException) {
            if (mutationStarted) states[key]?.let { publish(key, it.copy(phase = CheckoutPhase.Uncertain, message = "Request interrupted. Do not retry; use the merchant website.")) }
            throw cancelled
        } catch (_: Exception) {
            states[key]?.let { publish(key, it.copy(phase = if (mutationStarted) CheckoutPhase.Uncertain else CheckoutPhase.Error,
                message = if (mutationStarted) "The session may have changed. Do not retry; use the merchant website." else "Checkout is unavailable.")) }
        } finally { lock.unlock() }
    }

    suspend fun reconcile(platform: String, shop: String) {
        val key = platform to shop
        val lock = locks.getOrPut(key) { Mutex() }
        if (!lock.tryLock()) return
        try {
            val state = states[key] ?: return
            val checkout = state.checkout ?: return
            val service = services[key] ?: return
            val refreshed = gateway.get(service, checkout.checkoutId).getOrNull() ?: return
            if (refreshed.checkoutId != checkout.checkoutId || refreshed.version != checkout.version) return
            // A GET can show authoritative review but cannot prove that an uncertain write was applied.
            val responseMatchesCart = runCatching { CheckoutProjection.checkout(service.version, state.submittedLines, state.submittedInputs ?: CheckoutInputs(), refreshed, "dev.ucp.shopping.fulfillment" in service.capabilities) }.isSuccess
            val browserReason = refreshed.review.browserOnlyReason ?: if (!responseMatchesCart) "The merchant session's line items changed. Continue on its website." else null
            publish(key, state.copy(checkout = refreshed,
                phase = if (state.phase == CheckoutPhase.Uncertain) CheckoutPhase.Uncertain else if (browserReason != null) CheckoutPhase.BrowserRequired else state.phase,
                message = if (state.phase == CheckoutPhase.Uncertain) "Session refreshed. The uncertain write will not be replayed; continue on the merchant website." else browserReason ?: state.message))
            refreshExpiry()
        } finally { lock.unlock() }
    }
}
