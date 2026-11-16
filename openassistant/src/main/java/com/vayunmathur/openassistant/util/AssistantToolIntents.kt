package com.vayunmathur.openassistant.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.vayunmathur.library.log.Log
import androidx.core.net.toUri
import com.vayunmathur.library.intents.calendar.EventData
import com.vayunmathur.library.intents.clock.SetAlarmData
import com.vayunmathur.library.intents.clock.SetTimerData
import com.vayunmathur.library.intents.contacts.ContactData
import com.vayunmathur.library.intents.email.EmailData
import com.vayunmathur.library.intents.email.EmailSearchQuery
import com.vayunmathur.library.intents.findfamily.FamilyMemberData
import com.vayunmathur.library.intents.music.MusicSearchResult
import com.vayunmathur.library.intents.music.PlayMusicData
import com.vayunmathur.library.intents.notes.NoteData
import com.vayunmathur.library.intents.todo.CompleteTodoData
import com.vayunmathur.library.intents.todo.GetTodosQuery
import com.vayunmathur.library.intents.todo.InsertTodoData
import com.vayunmathur.library.intents.todo.TodoData
import com.vayunmathur.library.intents.communicate.ConversationData
import com.vayunmathur.library.intents.communicate.ConversationMessageData
import com.vayunmathur.library.intents.communicate.FindConversationsQuery
import com.vayunmathur.library.intents.communicate.GetMessagesQuery
import com.vayunmathur.library.intents.communicate.SendMessageRequest
import com.vayunmathur.library.intents.communicate.SendMessageResult
import com.vayunmathur.library.intents.fooddelivery.CartData as FoodCartData
import com.vayunmathur.library.intents.fooddelivery.FindRestaurants
import com.vayunmathur.library.intents.fooddelivery.GetCart as FoodGetCart
import com.vayunmathur.library.intents.fooddelivery.GetMenu
import com.vayunmathur.library.intents.fooddelivery.GetOrders as FoodGetOrders
import com.vayunmathur.library.intents.fooddelivery.MenuData
import com.vayunmathur.library.intents.fooddelivery.OrderData as FoodOrderData
import com.vayunmathur.library.intents.fooddelivery.OrderLookupRequest
import com.vayunmathur.library.intents.fooddelivery.OrderLookupResult
import com.vayunmathur.library.intents.fooddelivery.OrderSummaryData
import com.vayunmathur.library.intents.fooddelivery.PrepareOrder
import com.vayunmathur.library.intents.fooddelivery.RestaurantData
import com.vayunmathur.library.intents.fooddelivery.SetCart
import com.vayunmathur.library.intents.fooddelivery.SetCartItem
import com.vayunmathur.library.intents.maps.DirectionsRequest
import com.vayunmathur.library.intents.shop.AddToCartRequest
import com.vayunmathur.library.intents.shop.CartData as ShopCartData
import com.vayunmathur.library.intents.shop.GetProductQuery
import com.vayunmathur.library.intents.shop.OrderData as ShopOrderData
import com.vayunmathur.library.intents.shop.ProductData
import com.vayunmathur.library.intents.shop.ProductDetailData
import com.vayunmathur.library.intents.shop.RemoveFromCartRequest
import com.vayunmathur.library.intents.shop.SearchProductsQuery
import com.vayunmathur.library.intents.maps.DirectionsResult
import com.vayunmathur.library.intents.taxi.RideEstimateRequest
import com.vayunmathur.library.intents.taxi.RideEstimateResult
import com.vayunmathur.library.intents.weather.WeatherData
import com.vayunmathur.openassistant.data.Memory
import com.vayunmathur.openassistant.data.MemoryDao
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import com.vayunmathur.openassistant.MainActivity
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

/**
 * Intent-launching back end for the assistant tool registry
 * ([com.vayunmathur.openassistant.platform.AssistantTools]); every tool
 * delegates here for the actual cross-app call.
 */
internal class AssistantToolIntents(private val context: Context) {

    companion object {
        private val APP_NAMES = mapOf(
            "com.vayunmathur.notes" to "Notes",
            "com.vayunmathur.contacts" to "Contacts",
            "com.vayunmathur.calendar" to "Calendar",
            "com.vayunmathur.findfamily" to "FindFamily",
            "com.vayunmathur.music" to "Music",
            "com.vayunmathur.email" to "Email",
            "com.vayunmathur.weather" to "Weather",
            "com.vayunmathur.clock" to "Clock",
            "com.vayunmathur.todo" to "Todo",
            "com.vayunmathur.maps" to "Maps",
            "com.vayunmathur.taxi" to "Taxi",
            "com.vayunmathur.fooddelivery" to "FoodDelivery",
            "com.vayunmathur.communicate" to "Communicate",
            "com.vayunmathur.shop" to "Shop",
        )

        fun getMissingAppMessage(packageName: String): String {
            val appName = APP_NAMES[packageName] ?: packageName
            return "The $appName app is required but not installed. " +
                "[Download from GitHub](https://github.com/vayun-mathur/Modern-Apps)."
        }
    }

    fun handleMissingApp(packageName: String): String {
        Log.dev("AssistantToolSet", "Handling missing app: $packageName")
        return getMissingAppMessage(packageName) +
            " Try to help the user with your own knowledge instead."
    }

    fun runTool(block: suspend () -> String): String = runBlocking {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: MissingAppException) {
            handleMissingApp(e.packageName)
        } catch (expected: Exception) {
            "Error: ${expected.message}"
        }
    }

    suspend fun getNotes(): String = launchIntent<Unit, List<NoteData>>(
        context,
        "com.vayunmathur.notes",
        "com.vayunmathur.notes.intents.GetIntent",
        Unit,
    ).toString()

    suspend fun insertNote(data: NoteData) = launchIntentU(
        context,
        "com.vayunmathur.notes",
        "com.vayunmathur.notes.intents.InsertIntent",
        data,
    )

    suspend fun getTodos(query: GetTodosQuery): String = launchIntent<GetTodosQuery, List<TodoData>>(
        context,
        "com.vayunmathur.todo",
        "com.vayunmathur.todo.intents.GetTodosIntent",
        query,
    ).toString()

    suspend fun insertTodo(data: InsertTodoData): String = launchIntent<InsertTodoData, String>(
        context,
        "com.vayunmathur.todo",
        "com.vayunmathur.todo.intents.InsertTodoIntent",
        data,
    )

    suspend fun completeTodo(data: CompleteTodoData): Boolean = launchIntent<CompleteTodoData, Boolean>(
        context,
        "com.vayunmathur.todo",
        "com.vayunmathur.todo.intents.CompleteTodoIntent",
        data,
    )

    suspend fun getContacts(): String = launchIntent<Unit, List<ContactData>>(
        context,
        "com.vayunmathur.contacts",
        "com.vayunmathur.contacts.intents.GetIntent",
        Unit,
    ).toString()

    suspend fun insertContact(data: ContactData) = launchIntentU(
        context,
        "com.vayunmathur.contacts",
        "com.vayunmathur.contacts.intents.InsertIntent",
        data,
    )

    suspend fun getCalendarEvents(): String = launchIntent<Unit, List<EventData>>(
        context,
        "com.vayunmathur.calendar",
        "com.vayunmathur.calendar.intents.GetIntent",
        Unit,
    ).toString()

    suspend fun insertCalendarEvent(data: EventData) = launchIntentU(
        context,
        "com.vayunmathur.calendar",
        "com.vayunmathur.calendar.intents.InsertIntent",
        data,
    )

    suspend fun getFamilyLocations(): String = launchIntent<Unit, List<FamilyMemberData>>(
        context,
        "com.vayunmathur.findfamily",
        "com.vayunmathur.findfamily.intents.GetIntent",
        Unit,
    ).toString()

    suspend fun createLocationShareLink(name: String, durationMillis: Double): String =
        launchIntent<CreateLinkRequest, String>(
            context,
            "com.vayunmathur.findfamily",
            "com.vayunmathur.findfamily.intents.CreateLinkIntent",
            CreateLinkRequest(name, durationMillis.toLong()),
        )

    suspend fun searchMusic(query: String): String =
        launchIntent<String, List<MusicSearchResult>>(
            context,
            "com.vayunmathur.music",
            "com.vayunmathur.music.intents.SearchIntent",
            query,
        ).toString()

    suspend fun playMusic(data: PlayMusicData) = launchIntentU(
        context,
        "com.vayunmathur.music",
        "com.vayunmathur.music.intents.PlayIntent",
        data,
    )

    suspend fun searchEmails(query: String): String =
        launchIntent<EmailSearchQuery, List<EmailData>>(
            context,
            "com.vayunmathur.email",
            "com.vayunmathur.email.intents.SearchIntent",
            EmailSearchQuery(query),
        ).toString()

    suspend fun getRecentEmails(): String = launchIntent<Unit, List<EmailData>>(
        context,
        "com.vayunmathur.email",
        "com.vayunmathur.email.intents.GetRecentIntent",
        Unit,
    ).toString()

    suspend fun setAlarm(data: SetAlarmData) = launchIntentU(
        context,
        "com.vayunmathur.clock",
        "com.vayunmathur.clock.intents.SetAlarmIntent",
        data,
    )

    suspend fun setTimer(data: SetTimerData) = launchIntentU(
        context,
        "com.vayunmathur.clock",
        "com.vayunmathur.clock.intents.SetTimerIntent",
        data,
    )

    suspend fun getWeather(latitude: Double, longitude: Double): String {
        val result: WeatherData = launchIntent(
            context,
            "com.vayunmathur.weather",
            "com.vayunmathur.weather.intents.GetWeatherIntent",
            WeatherLatLonRequest(latitude, longitude),
        )
        return result.error ?: result.toString()
    }

    suspend fun getWeatherByName(location: String): String {
        val result: WeatherData = launchIntent(
            context,
            "com.vayunmathur.weather",
            "com.vayunmathur.weather.intents.GetWeatherByNameIntent",
            WeatherNameRequest(location),
        )
        return result.error ?: result.toString()
    }

    suspend fun getDirections(
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double,
        mode: String,
    ): String {
        val result: DirectionsResult = launchIntent(
            context,
            "com.vayunmathur.maps",
            "com.vayunmathur.maps.intents.DirectionsIntent",
            DirectionsRequest(fromLat, fromLng, toLat, toLng, mode.ifBlank { "drive" }),
        )
        return summarizeDirections(result)
    }

    suspend fun getRideEstimate(
        pickupLat: Double,
        pickupLng: Double,
        destLat: Double,
        destLng: Double,
        pickupLabel: String,
        destLabel: String,
    ): String {
        val result: RideEstimateResult = launchIntent(
            context,
            "com.vayunmathur.taxi",
            "com.vayunmathur.taxi.intents.RideEstimateIntent",
            RideEstimateRequest(
                pickupLat, pickupLng, destLat, destLng,
                pickupLabel.takeIf { it.isNotBlank() },
                destLabel.takeIf { it.isNotBlank() },
            ),
        )
        return summarizeRideEstimate(result)
    }

    suspend fun foodOrderLookup(name: String, lat: Double, lng: Double, address: String): String {
        val result: OrderLookupResult = launchIntent(
            context,
            "com.vayunmathur.fooddelivery",
            "com.vayunmathur.fooddelivery.intents.OrderLookupIntent",
            OrderLookupRequest(name, lat, lng, address.takeIf { it.isNotBlank() }),
        )
        return summarizeOrderLookup(result)
    }

    suspend fun findConversations(query: String, limit: Int): List<ConversationData> = launchIntent(
        context,
        "com.vayunmathur.communicate",
        "com.vayunmathur.communicate.intents.FindConversationsIntent",
        FindConversationsQuery(query, limit),
    )

    suspend fun getConversationMessages(threadKey: String, limit: Int): List<ConversationMessageData> = launchIntent(
        context,
        "com.vayunmathur.communicate",
        "com.vayunmathur.communicate.intents.GetMessagesIntent",
        GetMessagesQuery(threadKey, limit),
    )

    suspend fun sendMessageV2(request: SendMessageRequest): SendMessageResult = launchIntent(
        context,
        "com.vayunmathur.communicate",
        "com.vayunmathur.communicate.intents.SendMessageIntent",
        request,
    )

    suspend fun findRestaurants(query: String, lat: Double, lng: Double, limit: Int): List<RestaurantData> = launchIntent(
        context,
        "com.vayunmathur.fooddelivery",
        "com.vayunmathur.fooddelivery.intents.FindRestaurantsIntent",
        FindRestaurants(query.takeIf { it.isNotBlank() }, lat, lng, limit),
    )

    suspend fun getRestaurantMenu(merchantId: Int): MenuData = launchIntent(
        context,
        "com.vayunmathur.fooddelivery",
        "com.vayunmathur.fooddelivery.intents.GetMenuIntent",
        GetMenu(merchantId),
    )

    suspend fun getFoodCart(): FoodCartData = launchIntent(
        context,
        "com.vayunmathur.fooddelivery",
        "com.vayunmathur.fooddelivery.intents.GetCartIntent",
        FoodGetCart(),
    )

    suspend fun setFoodCart(merchantId: Int, items: List<SetCartItem>): FoodCartData = launchIntent(
        context,
        "com.vayunmathur.fooddelivery",
        "com.vayunmathur.fooddelivery.intents.SetCartIntent",
        SetCart(merchantId, items),
    )

    suspend fun prepareFoodOrder(request: PrepareOrder): OrderSummaryData = launchIntent(
        context,
        "com.vayunmathur.fooddelivery",
        "com.vayunmathur.fooddelivery.intents.PrepareOrderIntent",
        request,
    )

    suspend fun getFoodOrders(): List<FoodOrderData> = launchIntent(
        context,
        "com.vayunmathur.fooddelivery",
        "com.vayunmathur.fooddelivery.intents.GetOrdersIntent",
        FoodGetOrders(),
    )

    suspend fun searchProducts(query: String, limit: Int): List<ProductData> = launchIntent(
        context,
        "com.vayunmathur.shop",
        "com.vayunmathur.shop.intents.SearchProductsIntent",
        SearchProductsQuery(query, limit),
    )

    suspend fun getProductDetail(shopDomain: String, productId: String, variantId: String?): ProductDetailData = launchIntent(
        context,
        "com.vayunmathur.shop",
        "com.vayunmathur.shop.intents.GetProductIntent",
        GetProductQuery(shopDomain, productId, variantId),
    )

    suspend fun getShopCart(): ShopCartData = launchIntent(
        context,
        "com.vayunmathur.shop",
        "com.vayunmathur.shop.intents.GetCartIntent",
        Unit,
    )

    suspend fun addToShopCart(request: AddToCartRequest): ShopCartData = launchIntent(
        context,
        "com.vayunmathur.shop",
        "com.vayunmathur.shop.intents.AddToCartIntent",
        request,
    )

    suspend fun removeFromShopCart(variantId: String): ShopCartData = launchIntent(
        context,
        "com.vayunmathur.shop",
        "com.vayunmathur.shop.intents.RemoveFromCartIntent",
        RemoveFromCartRequest(variantId),
    )

    suspend fun getShopOrders(): List<ShopOrderData> = launchIntent(
        context,
        "com.vayunmathur.shop",
        "com.vayunmathur.shop.intents.GetOrdersIntent",
        Unit,
    )

    suspend fun getContactsList(): List<ContactData> = launchIntent<Unit, List<ContactData>>(
        context,
        "com.vayunmathur.contacts",
        "com.vayunmathur.contacts.intents.GetIntent",
        Unit,
    )

    suspend fun searchContacts(query: String): String =
        filterContacts(query, getContactsList()).toString()

    suspend fun getMemories(memoryDao: MemoryDao?): String =
        memoryDao?.getAll()?.joinToString("\n") { "[${it.id}] ${it.content}" }
            ?: "Error: MemoryDao is null"

    suspend fun removeMemory(memoryDao: MemoryDao?, id: Double): String {
        if (memoryDao == null) return "Error: MemoryDao is null"
        memoryDao.deleteById(id.toLong())
        return "Success: Removed memory with id ${id.toLong()}"
    }

    suspend fun addToMemory(memoryDao: MemoryDao?, content: String): String {
        if (memoryDao == null) return "Error: MemoryDao is null"
        memoryDao.upsert(Memory(content))
        return "Success: Added memory"
    }
}

/** Missing App Exception: a string implementation for missing app exception. */
class MissingAppException(val packageName: String) : Exception("App $packageName is not installed.")

/** Immutable value describing weather lat lon request; carries latitude, longitude. */
@kotlinx.serialization.Serializable
data class WeatherLatLonRequest(val latitude: Double, val longitude: Double)

/** Immutable value describing weather name request; carries name. */
@kotlinx.serialization.Serializable
data class WeatherNameRequest(val name: String)

/** Immutable value describing create link request; carries name, expiryMillis. */
@kotlinx.serialization.Serializable
data class CreateLinkRequest(val name: String, val expiryMillis: Long)

@OptIn(ExperimentalSerializationApi::class)
suspend inline fun <reified Input : Any, reified Output : Any> launchIntent(
    context: Context,
    packageName: String,
    className: String,
    input: Input,
): Output {
    val stringOutput = MainActivity.intentLauncher.launch(
        context,
        packageName,
        className,
        serializer<Input>(),
        input,
    )
    if (stringOutput == "package $packageName doesn't exist") throw MissingAppException(packageName)
    return Json.decodeFromString(serializer<Output>(), stringOutput)
}

/** Pure contact filter for `search_contacts`: case-insensitive substring on name/phone, capped. */
fun filterContacts(query: String, contacts: List<ContactData>, cap: Int = 10): List<ContactData> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return contacts.take(cap)
    return contacts.filter {
        it.name.lowercase().contains(needle) || it.phoneNumber.lowercase().contains(needle)
    }.take(cap)
}

/**
 * Host-truncated directions summary: totals + segment count only. The full polyline/segments
 * never reach the model (token cap) — this string is all `get_directions` returns.
 */
fun summarizeDirections(result: DirectionsResult): String {
    if (result.polyline.isEmpty() && result.distanceMeters <= 0.0) return "No route found"
    val km = result.distanceMeters / 1000.0
    val dist = if (km >= 10) "%.1f km".format(km) else "%.2f km".format(km)
    val mins = kotlin.math.round(result.durationSeconds / 60.0).toInt()
    val dur = when {
        mins >= 60 -> "${mins / 60}h ${mins % 60}m"
        mins > 0 -> "$mins min"
        else -> "${result.durationSeconds.toInt()}s"
    }
    return "Route: $dist, $dur, ${result.segments.size} segment(s), ${result.polyline.size} point(s)"
}

/** Short ride-estimate summary for the model. */
fun summarizeRideEstimate(result: RideEstimateResult): String =
    if (!result.available) {
        "No ride available for this trip"
    } else {
        "Ride estimate: ${result.fareEstimate}, pickup ETA ${result.etaMinutes} min"
    }

/** Short order-lookup summary for the model. */
fun summarizeOrderLookup(result: OrderLookupResult): String =
    if (!result.available) {
        "Not orderable nearby"
    } else {
        "Orderable: merchantId=${result.merchantId}, link=${result.deepLink}"
    }

/** Host-truncated conversation list for the model (names/keys/lines only). */
fun summarizeConversations(list: List<ConversationData>): String =
    if (list.isEmpty()) {
        "No conversations found"
    } else {
        list.joinToString("\n") { c ->
            val who = c.displayName?.takeIf { it.isNotBlank() } ?: c.address
            "thread_key=${c.threadKey} $who [${c.line}] ${c.snippet.take(80)}"
        }
    }

/** Host-truncated message window for the model; bodies capped per message. */
fun summarizeConversationMessages(list: List<ConversationMessageData>): String =
    if (list.isEmpty()) {
        "No messages found"
    } else {
        list.joinToString("\n") { m ->
            val dir = if (m.outgoing) "me" else m.address
            "[$dir ${m.line}] ${m.body.take(200)}"
        }
    }

/** Host-truncated restaurant list for the model. */
fun summarizeRestaurants(list: List<RestaurantData>): String =
    if (list.isEmpty()) {
        "No restaurants found"
    } else {
        list.joinToString("\n") { r ->
            val rating = r.rating?.let { " ★$it" } ?: ""
            "merchant_id=${r.id} ${r.name}$rating ${if (r.isOpen) "open" else "closed"} ${r.address.take(60)}"
        }
    }

/** Host-truncated menu for the model: ids, names, cents, availability, modifier rules. */
fun summarizeMenu(menu: MenuData, cap: Int = 24): String {
    if (menu.error != null) return "Error: menu unavailable (${menu.error})"
    if (menu.items.isEmpty()) return "No menu items for ${menu.merchantName}"
    val shown = menu.items.take(cap)
    val lines = shown.joinToString("\n") { item ->
        val mods = item.modifierGroups.joinToString(";") { g ->
            "${g.name}[${g.modifiers.joinToString(",") { "${it.id}:${it.name}" }}] req=${g.required} min=${g.minSelections} max=${g.maxSelections}"
        }
        "item_id=${item.id} ${item.name} ${item.priceCents}c ${if (item.available) "available" else "unavailable"} ${item.category}${if (mods.isBlank()) "" else " $mods"}"
    }
    val more = if (menu.items.size > cap) "\n(${menu.items.size - cap} more items not shown)" else ""
    return "Menu ${menu.merchantName} (merchant_id=${menu.merchantId}):\n$lines$more"
}

/** Cart summary for the model; also the success string of every cart write. */
fun summarizeFoodCart(cart: FoodCartData): String {
    if (cart.error != null) return "Error: cart not updated (${cart.error})"
    if (cart.items.isEmpty()) return "Cart is empty"
    val lines = cart.items.joinToString("\n") { "item_id=${it.itemId} ${it.name} x${it.qty} ${it.lineTotalCents}c" }
    return "Cart ${cart.merchantName} (merchant_id=${cart.merchantId}):\n$lines\nTotal ${cart.totalCents}c"
}

/** Priced-order summary for the model; taxes/fee unknown until in-app checkout. */
fun summarizeOrderSummary(summary: OrderSummaryData): String {
    if (summary.error != null) return "Error: order not priced (${summary.error})"
    val lines = summary.items.joinToString("\n") { "${it.name} x${it.qty} ${it.lineTotalCents}c" }
    return "Order summary ${summary.merchantName} (${if (summary.isPickup) "pickup" else "delivery"}):\n$lines\n" +
        "subtotal=${summary.subtotalCents}c tip=${summary.tipCents}c total=${summary.totalCents}c " +
        "(taxes/delivery fee confirmed in FoodDelivery) checkout=${summary.checkoutDeepLink}"
}

/** Host-truncated past orders for the model. */
fun summarizeFoodOrders(list: List<FoodOrderData>, cap: Int = 10): String =
    if (list.isEmpty()) {
        "No past orders"
    } else {
        list.take(cap).joinToString("\n") { o ->
            "order_id=${o.id} ${o.merchantName} ${o.status} ${o.totalCents}c items=${o.itemCount}"
        }
    }

/** Parses the food_set_cart items_json arg ([{itemId, qty, modifierIds}]). */
fun parseSetCartItems(itemsJson: String): List<SetCartItem>? {
    val root = runCatching { Json.parseToJsonElement(itemsJson) }.getOrNull() ?: return null
    val array = root as? kotlinx.serialization.json.JsonArray ?: return null
    return array.map { element ->
        val obj = element as? kotlinx.serialization.json.JsonObject ?: return null
        val itemId = (obj["itemId"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
        val qty = (obj["qty"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
        val mods = (obj["modifierIds"] as? kotlinx.serialization.json.JsonArray)
            ?.map { (it as? JsonPrimitive)?.content?.toIntOrNull() ?: return null } ?: emptyList()
        SetCartItem(itemId, qty, mods)
    }
}

/** Host-truncated product hits for the model: one line per purchasable variant. */
fun summarizeProducts(list: List<ProductData>): String =
    if (list.isEmpty()) {
        "No products found"
    } else {
        list.joinToString("\n") { p ->
            "variant_id=${p.variantId} product_id=${p.productId} ${p.title} ${p.price} ${p.currency} @ ${p.storeName} (${p.shopDomain}) checkout=${p.checkoutEligible}"
        }
    }

/** Product detail for the model: every variant's exact price/availability. */
fun summarizeProductDetail(detail: ProductDetailData): String {
    if (detail.error != null) return "Error: product unavailable (${detail.error})"
    val variants = detail.variants.joinToString("\n") { v ->
        "variant_id=${v.variantId} ${v.title} ${v.price} ${v.currency} ${if (v.available) "available" else "unavailable"}"
    }
    return "Product ${detail.title} @ ${detail.storeName} (${detail.shopDomain}) from ${detail.price} ${detail.currency}:\n$variants"
}

/** Shop cart for the model, grouped per store; a refused write leaves it unchanged. */
fun summarizeShopCart(cart: ShopCartData): String {
    if (cart.error != null) return "Error: cart not updated (${cart.error})"
    if (cart.stores.isEmpty() || cart.totalItems == 0) return "Shop cart is empty"
    return cart.stores.joinToString("\n") { store ->
        val lines = store.lines.joinToString("\n") { l ->
            "variant_id=${l.variantId} ${l.title} ${l.variantTitle} x${l.quantity} ${l.price} ${l.currency}"
        }
        "Store ${store.storeName} (${store.shopDomain}):\n$lines"
    }
}

/** Host-truncated shop order summaries; a pending handoff is never a paid order. */
fun summarizeShopOrders(list: List<ShopOrderData>, cap: Int = 10): String =
    if (list.isEmpty()) {
        "No shop orders"
    } else {
        list.take(cap).joinToString("\n") { o ->
            "order_id=${o.orderId} ${o.storeName} ${o.status} ${o.total} ${o.currency} items=${o.itemCount}"
        }
    }

/** Maps a provider send outcome to the model-facing string; GV is typed. */
fun summarizeSendResult(result: SendMessageResult): String = when {
    result.success -> "Success: Message sent"
    result.error == "GOOGLE_VOICE_REQUIRES_ACTIVITY" ->
        "Error: Google Voice messages cannot be sent from here; the conversation must be opened in Communicate instead"
    result.error != null -> "Error: message not sent (${result.error})"
    else -> "Error: message not sent"
}

@OptIn(ExperimentalSerializationApi::class)
suspend inline fun <reified Input : Any> launchIntentU(
    context: Context,
    packageName: String,
    className: String,
    input: Input,
) {
    val stringOutput = MainActivity.intentLauncher.launch(
        context,
        packageName,
        className,
        serializer<Input>(),
        input,
    )
    if (stringOutput == "package $packageName doesn't exist") throw MissingAppException(packageName)
}
