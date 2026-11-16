package com.vayunmathur.openassistant.platform

import android.content.Context
import com.vayunmathur.library.intents.calendar.EventData
import com.vayunmathur.library.intents.clock.SetAlarmData
import com.vayunmathur.library.intents.clock.SetTimerData
import com.vayunmathur.library.intents.contacts.ContactData
import com.vayunmathur.library.intents.music.PlayMusicData
import com.vayunmathur.library.intents.notes.NoteData
import com.vayunmathur.library.intents.todo.CompleteTodoData
import com.vayunmathur.library.intents.todo.GetTodosQuery
import com.vayunmathur.library.intents.todo.InsertTodoData
import com.vayunmathur.library.ml.Gemma4Handle
import com.vayunmathur.openassistant.data.MemoryDao
import com.vayunmathur.openassistant.domain.ASSISTANT_TOOL_SPECS
import com.vayunmathur.openassistant.domain.InMemoryPendingToolCallStore
import com.vayunmathur.openassistant.domain.ParsedToolCall
import com.vayunmathur.openassistant.domain.PendingToolCall
import com.vayunmathur.openassistant.domain.PendingToolCallStore
import com.vayunmathur.openassistant.domain.ToolArgs
import com.vayunmathur.openassistant.domain.ToolArgsResult
import com.vayunmathur.openassistant.domain.ToolRisk
import com.vayunmathur.openassistant.domain.ToolSpec
import com.vayunmathur.openassistant.domain.buildA2uiEnvelope
import com.vayunmathur.openassistant.domain.coerceToolArgs
import com.vayunmathur.openassistant.domain.pendingToolSummary
import com.vayunmathur.openassistant.util.AssistantDeviceTools
import com.vayunmathur.openassistant.util.AssistantToolIntents
import com.vayunmathur.openassistant.util.AssistantWebTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.UUID

fun ToolSpec.toDeclaration(): Gemma4Handle.ToolDeclaration = Gemma4Handle.ToolDeclaration(
    name = name,
    description = description,
    parameters = params.map {
        Gemma4Handle.ToolDeclaration.Parameter(it.name, it.description, it.type.gemmaName, it.required)
    },
)

/** The assistant's tools: [ASSISTANT_TOOL_SPECS] bound to the intent and device back ends. */
class AssistantTools(
    context: Context,
    private val memoryDao: MemoryDao?,
    private val pendingStore: PendingToolCallStore = InMemoryPendingToolCallStore(),
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val webTools: AssistantWebTools = AssistantWebTools(),
) {
    private val intents = AssistantToolIntents(context)
    private val device = AssistantDeviceTools(context)

    /** Exposed for tests / host code that need the web tool's stored results. */
    fun webTools(): AssistantWebTools = webTools
    private val specs = ASSISTANT_TOOL_SPECS.associateBy { it.name }

    val declarations: List<Gemma4Handle.ToolDeclaration> = ASSISTANT_TOOL_SPECS.map { it.toDeclaration() }

    /**
     * Envelope JSON stashed by the last successful `render_ui` call. AssistantTools cannot
     * write the DB, so [MamlChatEngine] consumes this after the loop and appends it to the
     * reply as an a2ui fence, which the normal split/persist path then stores.
     */
    var pendingRenderUi: String? = null

    private val handlers: Map<String, (ToolArgs) -> String> = mapOf(
        "get_notes" to { _ -> intents.runTool { intents.getNotes() } },
        "create_note" to { a ->
            intents.runTool {
                intents.insertNote(NoteData(a.string("title"), a.string("content")))
                "Success: Created note '${a.string("title")}'"
            }
        },
        "get_todos" to { a -> intents.runTool { intents.getTodos(GetTodosQuery(a.string("scope", "today"))) } },
        "create_todo" to { a ->
            intents.runTool {
                val title = a.string("title")
                intents.insertTodo(
                    InsertTodoData(
                        title = title,
                        notes = a.string("notes"),
                        dueAt = a.number("due").toLong().takeIf { it > 0 },
                        priority = a.number("priority").toInt(),
                    ),
                )
                "Success: Created task '$title'"
            }
        },
        "complete_todo" to { a ->
            intents.runTool {
                if (intents.completeTodo(CompleteTodoData(a.string("id"), a.string("title")))) {
                    "Success: Marked the task done"
                } else {
                    "Error: No matching open task"
                }
            }
        },
        "get_contacts" to { _ -> intents.runTool { intents.getContacts() } },
        "create_contact" to { a ->
            intents.runTool {
                intents.insertContact(ContactData(a.string("name"), a.string("phone_number")))
                "Success: Created contact '${a.string("name")}'"
            }
        },
        "get_calendar_events" to { _ -> intents.runTool { intents.getCalendarEvents() } },
        "create_calendar_event" to { a ->
            intents.runTool {
                val title = a.string("title")
                intents.insertCalendarEvent(
                    EventData(title, a.number("start").toLong(), a.number("end").toLong(), a.string("location")),
                )
                "Success: Created event '$title'"
            }
        },
        "get_family_locations" to { _ -> intents.runTool { intents.getFamilyLocations() } },
        "create_location_share_link" to { a ->
            intents.runTool { intents.createLocationShareLink(a.string("name"), a.number("duration_millis")) }
        },
        "search_music" to { a -> intents.runTool { intents.searchMusic(a.string("query")) } },
        "play_music" to { a ->
            intents.runTool {
                intents.playMusic(PlayMusicData(a.number("id").toLong(), a.string("type")))
                "Success: Playing music"
            }
        },
        "search_emails" to { a -> intents.runTool { intents.searchEmails(a.string("query")) } },
        "get_recent_emails" to { _ -> intents.runTool { intents.getRecentEmails() } },
        "get_local_current_date_time" to { _ -> device.getLocalCurrentDateTime() },
        "get_app_list" to { _ -> device.getAppList() },
        "open_app" to { a -> device.openApp(a.string("package_id"), intents::handleMissingApp) },
        "send_message" to { a -> device.sendMessage(a.string("recipient"), a.string("message")) },
        "make_phone_call" to { a -> device.makePhoneCall(a.string("recipient")) },
        "set_alarm" to { a ->
            val hour = a.number("hour").toInt()
            val minute = a.number("minute").toInt()
            intents.runTool {
                intents.setAlarm(SetAlarmData(hour, minute, a.string("label")))
                "Success: Alarm set for %02d:%02d".format(hour, minute)
            }
        },
        "set_timer" to { a ->
            val seconds = a.number("seconds").toInt()
            intents.runTool {
                intents.setTimer(SetTimerData(seconds, a.string("label")))
                "Success: Timer started for ${seconds}s"
            }
        },
        "set_conversation_title" to { a -> device.setConversationTitle(a.string("new_title")) },
        "get_weather" to { a ->
            intents.runTool { intents.getWeather(a.number("latitude"), a.number("longitude")) }
        },
        "get_weather_by_name" to { a -> intents.runTool { intents.getWeatherByName(a.string("location")) } },
        "get_directions" to { a ->
            intents.runTool {
                intents.getDirections(
                    a.number("from_lat"), a.number("from_lng"),
                    a.number("to_lat"), a.number("to_lng"),
                    a.string("mode", "drive"),
                )
            }
        },
        "get_ride_estimate" to { a ->
            intents.runTool {
                intents.getRideEstimate(
                    a.number("pickup_lat"), a.number("pickup_lng"),
                    a.number("dest_lat"), a.number("dest_lng"),
                    a.string("pickup_label"), a.string("dest_label"),
                )
            }
        },
        "food_order_lookup" to { a ->
            intents.runTool {
                intents.foodOrderLookup(
                    a.string("name"), a.number("lat"), a.number("lng"), a.string("address"),
                )
            }
        },
        "search_contacts" to { a -> intents.runTool { intents.searchContacts(a.string("query")) } },
        "find_conversations" to { a ->
            intents.runTool {
                val limit = a.number("limit").toInt().takeIf { it > 0 } ?: 5
                val list = intents.findConversations(a.string("query"), limit)
                if (list.isNotEmpty()) {
                    pendingRenderUi = A2uiTemplates.listPicker(
                        "Conversations",
                        list.map {
                            PickerItem(
                                it.threadKey,
                                it.displayName?.takeIf { n -> n.isNotBlank() } ?: it.address,
                                "${it.line} · ${it.snippet.take(60)}",
                            )
                        },
                        actionName = "pick.conversation",
                    )
                }
                com.vayunmathur.openassistant.util.summarizeConversations(list)
            }
        },
        "get_conversation_messages" to { a ->
            intents.runTool {
                val limit = a.number("limit").toInt().takeIf { it > 0 } ?: 10
                com.vayunmathur.openassistant.util.summarizeConversationMessages(
                    intents.getConversationMessages(a.string("thread_key"), limit),
                )
            }
        },
        "draft_message" to { a ->
            pendingRenderUi = A2uiTemplates.messageComposer(
                a.string("recipient_name"), a.string("address"), a.string("line"),
                a.string("body"), a.string("thread_remote_id").takeIf { it.isNotBlank() },
            )
            "Composer shown. Nothing has been sent; the user reviews, edits and taps Send."
        },
        "send_message_v2" to { a ->
            intents.runTool {
                val result = intents.sendMessageV2(
                    com.vayunmathur.library.intents.communicate.SendMessageRequest(
                        a.string("address"), a.string("line"), a.string("body"),
                        a.string("thread_remote_id").takeIf { it.isNotBlank() },
                    ),
                )
                if (!result.success && result.error == "GOOGLE_VOICE_REQUIRES_ACTIVITY") {
                    pendingRenderUi = A2uiTemplates.resultCard(
                        "Open in Communicate",
                        listOf("Google Voice messages are sent from the Communicate app."),
                        actionLabel = "Open conversation",
                        actionName = "open.conversation",
                        actionContext = mapOf(
                            "address" to a.string("address"),
                            "line" to a.string("line"),
                        ),
                    )
                }
                com.vayunmathur.openassistant.util.summarizeSendResult(result)
            }
        },
        "find_restaurants" to { a ->
            intents.runTool {
                val limit = a.number("limit").toInt().takeIf { it > 0 } ?: 5
                val list = intents.findRestaurants(
                    a.string("query"), a.number("lat"), a.number("lng"), limit)
                if (list.isNotEmpty()) {
                    pendingRenderUi = A2uiTemplates.listPicker(
                        "Restaurants",
                        list.map {
                            PickerItem(
                                it.id.toString(), it.name,
                                "${if (it.isOpen) "Open" else "Closed"} · ${it.address.take(50)}",
                                it.imageUrl.takeIf { u -> u.isNotBlank() },
                            )
                        },
                        actionName = "pick.restaurant",
                    )
                }
                com.vayunmathur.openassistant.util.summarizeRestaurants(list)
            }
        },
        "get_restaurant_menu" to { a ->
            intents.runTool {
                val menu = intents.getRestaurantMenu(a.number("merchant_id").toInt())
                if (menu.error == null && menu.items.isNotEmpty()) {
                    pendingRenderUi = A2uiTemplates.foodMenu(
                        menu.merchantName, menu.merchantId,
                        menu.items.take(24).map {
                            MenuItemRow(it.id, it.name, it.priceCents, it.available)
                        },
                    )
                }
                com.vayunmathur.openassistant.util.summarizeMenu(menu)
            }
        },
        "food_get_cart" to { _ ->
            intents.runTool {
                val cart = intents.getFoodCart()
                if (cart.error == null && cart.items.isNotEmpty()) {
                    pendingRenderUi = A2uiTemplates.foodCart(
                        cart.merchantName, cart.merchantId,
                        cart.items.map { CartLineRow(it.itemId, it.name, it.qty, it.lineTotalCents) },
                        cart.totalCents,
                    )
                }
                com.vayunmathur.openassistant.util.summarizeFoodCart(cart)
            }
        },
        "food_set_cart" to { a ->
            val items = com.vayunmathur.openassistant.util.parseSetCartItems(a.string("items_json"))
                ?: return@to "Error: items_json must be a JSON array of {itemId, qty, modifierIds}"
            intents.runTool {
                val cart = intents.setFoodCart(a.number("merchant_id").toInt(), items)
                // Light write (design §2): executed immediately, always rendered back.
                if (cart.error == null) {
                    pendingRenderUi = A2uiTemplates.foodCart(
                        cart.merchantName, cart.merchantId,
                        cart.items.map { CartLineRow(it.itemId, it.name, it.qty, it.lineTotalCents) },
                        cart.totalCents,
                    )
                }
                com.vayunmathur.openassistant.util.summarizeFoodCart(cart)
            }
        },
        "food_prepare_order" to { a ->
            intents.runTool {
                com.vayunmathur.openassistant.util.summarizeOrderSummary(
                    intents.prepareFoodOrder(
                        com.vayunmathur.library.intents.fooddelivery.PrepareOrder(
                            merchantId = a.number("merchant_id").toInt(),
                            isPickup = a.number("is_pickup").toInt() == 1,
                            addressId = a.string("address_id").takeIf { it.isNotBlank() },
                            tipCents = a.number("tip_cents").toInt(),
                            promo = a.string("promo").takeIf { it.isNotBlank() },
                        ),
                    ),
                )
            }
        },
        "place_food_order" to { a ->
            intents.runTool {
                val summary = intents.prepareFoodOrder(
                    com.vayunmathur.library.intents.fooddelivery.PrepareOrder(
                        merchantId = a.number("merchant_id").toInt(),
                    ),
                )
                if (summary.error == null) {
                    pendingRenderUi = A2uiTemplates.foodOrderSummary(
                        summary.merchantName,
                        summary.items.map { "${it.name} × ${it.qty} — ${formatCents(it.lineTotalCents)}" },
                        summary.subtotalCents, summary.tipCents, summary.totalCents,
                        summary.checkoutDeepLink, summary.merchantId,
                    )
                }
                com.vayunmathur.openassistant.util.summarizeOrderSummary(summary) +
                    " Show the user this summary; they place the order in FoodDelivery."
            }
        },
        "get_food_orders" to { _ ->
            intents.runTool {
                com.vayunmathur.openassistant.util.summarizeFoodOrders(intents.getFoodOrders())
            }
        },
        "web_search" to { a ->
            val count = a.number("count").toInt().takeIf { it > 0 } ?: 5
            val result = runBlocking { webTools.webSearch(a.string("query"), count, a.string("kind", "text")) }
            webTools.takeTemplate()?.let { pendingRenderUi = it }
            result
        },
        "web_read" to { a -> runBlocking { webTools.webRead(a.string("url")) } },
        "web_read_result" to { a ->
            runBlocking { webTools.webReadResult(a.number("index").toInt(), a.number("offset").toInt()) }
        },
        "render_ui" to { a -> renderUi(a.string("components_json"), a.string("data_json")) },
        "get_memories" to { _ -> intents.runTool { device.getMemories(memoryDao) } },
        "remove_memory" to { a -> intents.runTool { device.removeMemory(memoryDao, a.number("id")) } },
        "add_to_memory" to { a -> intents.runTool { device.addToMemory(memoryDao, a.string("content")) } },
    )

    init {
        check(handlers.keys == specs.keys) { "tool handlers and specs disagree" }
    }

    fun specFor(name: String): ToolSpec? = specs[name]

    /** Creates a pending call for a WRITE_CONFIRM tool, or null if not gated/invalid. */
    fun createPending(call: ParsedToolCall): PendingToolCall? {
        val spec = specs[call.name] ?: return null
        if (spec.risk != ToolRisk.WRITE_CONFIRM) return null
        if (coerceToolArgs(spec, call.arguments) is ToolArgsResult.Invalid) return null
        val now = nowMillis()
        val pending = PendingToolCall(
            id = idGenerator(),
            name = call.name,
            args = call.arguments,
            summary = pendingToolSummary(call.name, call.arguments),
            createdAtMillis = now,
        )
        pendingStore.put(pending)
        return pending
    }

    fun pendingById(id: String): PendingToolCall? = pendingStore.get(id)

    fun restorePending(pending: PendingToolCall) = pendingStore.put(pending)

    fun removePending(id: String) = pendingStore.remove(id)

    /** Runs [call]. Never throws: unknown tools and bad arguments come back as an error string. */
    fun invoke(call: ParsedToolCall): String {
        val spec = specs[call.name] ?: return "Error: unknown tool '${call.name}'"
        return when (val coerced = coerceToolArgs(spec, call.arguments)) {
            is ToolArgsResult.Invalid -> coerced.message
            is ToolArgsResult.Ok -> {
                if (spec.risk == ToolRisk.WRITE_CONFIRM) {
                    return "Error: confirmation required for ${spec.name}"
                }
                execute(spec.name, coerced.args)
            }
        }
    }

    /**
     * Executes a pending call exactly once. Unknown, expired or consumed ids
     * return an error and never execute the handler.
     */
    fun invokeConfirmed(pendingId: String): String {
        val pending = pendingStore.get(pendingId)
            ?: return "Error: pending tool call not found"
        if (pending.consumed) return "Error: pending tool call already consumed"
        if (pending.isExpired(nowMillis())) return "Error: pending tool call expired"
        val spec = specs[pending.name] ?: return "Error: unknown tool '${pending.name}'"
        val coerced = when (val result = coerceToolArgs(spec, pending.args)) {
            is ToolArgsResult.Invalid -> return result.message
            is ToolArgsResult.Ok -> result.args
        }
        if (!pendingStore.markConsumed(pendingId)) {
            return "Error: pending tool call already consumed"
        }
        return execute(spec.name, coerced)
    }

    /** Takes and clears the stashed [pendingRenderUi] envelope, if any. */
    fun consumePendingRenderUi(): String? {
        val pending = pendingRenderUi
        pendingRenderUi = null
        return pending
    }

    /**
     * Validates a `render_ui` payload and stashes it in [pendingRenderUi] for the engine
     * to attach to the current assistant message. Never throws; bad UI comes back as an
     * error string so the model can fix it.
     */
    private fun renderUi(componentsJson: String, dataJson: String): String {
        if (componentsJson.isBlank()) return "Error: render_ui needs components_json"
        val combined = buildRenderUiJson(componentsJson, dataJson)
            ?: return "Error: invalid A2UI: components_json/data_json is not valid JSON"
        if (combined.length > MAX_RENDER_UI_CHARS) {
            return "Error: render_ui payload too large (${combined.length} chars, max $MAX_RENDER_UI_CHARS)"
        }
        if (buildA2uiEnvelope(TEMP_SURFACE_ID, AssistantCatalog.ID, combined) == null) {
            return "Error: invalid A2UI: no usable components"
        }
        pendingRenderUi = combined
        return "UI rendered"
    }

    /** Merges components_json (+ optional data_json object) into one {"components","data"} string. */
    private fun buildRenderUiJson(componentsJson: String, dataJson: String): String? {
        val componentsRoot = runCatching { Json.parseToJsonElement(componentsJson) }.getOrNull() ?: return null
        val dataOverride = if (dataJson.isBlank()) {
            null
        } else {
            runCatching { Json.parseToJsonElement(dataJson) }.getOrNull() as? JsonObject ?: return null
        }
        return when (componentsRoot) {
            is JsonArray -> JsonObject(
                buildMap {
                    put("components", componentsRoot)
                    if (dataOverride != null) put("data", dataOverride)
                },
            ).toString()
            is JsonObject -> {
                if (componentsRoot["components"] !is JsonArray) return null
                val merged = LinkedHashMap(componentsRoot)
                if (dataOverride != null) merged["data"] = dataOverride
                JsonObject(merged).toString()
            }
            else -> null
        }
    }

    private fun execute(name: String, args: ToolArgs): String = try {
        handlers.getValue(name)(args)
    } catch (e: CancellationException) {
        throw e
    } catch (expected: Exception) {
        "Error: ${expected.message}"
    }

    private companion object {
        const val TEMP_SURFACE_ID = "temp"
        const val MAX_RENDER_UI_CHARS = 12_000
    }
}
