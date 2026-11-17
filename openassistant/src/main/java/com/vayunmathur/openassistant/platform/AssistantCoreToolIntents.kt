package com.vayunmathur.openassistant.platform

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
 * Core cross-app intents (notes, todos, contacts, calendar, family, music,
 * email, clock, weather, maps, taxi) backing [AssistantToolIntents]; the
 * commerce/communicate intents live in the subclass so neither class
 * exceeds the TooManyFunctions limit.
 */
internal abstract class AssistantCoreToolIntents(
    protected val context: Context,
) {
    fun handleMissingApp(packageName: String): String {
        Log.dev("AssistantToolSet", "Handling missing app: $packageName")
        return AssistantToolIntents.getMissingAppMessage(packageName) +
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

}
