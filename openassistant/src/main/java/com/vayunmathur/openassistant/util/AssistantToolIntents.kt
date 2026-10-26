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

class MissingAppException(val packageName: String) : Exception("App $packageName is not installed.")

@kotlinx.serialization.Serializable
data class WeatherLatLonRequest(val latitude: Double, val longitude: Double)

@kotlinx.serialization.Serializable
data class WeatherNameRequest(val name: String)

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
