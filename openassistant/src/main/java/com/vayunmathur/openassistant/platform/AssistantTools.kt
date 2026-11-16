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
import kotlinx.coroutines.CancellationException
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
) {
    private val intents = AssistantToolIntents(context)
    private val device = AssistantDeviceTools(context)
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
