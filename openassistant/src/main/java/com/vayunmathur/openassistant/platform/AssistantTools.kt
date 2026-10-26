package com.vayunmathur.openassistant.platform

import android.content.Context
import com.vayunmathur.library.intents.calendar.EventData
import com.vayunmathur.library.intents.clock.SetAlarmData
import com.vayunmathur.library.intents.clock.SetTimerData
import com.vayunmathur.library.intents.contacts.ContactData
import com.vayunmathur.library.intents.music.PlayMusicData
import com.vayunmathur.library.intents.notes.NoteData
import com.vayunmathur.library.ml.Gemma4Handle
import com.vayunmathur.openassistant.data.MemoryDao
import com.vayunmathur.openassistant.domain.ASSISTANT_TOOL_SPECS
import com.vayunmathur.openassistant.domain.ParsedToolCall
import com.vayunmathur.openassistant.domain.ToolArgs
import com.vayunmathur.openassistant.domain.ToolArgsResult
import com.vayunmathur.openassistant.domain.ToolSpec
import com.vayunmathur.openassistant.domain.coerceToolArgs
import com.vayunmathur.openassistant.util.AssistantDeviceTools
import com.vayunmathur.openassistant.util.AssistantToolIntents
import kotlinx.coroutines.CancellationException

fun ToolSpec.toDeclaration(): Gemma4Handle.ToolDeclaration = Gemma4Handle.ToolDeclaration(
    name = name,
    description = description,
    parameters = params.map {
        Gemma4Handle.ToolDeclaration.Parameter(it.name, it.description, it.type.gemmaName, it.required)
    },
)

/** The assistant's tools: [ASSISTANT_TOOL_SPECS] bound to the intent and device back ends. */
class AssistantTools(context: Context, private val memoryDao: MemoryDao?) {
    private val intents = AssistantToolIntents(context)
    private val device = AssistantDeviceTools(context)
    private val specs = ASSISTANT_TOOL_SPECS.associateBy { it.name }

    val declarations: List<Gemma4Handle.ToolDeclaration> = ASSISTANT_TOOL_SPECS.map { it.toDeclaration() }

    private val handlers: Map<String, (ToolArgs) -> String> = mapOf(
        "get_notes" to { _ -> intents.runTool { intents.getNotes() } },
        "create_note" to { a ->
            intents.runTool {
                intents.insertNote(NoteData(a.string("title"), a.string("content")))
                "Success: Created note '${a.string("title")}'"
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
        "get_memories" to { _ -> intents.runTool { device.getMemories(memoryDao) } },
        "remove_memory" to { a -> intents.runTool { device.removeMemory(memoryDao, a.number("id")) } },
        "add_to_memory" to { a -> intents.runTool { device.addToMemory(memoryDao, a.string("content")) } },
    )

    init {
        check(handlers.keys == specs.keys) { "tool handlers and specs disagree" }
    }

    /** Runs [call]. Never throws: unknown tools and bad arguments come back as an error string. */
    fun invoke(call: ParsedToolCall): String {
        val spec = specs[call.name] ?: return "Error: unknown tool '${call.name}'"
        return when (val coerced = coerceToolArgs(spec, call.arguments)) {
            is ToolArgsResult.Invalid -> coerced.message
            is ToolArgsResult.Ok -> try {
                handlers.getValue(spec.name)(coerced.args)
            } catch (e: CancellationException) {
                throw e
            } catch (expected: Exception) {
                "Error: ${expected.message}"
            }
        }
    }
}
