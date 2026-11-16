package com.vayunmathur.openassistant.domain

import com.vayunmathur.openassistant.domain.ToolParamType.NUMBER
import com.vayunmathur.openassistant.domain.ToolParamType.STRING
import com.vayunmathur.openassistant.domain.ToolRisk.HANDOFF_PREP
import com.vayunmathur.openassistant.domain.ToolRisk.WRITE_CONFIRM
import com.vayunmathur.openassistant.domain.ToolRisk.WRITE_LIGHT

private fun p(name: String, type: ToolParamType = STRING, description: String = "", required: Boolean = true) =
    ToolParamSpec(name, description, type, required)

/**
 * Every tool the assistant may call, in snake_case as LiteRT-LM's reflection exposed them,
 * with the descriptions the old `@Tool`/`@ToolParam` annotations carried.
 */
val ASSISTANT_TOOL_SPECS: List<ToolSpec> = listOf(
    ToolSpec("get_notes", "Get a list of all notes"),
    ToolSpec(
        "create_note",
        "Create a new note in the notes app (requires confirmation). Should only be used with EXPLICIT request by user",
        listOf(p("title"), p("content")), risk = WRITE_CONFIRM,
    ),
    ToolSpec(
        "get_todos",
        "Get the user's open tasks from the todo app. Due times are epoch milliseconds; " +
            "each task has an id usable with complete_todo.",
        listOf(p("scope", description = "today (default), upcoming, or all", required = false)),
    ),
    ToolSpec(
        "create_todo",
        "Create a task in the todo app (requires confirmation). Should only be used with EXPLICIT request by user. The title may " +
            "include words like 'tomorrow 6pm' or '#tag', which are understood when no due time is given.",
        listOf(
            p("title"),
            p("due", NUMBER, "optional due time as epoch milliseconds", required = false),
            p("notes", required = false),
            p("priority", NUMBER, "optional priority, 1 (highest) to 4", required = false),
        ), risk = WRITE_CONFIRM,
    ),
    ToolSpec(
        "complete_todo",
        "Mark a task done (requires confirmation). Should only be used with EXPLICIT request by user.",
        listOf(
            p("id", description = "task id from get_todos", required = false),
            p("title", description = "exact task title, used when no id is given", required = false),
        ), risk = WRITE_CONFIRM,
    ),
    ToolSpec("get_contacts", "Get a list of all contacts"),
    ToolSpec("create_contact", "Create a new contact (requires confirmation)", listOf(p("name"), p("phone_number")), risk = WRITE_CONFIRM),
    ToolSpec(
        "get_calendar_events",
        "Get a list of calendar events. Each event's start and end times are epoch milliseconds.",
    ),
    ToolSpec(
        "create_calendar_event",
        "Create a new calendar event (requires confirmation). Start and end times must be epoch milliseconds.",
        listOf(
            p("title"),
            p("start", NUMBER, "start time as epoch milliseconds"),
            p("end", NUMBER, "end time as epoch milliseconds"),
            p("location", required = false),
        ), risk = WRITE_CONFIRM,
    ),
    ToolSpec("get_family_locations", "Get a list of family members and their current locations"),
    ToolSpec(
        "create_location_share_link",
        "Create a temporary FindFamily link that shares the user's live location (requires confirmation).",
        listOf(
            p("name", description = "name or label for who the link is for"),
            p("duration_millis", NUMBER, "how long the link stays active, in milliseconds"),
        ), risk = WRITE_CONFIRM,
    ),
    ToolSpec("search_music", "Search for music (songs, albums, artists, or playlists)", listOf(p("query"))),
    ToolSpec(
        "play_music",
        "Play music given its id and type (song, album, artist, or playlist) (requires confirmation)",
        listOf(p("id", NUMBER), p("type")), risk = WRITE_CONFIRM,
    ),
    ToolSpec("search_emails", "Search emails by keyword", listOf(p("query"))),
    ToolSpec("get_recent_emails", "Get recent emails from the inbox"),
    ToolSpec("get_local_current_date_time", "Get the current date and time in the local timezone."),
    ToolSpec("get_app_list", "Get a list of installed apps on the device"),
    ToolSpec("open_app", "Open an app given its package id", listOf(p("package_id", description = "package id"))),
    ToolSpec("send_message", "Send a message", listOf(p("recipient"), p("message"))),
    ToolSpec("make_phone_call", "Make a phone call", listOf(p("recipient"))),
    ToolSpec(
        "set_alarm",
        "Set an alarm in the Clock app at a specific hour and minute (24-hour time) (requires confirmation).",
        listOf(
            p("hour", NUMBER, "hour in 24-hour format, 0-23"),
            p("minute", NUMBER, "minute, 0-59"),
            p("label", description = "optional label for the alarm", required = false),
        ), risk = WRITE_CONFIRM,
    ),
    ToolSpec(
        "set_timer",
        "Start a countdown timer in the Clock app for a number of seconds (requires confirmation).",
        listOf(
            p("seconds", NUMBER, "timer length in seconds"),
            p("label", description = "optional label for the timer", required = false),
        ), risk = WRITE_CONFIRM,
    ),
    ToolSpec(
        "set_conversation_title",
        "Set title of current conversation. Mandatory for first response",
        listOf(p("new_title")),
    ),
    ToolSpec(
        "get_weather",
        "Get the current weather conditions at a specific latitude/longitude.",
        listOf(p("latitude", NUMBER), p("longitude", NUMBER)),
    ),
    ToolSpec(
        "get_weather_by_name",
        "Get the current weather conditions for a named place (city, address, landmark).",
        listOf(p("location", description = "city or place name")),
    ),
    ToolSpec(
        "render_ui",
        "Render a rich UI card (A2UI) attached to your reply. Prefer this over writing a ```a2ui fence. " +
            "components_json is a JSON string: {\"components\":[...]} (or a bare component array) using the RICH UI types; " +
            "data_json is an optional JSON object string with initial data values.",
        listOf(
            p("components_json", description = "A2UI components as a JSON string"),
            p("data_json", description = "optional initial data model as a JSON object string", required = false),
        ),
    ),
    ToolSpec(
        "get_directions",
        "Get directions between two coordinates in the Maps app. Returns distance, duration and segment count.",
        listOf(
            p("from_lat", NUMBER, "origin latitude"),
            p("from_lng", NUMBER, "origin longitude"),
            p("to_lat", NUMBER, "destination latitude"),
            p("to_lng", NUMBER, "destination longitude"),
            p("mode", description = "drive (default), transit, walk, or bicycle", required = false),
        ),
    ),
    ToolSpec(
        "get_ride_estimate",
        "Get a ride fare and pickup ETA estimate from the Taxi app.",
        listOf(
            p("pickup_lat", NUMBER, "pickup latitude"),
            p("pickup_lng", NUMBER, "pickup longitude"),
            p("dest_lat", NUMBER, "destination latitude"),
            p("dest_lng", NUMBER, "destination longitude"),
            p("pickup_label", description = "optional pickup label", required = false),
            p("dest_label", description = "optional destination label", required = false),
        ),
    ),
    ToolSpec(
        "food_order_lookup",
        "Check whether a named restaurant is orderable near a location via the FoodDelivery app.",
        listOf(
            p("name", description = "restaurant name"),
            p("lat", NUMBER, "latitude"),
            p("lng", NUMBER, "longitude"),
            p("address", description = "optional address", required = false),
        ),
    ),
    ToolSpec(
        "search_contacts",
        "Search contacts by name or phone number substring (case-insensitive, max 10 results).",
        listOf(p("query", description = "name or phone substring to search for")),
    ),
    ToolSpec(
        "web_search",
        "Search the web for current or external facts. Returns numbered results; " +
            "use web_read_result to read the most relevant ones before answering.",
        listOf(
            p("query", description = "search query"),
            p("count", NUMBER, "optional result count, 1-10 (default 5)", required = false),
            p("kind", description = "optional: text (default) or news", required = false),
        ),
    ),
    ToolSpec(
        "web_read",
        "Read a web page by URL and return its extracted text.",
        listOf(p("url", description = "page URL to read")),
    ),
    ToolSpec(
        "web_read_result",
        "Read a result from the last web_search by its 1-based index.",
        listOf(
            p("index", NUMBER, "1-based index into the last web_search results"),
            p("offset", NUMBER, "optional character offset for long pages", required = false),
        ),
    ),
    ToolSpec(
        "find_conversations",
        "Find messaging conversations in the Communicate app by name, number or text snippet. " +
            "Returns thread_key values usable with get_conversation_messages.",
        listOf(
            p("query", description = "name, number or text to search (blank lists recent)", required = false),
            p("limit", NUMBER, "optional max conversations 1-50 (default 5)", required = false),
        ),
    ),
    ToolSpec(
        "get_conversation_messages",
        "Get recent messages from a messaging conversation by its thread_key from find_conversations.",
        listOf(
            p("thread_key", description = "thread key like Sim:42 or WhatsApp:123@s.whatsapp.net"),
            p("limit", NUMBER, "optional max messages 1-100 (default 10)", required = false),
        ),
    ),
    ToolSpec(
        "draft_message",
        "Show a message composer card with recipient, line and a draft body. Sends nothing; " +
            "the user can edit the body and tap Send, which then asks for confirmation before sending.",
        listOf(
            p("recipient_name", description = "recipient display name", required = false),
            p("address", description = "recipient phone number or address"),
            p("line", description = "Sim, Rcs, WhatsApp, Signal, or GoogleVoice"),
            p("body", description = "draft message text"),
            p("thread_remote_id", description = "optional existing thread id to reply into", required = false),
        ),
    ),
    ToolSpec(
        "send_message_v2",
        "Send a message via the Communicate app (requires confirmation). Should only be used " +
            "with EXPLICIT request by user. GoogleVoice cannot send from here; use draft_message " +
            "so the user can open the conversation instead.",
        listOf(
            p("address", description = "recipient phone number or address"),
            p("line", description = "Sim, Rcs, WhatsApp, Signal, or GoogleVoice"),
            p("body", description = "message text"),
            p("thread_remote_id", description = "optional existing thread id to reply into", required = false),
        ), risk = WRITE_CONFIRM,
    ),
    ToolSpec("get_memories", "Get a list of all memories"),
    ToolSpec("remove_memory", "Remove a memory by its id (requires confirmation)", listOf(p("id", NUMBER)), risk = WRITE_CONFIRM),
    ToolSpec("add_to_memory", "Add a new memory to the list of memories", listOf(p("content"))),
)
