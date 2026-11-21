package com.vayunmathur.fooddelivery.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * An in-flight checkout, persisted before PaymentSheet is presented so process
 * death during payment cannot silently create (and charge) a second order.
 *
 * There is no backend query-by-PaymentIntent endpoint (orders are only listed via
 * GET /orders/past/all, keyed by order uuid), so reconciliation matches the
 * persisted draft order uuid against that list — see [checkoutDraftAction].
 * [clientSecret] is the Stripe PaymentIntent client secret for this draft order
 * only; it is not card data and never leaves the device except to Stripe.
 */
@Serializable
data class CheckoutDraft(
    val merchantId: Int = 0,
    /** Server draft-order uuid; a later checkout call with this uuid UPDATEs the draft. */
    val orderUuid: String? = null,
    /** Stripe PaymentIntent id (`pi_…`), parsed from [clientSecret]. */
    val paymentIntentId: String? = null,
    val clientSecret: String? = null,
    val isPickup: Boolean = false,
    val tipCents: Int = 300,
    val promoCode: String = "",
    val dealId: Int? = null,
    val addressId: String? = null,
    val deliveryInstructions: String = "",
    /** True once PaymentSheet has been presented and no result was recorded. */
    val paymentPresented: Boolean = false,
    val savedAtMs: Long = 0,
)

/** What checkout entry should do with a persisted draft. */
enum class CheckoutDraftAction { RestorePlaced, CheckOrders, ResumeDraft, None }

/**
 * Entry-reconcile decision. A draft whose order already appears in the placed-order
 * list is done (restore the success/tracking path, never pay again); a draft whose
 * PaymentSheet was presented but whose order is not placed has an unknown payment
 * outcome, so it reconciles to "check your orders" rather than re-presenting or
 * re-creating; anything else simply resumes the same draft order uuid.
 */
fun checkoutDraftAction(draft: CheckoutDraft?, existingOrderFound: Boolean): CheckoutDraftAction = when {
    draft == null -> CheckoutDraftAction.None
    existingOrderFound -> CheckoutDraftAction.RestorePlaced
    draft.paymentPresented -> CheckoutDraftAction.CheckOrders
    else -> CheckoutDraftAction.ResumeDraft
}

/** Extracts the PaymentIntent id from a `pi_…_secret_…` client secret, or null when malformed. */
fun paymentIntentId(clientSecret: String?): String? =
    clientSecret?.takeIf { it.isNotBlank() }?.substringBefore("_secret_")?.takeIf { it.startsWith("pi_") }

/** Persists the single in-flight checkout draft as JSON in SharedPreferences (CartStore pattern). */
@OptIn(ExperimentalCoroutinesApi::class)
object CheckoutDraftStore {

    private const val PREFS_NAME = "fooddelivery_checkout"
    private const val KEY = "checkout_draft_v1"

    private val json = Json { ignoreUnknownKeys = true }
    private val io = Dispatchers.IO.limitedParallelism(1)

    suspend fun get(context: Context): CheckoutDraft? = withContext(io) {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return@withContext null
        try {
            json.decodeFromString<CheckoutDraft>(raw)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun save(context: Context, draft: CheckoutDraft) = withContext(io) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY, json.encodeToString(draft)) }
    }

    suspend fun clear(context: Context) = withContext(io) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY) }
    }
}
