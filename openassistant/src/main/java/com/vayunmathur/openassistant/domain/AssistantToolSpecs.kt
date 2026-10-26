package com.vayunmathur.openassistant.domain

import com.vayunmathur.openassistant.domain.ToolParamType.NUMBER
import com.vayunmathur.openassistant.domain.ToolParamType.STRING

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
        "Create a new note in the notes app. Should only be used with EXPLICIT request by user",
        listOf(p("title"), p("content")),
    ),
    ToolSpec("get_contacts", "Get a list of all contacts"),
    ToolSpec("create_contact", "Create a new contact", listOf(p("name"), p("phone_number"))),
    ToolSpec(
        "get_calendar_events",
        "Get a list of calendar events. Each event's start and end times are epoch milliseconds.",
    ),
    ToolSpec(
        "create_calendar_event",
        "Create a new calendar event. Start and end times must be epoch milliseconds.",
        listOf(
            p("title"),
            p("start", NUMBER, "start time as epoch milliseconds"),
            p("end", NUMBER, "end time as epoch milliseconds"),
            p("location", required = false),
        ),
    ),
    ToolSpec("get_family_locations", "Get a list of family members and their current locations"),
    ToolSpec(
        "create_location_share_link",
        "Create a temporary FindFamily link that shares the user's live location.",
        listOf(
            p("name", description = "name or label for who the link is for"),
            p("duration_millis", NUMBER, "how long the link stays active, in milliseconds"),
        ),
    ),
    ToolSpec("search_music", "Search for music (songs, albums, artists, or playlists)", listOf(p("query"))),
    ToolSpec(
        "play_music",
        "Play music given its id and type (song, album, artist, or playlist)",
        listOf(p("id", NUMBER), p("type")),
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
        "Set an alarm in the Clock app at a specific hour and minute (24-hour time).",
        listOf(
            p("hour", NUMBER, "hour in 24-hour format, 0-23"),
            p("minute", NUMBER, "minute, 0-59"),
            p("label", description = "optional label for the alarm", required = false),
        ),
    ),
    ToolSpec(
        "set_timer",
        "Start a countdown timer in the Clock app for a number of seconds.",
        listOf(
            p("seconds", NUMBER, "timer length in seconds"),
            p("label", description = "optional label for the timer", required = false),
        ),
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
    ToolSpec("get_memories", "Get a list of all memories"),
    ToolSpec("remove_memory", "Remove a memory by its id", listOf(p("id", NUMBER))),
    ToolSpec("add_to_memory", "Add a new memory to the list of memories", listOf(p("content"))),
)
