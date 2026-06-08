package com.vayunmathur.openassistant.util
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.vayunmathur.library.intents.calendar.EventData
import com.vayunmathur.library.intents.contacts.ContactData
import com.vayunmathur.library.intents.findfamily.FamilyMemberData
import com.vayunmathur.library.intents.music.MusicSearchResult
import com.vayunmathur.library.intents.music.PlayMusicData
import com.vayunmathur.library.intents.email.EmailData
import com.vayunmathur.library.intents.email.EmailSearchQuery
import com.vayunmathur.library.intents.weather.WeatherData
import com.vayunmathur.openassistant.MainActivity
import com.vayunmathur.library.intents.notes.NoteData
import com.vayunmathur.openassistant.data.Memory
import com.vayunmathur.openassistant.data.MemoryDao
import com.vayunmathur.openassistant.data.MessageDao
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking

object JsonSchemaValidator {
    fun validateJsonAgainstSchema(jsonString: String, schemaString: String): String? {
        val json = try {
            Json.parseToJsonElement(jsonString)
        } catch (e: Exception) {
            return "Invalid JSON format: ${e.message}"
        }

        val schemaElement = try {
            Json.parseToJsonElement(schemaString)
        } catch (e: Exception) {
            Log.e("JsonSchemaValidator", "Internal Error: Schema itself is invalid JSON", e)
            return null
        }

        return performValidation(json, schemaElement)
    }

    fun trimJsonKeys(element: JsonElement): JsonElement {
        return when (element) {
            is JsonObject -> {
                val newMap = mutableMapOf<String, JsonElement>()
                element.forEach { (key, value) ->
                    newMap[key.trim()] = trimJsonKeys(value)
                }
                JsonObject(newMap)
            }
            is JsonArray -> {
                JsonArray(element.map { trimJsonKeys(it) })
            }
            else -> element
        }
    }

    private fun performValidation(data: JsonElement, schema: JsonElement, path: String = ""): String? {
        val pathPrefix = if (path.isEmpty()) "" else "at $path: "

        if (schema is JsonObject) {
            // Handle anyOf
            val anyOf = schema["anyOf"] as? JsonArray
            if (anyOf != null) {
                val errors = mutableListOf<String>()
                for (i in anyOf.indices) {
                    val error = performValidation(data, anyOf[i], path)
                    if (error == null) return null // Found a matching schema
                    errors.add("Option $i: $error")
                }
                return "Data does not match any of the allowed schemas in anyOf. Details: ${errors.joinToString("; ")}"
            }

            // Handle oneOf
            val oneOf = schema["oneOf"] as? JsonArray
            if (oneOf != null) {
                val matchingIndices = mutableListOf<Int>()
                val errors = mutableListOf<String>()
                for (i in oneOf.indices) {
                    val error = performValidation(data, oneOf[i], path)
                    if (error == null) {
                        matchingIndices.add(i)
                    } else {
                        errors.add("Option $i: $error")
                    }
                }
                if (matchingIndices.size == 1) return null // Exactly one matches
                return if (matchingIndices.isEmpty()) {
                    "Data does not match any of the allowed options in 'oneOf'. Details: ${errors.joinToString("; ")}"
                } else {
                    "Data matches MULTIPLE options in 'oneOf': $matchingIndices."
                }
            }

            // Handle not
            val notSchema = schema["not"]
            if (notSchema != null) {
                val error = performValidation(data, notSchema, path)
                if (error == null) {
                    return "Data matched the 'not' schema at $path, which is forbidden."
                }
            }

            // 1. Basic Type Check
            val expectedType = schema["type"]?.jsonPrimitive?.content
            if (expectedType == "object" && data !is JsonObject) {
                return "${pathPrefix}Expected an object but got ${data::class.simpleName}"
            }
            if (expectedType == "array" && data !is JsonArray) {
                return "${pathPrefix}Expected an array but got ${data::class.simpleName}"
            }

            // 2. Object Validation
            if (data is JsonObject) {
                val properties = schema["properties"] as? JsonObject
                
                // Unexpected Fields Check
                data.keys.forEach { key ->
                    if (properties == null || !properties.containsKey(key)) {
                        val fullPath = if (path.isEmpty()) key else "$path.$key"
                        return "Unexpected field found: '$fullPath'."
                    }
                }

                // Required Fields Check
                val required = schema["required"] as? JsonArray
                required?.forEach { req ->
                    val fieldName = req.jsonPrimitive.content
                    if (!data.containsKey(fieldName)) {
                        val fullPath = if (path.isEmpty()) fieldName else "$path.$fieldName"
                        return "Missing required field: '$fullPath'"
                    }
                }

                // Recurse into properties
                data.forEach { (key, value) ->
                    val propSchema = properties?.get(key)
                    if (propSchema != null) {
                        val fullPath = if (path.isEmpty()) key else "$path.$key"

                        if (propSchema is JsonObject) {
                            // Const check
                            val constValue = propSchema["const"]?.jsonPrimitive?.content
                            if (constValue != null && value.jsonPrimitive.content != constValue) {
                                return "Field '$fullPath' must be '$constValue' but got '${value.jsonPrimitive.content}'"
                            }

                            // Enum check
                            val enumValues = propSchema["enum"] as? JsonArray
                            if (enumValues != null) {
                                val allowed = enumValues.map { it.jsonPrimitive.content }
                                if (value.jsonPrimitive.content !in allowed) {
                                    return "Field '$fullPath' has invalid value '${value.jsonPrimitive.content}'. Allowed values: $allowed"
                                }
                            }
                        }
                        
                        // RECURSE
                        val nestedError = performValidation(value, propSchema, fullPath)
                        if (nestedError != null) return nestedError
                    }
                }
            }
            
            // 3. Array Validation
            if (data is JsonArray) {
                val itemSchema = schema["items"]
                if (itemSchema != null) {
                    data.forEachIndexed { index, element ->
                        val nestedError = performValidation(element, itemSchema, "$path[$index]")
                        if (nestedError != null) return nestedError
                    }
                }
            }
        }

        return null
    }
}

class AssistantToolSet(
    private val context: Context,
    private val memoryDao: MemoryDao? = null,
    private val messageDao: MessageDao? = null,
    private val conversationId: Long = -1L
) : ToolSet {

    companion object {
        fun getMissingAppMessage(packageName: String): String {
            val appName = when (packageName) {
                "com.vayunmathur.notes" -> "Notes"
                "com.vayunmathur.contacts" -> "Contacts"
                "com.vayunmathur.calendar" -> "Calendar"
                "com.vayunmathur.findfamily" -> "FindFamily"
                "com.vayunmathur.music" -> "Music"
                "com.vayunmathur.email" -> "Email"
                "com.vayunmathur.weather" -> "Weather"
                else -> packageName
            }
            return "The $appName app is required but not installed. [Download from GitHub](https://github.com/vayun-mathur/Modern-Apps)."
        }
    }

    private suspend fun handleMissingApp(packageName: String): String {
        Log.d("AssistantToolSet", "Handling missing app: $packageName")
        InferenceService.halt = true
        if (messageDao != null && conversationId != -1L) {
            Log.d("AssistantToolSet", "Inserting tool error message for $packageName")
            messageDao.upsert(com.vayunmathur.openassistant.data.Message(
                conversationId = conversationId,
                text = getMissingAppMessage(packageName),
                role = "tool",
                timestamp = Clock.System.now().toEpochMilliseconds()
            ))
            Log.d("AssistantToolSet", "Throwing StopInferenceException")
            throw StopInferenceException()
        }
        return getMissingAppMessage(packageName)
    }

    @Tool(description = "Get a list of all notes")
    fun get_notes(): String = runBlocking {
        try {
            val result: List<NoteData> = launchIntent(
                context,
                "com.vayunmathur.notes",
                "com.vayunmathur.notes.intents.GetIntent",
                Unit
            )
            result.toString()
        } catch (e: MissingAppException) { handleMissingApp(e.packageName) }
        catch (e: Exception) { "Error: ${e.message}" }
    }

    @Tool(description = "Create a new note in the notes app. Should only be used with EXPLICIT request by user")
    fun create_note(title: String, content: String): String = runBlocking {
        try {
            launchIntentU(
                context,
                "com.vayunmathur.notes",
                "com.vayunmathur.notes.intents.InsertIntent",
                NoteData(title, content)
            )
            "Success: Created note '$title'"
        } catch (e: MissingAppException) { handleMissingApp(e.packageName) }
        catch (e: Exception) { "Error: ${e.message}" }
    }

    @Tool(description = "Get a list of all contacts")
    fun get_contacts(): String = runBlocking {
        try {
            val result: List<ContactData> = launchIntent(
                context,
                "com.vayunmathur.contacts",
                "com.vayunmathur.contacts.intents.GetIntent",
                Unit
            )
            result.toString()
        } catch (e: MissingAppException) { handleMissingApp(e.packageName) }
        catch (e: Exception) { "Error: ${e.message}" }
    }

    @Tool(description = "Create a new contact")
    fun create_contact(name: String, phoneNumber: String): String = runBlocking {
        try {
            launchIntentU(
                context,
                "com.vayunmathur.contacts",
                "com.vayunmathur.contacts.intents.InsertIntent",
                ContactData(name, phoneNumber)
            )
            "Success: Created contact '$name'"
        } catch (e: MissingAppException) { handleMissingApp(e.packageName) }
        catch (e: Exception) { "Error: ${e.message}" }
    }

    @Tool(description = "Get a list of calendar events")
    fun get_calendar_events(): String = runBlocking {
        try {
            val result: List<EventData> = launchIntent(
                context,
                "com.vayunmathur.calendar",
                "com.vayunmathur.calendar.intents.GetIntent",
                Unit
            )
            result.toString()
        } catch (e: MissingAppException) { handleMissingApp(e.packageName) }
        catch (e: Exception) { "Error: ${e.message}" }
    }

    @Tool(description = "Create a new calendar event")
    fun create_calendar_event(title: String, start: Double, end: Double, location: String = ""): String = runBlocking {
        try {
            launchIntentU(
                context,
                "com.vayunmathur.calendar",
                "com.vayunmathur.calendar.intents.InsertIntent",
                EventData(title, start.toLong(), end.toLong(), location)
            )
            "Success: Created event '$title'"
        } catch (e: MissingAppException) { handleMissingApp(e.packageName) }
        catch (e: Exception) { "Error: ${e.message}" }
    }

    @Tool(description = "Get a list of family members and their current locations")
    fun get_family_locations(): String = runBlocking {
        try {
            val result: List<FamilyMemberData> = launchIntent(
                context,
                "com.vayunmathur.findfamily",
                "com.vayunmathur.findfamily.intents.GetIntent",
                Unit
            )
            result.toString()
        } catch (e: MissingAppException) { handleMissingApp(e.packageName) }
        catch (e: Exception) { "Error: ${e.message}" }
    }

    @Tool(description = "Search for music (songs, albums, artists, or playlists)")
    fun search_music(query: String): String = runBlocking {
        try {
            val result: List<MusicSearchResult> = launchIntent(
                context,
                "com.vayunmathur.music",
                "com.vayunmathur.music.intents.SearchIntent",
                query
            )
            result.toString()
        } catch (e: MissingAppException) { handleMissingApp(e.packageName) }
        catch (e: Exception) { "Error: ${e.message}" }
    }

    @Tool(description = "Play music given its id and type (song, album, artist, or playlist)")
    fun play_music(id: Double, type: String): String = runBlocking {
        try {
            launchIntentU(
                context,
                "com.vayunmathur.music",
                "com.vayunmathur.music.intents.PlayIntent",
                PlayMusicData(id.toLong(), type),
            )
            "Success: Playing music"
        } catch (e: MissingAppException) { handleMissingApp(e.packageName) }
        catch (e: Exception) { "Error: ${e.message}" }
    }

    @Tool(description = "Search emails by keyword")
    fun search_emails(query: String): String = runBlocking {
        try {
            val result: List<EmailData> = launchIntent(
                context,
                "com.vayunmathur.email",
                "com.vayunmathur.email.intents.SearchIntent",
                EmailSearchQuery(query)
            )
            result.toString()
        } catch (e: MissingAppException) { handleMissingApp(e.packageName) }
        catch (e: Exception) { Log.e("AssistantToolSet", "search_emails failed", e); "Error: ${e.message}" }
    }

    @Tool(description = "Get recent emails from the inbox")
    fun get_recent_emails(): String = runBlocking {
        try {
            val result: List<EmailData> = launchIntent(
                context,
                "com.vayunmathur.email",
                "com.vayunmathur.email.intents.GetRecentIntent",
                Unit
            )
            result.toString()
        } catch (e: MissingAppException) { handleMissingApp(e.packageName) }
        catch (e: Exception) { Log.e("AssistantToolSet", "get_recent_emails failed", e); "Error: ${e.message}" }
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
    fun open_app(@ToolParam(description = "package id") packageId: String): String = runBlocking {
        val intent = context.packageManager.getLaunchIntentForPackage(packageId)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Success: Opened $packageId"
        } else {
            handleMissingApp(packageId)
        }
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

    @Tool(description = "Get the current weather conditions at a specific latitude/longitude. Returns temperature, feels-like, condition, hi/lo, humidity, wind, UV, sunrise and sunset.")
    fun get_weather(latitude: Double, longitude: Double): String = runBlocking {
        try {
            val result: WeatherData = launchIntent(
                context,
                "com.vayunmathur.weather",
                "com.vayunmathur.weather.intents.GetWeatherIntent",
                WeatherLatLonRequest(latitude, longitude)
            )
            result.error ?: result.toString()
        } catch (e: MissingAppException) { handleMissingApp(e.packageName) }
        catch (e: Exception) { "Error: ${e.message}" }
    }

    @Tool(description = "Get the current weather conditions for a named place (city, address, landmark). Prefer this over get_weather when the user says a place name instead of coordinates.")
    fun get_weather_by_name(@ToolParam(description = "city or place name") location: String): String = runBlocking {
        try {
            val result: WeatherData = launchIntent(
                context,
                "com.vayunmathur.weather",
                "com.vayunmathur.weather.intents.GetWeatherByNameIntent",
                WeatherNameRequest(location)
            )
            result.error ?: result.toString()
        } catch (e: MissingAppException) { handleMissingApp(e.packageName) }
        catch (e: Exception) { "Error: ${e.message}" }
    }

    @Tool(description = "Get a list of all memories")
    fun get_memories(): String = runBlocking {
        if (memoryDao == null) return@runBlocking "Error: MemoryDao is null"
        try {
            val memories = memoryDao.getAll()
            memories.joinToString("\n") { "[${it.id}] ${it.content}" }
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    @Tool(description = "Remove a memory by its id")
    fun remove_memory(id: Double): String = runBlocking {
        if (memoryDao == null) return@runBlocking "Error: MemoryDao is null"
        try {
            memoryDao.deleteById(id.toLong())
            "Success: Removed memory with id ${id.toLong()}"
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    @Tool(description = "Add a new memory to the list of memories")
    fun add_to_memory(content: String): String = runBlocking {
        if (memoryDao == null) return@runBlocking "Error: MemoryDao is null"
        try {
            memoryDao.upsert(Memory(content))
            "Success: Added memory"
        } catch (e: Exception) { "Error: ${e.message}" }
    }
}

class MissingAppException(val packageName: String) : Exception("App $packageName is not installed.")
class StopInferenceException : Exception("STOP")

/**
 * Mirror of the input payload that [GetWeatherIntent] in the Weather app
 * expects. kotlinx.serialization is structural, so JSON written from here
 * deserializes into the other side as long as field names + types match.
 */
@kotlinx.serialization.Serializable
data class WeatherLatLonRequest(val latitude: Double, val longitude: Double)

/** Mirror of the [GetWeatherByNameIntent] input. */
@kotlinx.serialization.Serializable
data class WeatherNameRequest(val name: String)

inline fun <reified Input : Any, reified Output : Any> launchIntent(
    context: Context,
    packageName: String,
    className: String,
    input: Input
): Output = runBlocking {
    val stringOutput = MainActivity.intentLauncher.launch(context, packageName, className, serializer<Input>(), input)
    if (stringOutput == "package $packageName doesn't exist") {
        throw MissingAppException(packageName)
    }
    Json.decodeFromString(serializer<Output>(), stringOutput)
}
inline fun <reified Input : Any> launchIntentU(
    context: Context,
    packageName: String,
    className: String,
    input: Input
): Unit = runBlocking {
    val stringOutput = MainActivity.intentLauncher.launch(context, packageName, className, serializer<Input>(), input)
    if (stringOutput == "package $packageName doesn't exist") {
        throw MissingAppException(packageName)
    }
    Json.decodeFromString(serializer<Unit>(), stringOutput)
}

