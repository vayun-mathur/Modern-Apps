package com.vayunmathur.openassistant.util
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import androidx.core.net.toUri
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.vayunmathur.library.intents.calendar.EventData
import com.vayunmathur.library.intents.contacts.ContactData
import com.vayunmathur.library.intents.findfamily.FamilyMemberData
import com.vayunmathur.library.intents.music.MusicSearchResult
import com.vayunmathur.library.intents.music.PlayMusicData
import com.vayunmathur.openassistant.MainActivity
import com.vayunmathur.library.intents.notes.NoteData
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.decodeFromString
import kotlin.reflect.KClass

class AssistantToolSet(private val context: Context) : ToolSet {

    @Tool(description = "Get a list of all notes")
    fun get_notes(): String {
        return try {
            val result: List<NoteData> = launchIntent(
                context,
                "com.vayunmathur.notes",
                "com.vayunmathur.notes.intents.GetIntent",
                Unit
            )
            result.toString()
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    @Tool(description = "Create a new note")
    fun create_note(title: String, content: String): String {
        return try {
            launchIntentU(
                context,
                "com.vayunmathur.notes",
                "com.vayunmathur.notes.intents.InsertIntent",
                NoteData(title, content)
            )
            "Success: Created note '$title'"
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    @Tool(description = "Get a list of all contacts")
    fun get_contacts(): String {
        return try {
            val result: List<ContactData> = launchIntent(
                context,
                "com.vayunmathur.contacts",
                "com.vayunmathur.contacts.intents.GetIntent",
                Unit
            )
            result.toString()
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    @Tool(description = "Create a new contact")
    fun create_contact(name: String, phoneNumber: String): String {
        return try {
            launchIntentU(
                context,
                "com.vayunmathur.contacts",
                "com.vayunmathur.contacts.intents.InsertIntent",
                ContactData(name, phoneNumber)
            )
            "Success: Created contact '$name'"
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    @Tool(description = "Get a list of calendar events")
    fun get_calendar_events(): String {
        return try {
            val result: List<EventData> = launchIntent(
                context,
                "com.vayunmathur.calendar",
                "com.vayunmathur.calendar.intents.GetIntent",
                Unit
            )
            result.toString()
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    @Tool(description = "Create a new calendar event")
    fun create_calendar_event(title: String, start: Double, end: Double, location: String = ""): String {
        return try {
            launchIntentU(
                context,
                "com.vayunmathur.calendar",
                "com.vayunmathur.calendar.intents.InsertIntent",
                EventData(title, start.toLong(), end.toLong(), location)
            )
            "Success: Created event '$title'"
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    @Tool(description = "Get a list of family members and their current locations")
    fun get_family_locations(): String {
        return try {
            val result: List<FamilyMemberData> = launchIntent(
                context,
                "com.vayunmathur.findfamily",
                "com.vayunmathur.findfamily.intents.GetIntent",
                Unit
            )
            println(result)
            result.toString()
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    @Tool(description = "Search for music (songs, albums, artists, or playlists)")
    fun search_music(query: String): String {
        return try {
            val result: List<MusicSearchResult> = launchIntent(
                context,
                "com.vayunmathur.music",
                "com.vayunmathur.music.intents.SearchIntent",
                query
            )
            result.toString()
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    @Tool(description = "Play music given its id and type (song, album, artist, or playlist)")
    fun play_music(id: Double, type: String): String {
        return try {
            launchIntentU(
                context,
                "com.vayunmathur.music",
                "com.vayunmathur.music.intents.PlayIntent",
                PlayMusicData(id.toLong(), type),
            )
            "Success: Playing music"
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    @Tool(description = "Get the current date and time in the local timezone")
    fun get_local_current_date_time(): String {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val tzId = TimeZone.currentSystemDefault().id
        return "$tzId: $now"
    }

    @SuppressLint("QueryPermissionsNeeded")
    @Tool(description = "Get a list of installed apps on the device")
    fun get_app_list(): String {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        return apps.map { it.loadLabel(pm).toString() }.toString()
    }

    @Tool(description = "Open an app given its package id")
    fun open_app(@ToolParam(description = "package id") packageId: String): String {
        val intent = context.packageManager.getLaunchIntentForPackage(packageId)
        return if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Success: Opened $packageId"
        } else "Error: App not found"
    }

    @Tool(description = "Send a message")
    fun send_message(recipient: String, message: String): String {
        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = "smsto:$recipient".toUri()
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Opened messaging app."
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    @Tool(description = "Make a phone call")
    fun make_phone_call(recipient: String): String {
        return try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = "tel:$recipient".toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Opened dialer."
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    @Tool("Set title of current conversation. Mandatory for first response")
    fun set_conversation_title(newTitle: String): String {
        InferenceService.newTitle = newTitle
        return "Conversation title set successfully"
    }

    @Tool(description = "Get weather")
    fun get_weather(latitude: Double, longitude: Double): String = "Weather: 22°C, Sunny."
}

inline fun <reified Input : Any, reified Output : Any> launchIntent(
    context: Context,
    packageName: String,
    className: String,
    input: Input,
): Output = runBlocking {
    val stringOutput = MainActivity.intentLauncher.launch(context, packageName, className, serializer<Input>(), input)
    Json.decodeFromString(serializer<Output>(), stringOutput)
}
inline fun <reified Input : Any> launchIntentU(
    context: Context,
    packageName: String,
    className: String,
    input: Input,
): Unit = runBlocking {
    val stringOutput = MainActivity.intentLauncher.launch(context, packageName, className, serializer<Input>(), input)
    Json.decodeFromString(serializer<Unit>(), stringOutput)
}