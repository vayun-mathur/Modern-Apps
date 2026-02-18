package com.vayunmathur.openassistant.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import androidx.core.text.util.LocalePreferences
import com.vayunmathur.library.intents.notes.InsertNoteData
import com.vayunmathur.openassistant.intentLauncher
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

typealias ToolFunctionType = suspend (Map<String, JsonElement>, Context) -> ToolResult

class Tools {
    companion object {
        val client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json()
            }
        }
        @SuppressLint("QueryPermissionsNeeded")
        @OptIn(ExperimentalTime::class)
        val ALL_TOOLS = listOf(
            ToolSimple("get_weather", "Get the current weather (full data) for a specific location", listOf(
                    numberParam("latitude", "the latitude of the location"),
                    numberParam("longitude", "the longitude of the location"),
                )
            ) { args, context ->
                println(args)
                val latitude = args["latitude"]
                val longitude = args["longitude"]


                val locale = context.resources.configuration.locales[0]
                val isMetric = locale.country.let { it == "US" || it == "LR" || it == "MM" }.not()


                val temperatureUnit = when(LocalePreferences.getTemperatureUnit()) {
                    LocalePreferences.TemperatureUnit.CELSIUS -> "celsius"
                    LocalePreferences.TemperatureUnit.FAHRENHEIT -> "fahrenheit"
                    else -> if(isMetric) "celsius" else "fahrenheit"
                }

                if (latitude == null || longitude == null) {
                    val response = Json.encodeToString(mapOf("error" to "latitude and longitude parameters are required"))
                    ToolResult(response, response)
                } else {
                    try {
                        val weather = client.get("https://api.open-meteo.com/v1/forecast?current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,rain,showers,snowfall,weather_code,cloud_cover,pressure_msl,surface_pressure,wind_speed_10m,wind_direction_10m,wind_gusts_10m") {
                            parameter("latitude", latitude.jsonPrimitive.double)
                            parameter("longitude", longitude.jsonPrimitive.double)
                            parameter("temperature_unit", temperatureUnit)
                            if(isMetric) {
                                parameter("wind_speed_unit", "kmh")
                                parameter("precipitation_unit", "mm")
                            } else {
                                parameter("wind_speed_unit", "mph")
                                parameter("precipitation_unit", "inch")
                            }
                        }.bodyAsText()
                        ToolResult("$weather: The user would like their responses using the units requested and returned by the response. Other units may be used in parenthesis.", "Got the weather forecast.")
                    } catch (e: Exception) {
                        val response = Json.encodeToString(mapOf("error" to "Unable to fetch weather data: ${e.message}"))
                        ToolResult(response, response)
                    }
                }
            },
            ToolSimple("get_local_current_date_time", "Get the current date and time in the local timezone", listOf()) { _, _ ->
                val result = "${TimeZone.currentSystemDefault()}: ${Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())}"
                ToolResult(result, "Got the current date and time.")
            },
            ToolSimple("get_list_of_apps", "Get a list of installed apps on the device", listOf()) { _, context ->
                val pm = context.packageManager
                val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                val appInfos = apps.map {
                    mapOf(
                        "name" to it.loadLabel(pm).toString(),
                        "package_name" to it.packageName
                    )
                }
                ToolResult(Json.encodeToString(appInfos), "Got the list of installed apps.")
            },
            ToolSimple("open_app", "Open an app given its package id", listOf(
                stringParam("package_id", "the package id of the app to open")
            )) { args, context ->
                val packageId = args["package_id"]?.jsonPrimitive?.content
                if (packageId == null) {
                    val response = Json.encodeToString(mapOf("error" to "package_id parameter is required"))
                    ToolResult(response, response)
                } else {
                    val intent = context.packageManager.getLaunchIntentForPackage(packageId)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        ToolResult(Json.encodeToString(mapOf("success" to "app opened")), "Opened $packageId")
                    } else {
                        val response = Json.encodeToString(mapOf("error" to "App not found"))
                        ToolResult(response, response)
                    }
                }
            },
            ToolSimple("send_message", "Send a message to a recipient", listOf(
                stringParam("recipient", "the phone number of the recipient"),
                stringParam("message", "the content of the message")
            )) { args, context ->
                val recipient = args["recipient"]?.jsonPrimitive?.content
                val message = args["message"]?.jsonPrimitive?.content
                if (recipient == null || message == null) {
                    val response = Json.encodeToString(mapOf("error" to "recipient and message parameters are required"))
                    ToolResult(response, response)
                } else {
                    try {
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = "smsto:$recipient".toUri()
                            putExtra("sms_body", message)
                        }
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        ToolResult(Json.encodeToString(mapOf("success" to "message intent sent")), "Opened messaging app to send a message to $recipient.")
                    } catch (e: Exception) {
                        val response = Json.encodeToString(mapOf("error" to "Could not send message: ${e.message}"))
                        ToolResult(response, "Could not send message")
                    }
                }
            },
            ToolSimple("make_phone_call", "Make a phone call to a recipient", listOf(
                stringParam("recipient", "the phone number of the recipient")
            )) { args, context ->
                val recipient = args["recipient"]?.jsonPrimitive?.content
                if (recipient == null) {
                    val response = Json.encodeToString(mapOf("error" to "recipient parameter is required"))
                    ToolResult(response, response)
                } else {
                    try {
                        val intent = Intent(Intent.ACTION_DIAL).apply {
                            data = "tel:$recipient".toUri()
                        }
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        ToolResult(Json.encodeToString(mapOf("success" to "dialer opened")), "Opened dialer to call $recipient.")
                    } catch (e: Exception) {
                        val response = Json.encodeToString(mapOf("error" to "Could not open dialer: ${e.message}"))
                        ToolResult(response, "Could not open dialer")
                    }
                }
            },
            ToolSimple("get_notes", "Get a list of notes (and their content) on the user's phone", listOf()) { _, _ ->
                ToolResult(intentLauncher.launch("com.vayunmathur.notes", "com.vayunmathur.notes.intents.GetIntent", Unit::class, Unit), "Retrieved notes")
            },
            ToolSimple("insert_note", "Add a note to the user's phone", listOf(
                stringParam("title", "the title of the note"),
                stringParam("content", "the content of the note")
            )) { args, _ ->
                val input = InsertNoteData(args["title"]!!.jsonPrimitive.content, args["content"]!!.jsonPrimitive.content)
                ToolResult(intentLauncher.launch("com.vayunmathur.notes", "com.vayunmathur.notes.intents.InsertIntent", InsertNoteData::class, input), "Added notes")
            }
        )

        val API_TOOLS = ALL_TOOLS.map { it.toTool() }

        fun getToolAction(name: String): ToolFunctionType? {
            return ALL_TOOLS.find { it.name == name }?.action
        }
    }
}
