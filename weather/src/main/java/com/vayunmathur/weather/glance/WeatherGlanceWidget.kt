package com.vayunmathur.weather.glance

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.Image
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import android.widget.RemoteViews
import androidx.glance.appwidget.AndroidRemoteViews
import com.vayunmathur.weather.R
import com.vayunmathur.library.room.buildDatabase
import com.vayunmathur.library.widgets.DynamicThemeGlance
import com.vayunmathur.weather.MainActivity
import com.vayunmathur.weather.data.WeatherDatabase
import com.vayunmathur.weather.data.weatherJson
import com.vayunmathur.weather.network.ForecastResponse
import com.vayunmathur.weather.util.roundCoord
import com.vayunmathur.weather.util.weatherConditionForCode
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Clock

/**
 * Snapshot of cached weather used by the 4x1 widget. Populated from the
 * most-recent [com.vayunmathur.weather.data.WeatherCache] row for the
 * user's primary saved location (preferring the device-location row).
 * `null` while no forecast has been cached yet — the widget renders a
 * dash in that case rather than failing to load.
 */
data class WidgetWeather(
    val temperatureCelsius: Double,
    val weatherCode: Int,
    val isDay: Boolean,
)

class WeatherGlanceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val weather = loadWeatherSnapshot(context)

        provideContent {
            DynamicThemeGlance(context) {
                Content(weather)
            }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent {
            DynamicThemeGlance(context) {
                Content(WidgetWeather(temperatureCelsius = 22.0, weatherCode = 1, isDay = true))
            }
        }
    }

    private suspend fun loadWeatherSnapshot(context: Context): WidgetWeather? {
        return try {
            val db = context.buildDatabase<WeatherDatabase>(dbName = "weather-db")
            val dao = db.weatherDao()
            // Prefer the device-current row, fall back to the first pinned location.
            val location = dao.getCurrentDeviceLocation()
                ?: dao.getLocations().firstOrNull()
                ?: return null
            val cache = dao.getCache(
                roundCoord(location.latitude),
                roundCoord(location.longitude),
            ) ?: return null
            val forecast = runCatching {
                weatherJson.decodeFromString<ForecastResponse>(cache.forecastJson)
            }.getOrNull() ?: return null
            val current = forecast.current ?: return null
            WidgetWeather(
                temperatureCelsius = current.temperature,
                weatherCode = current.weatherCode,
                isDay = current.isDay != 0,
            )
        } catch (e: Exception) {
            null
        }
    }

}

private val PARAM_PACKAGE = ActionParameters.Key<String>("package")
private val PARAM_CLASS = ActionParameters.Key<String>("class")
private val PARAM_FALLBACK = ActionParameters.Key<String>("fallback")

/**
 * Launches the preferred sibling app ([PARAM_PACKAGE]/[PARAM_CLASS]) and, if it
 * isn't installed/visible, falls back to a system intent ([PARAM_FALLBACK]).
 * Using a try/catch around `startActivity` here is robust without a `<queries>`
 * manifest entry, which `Intent.resolveActivity` would otherwise require on
 * modern Android (it returns null for unqueryable packages).
 */
class LaunchAppAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val pkg = parameters[PARAM_PACKAGE]
        val cls = parameters[PARAM_CLASS]
        val explicit = Intent().apply {
            if (pkg != null && cls != null) setClassName(pkg, cls)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val fallback = when (parameters[PARAM_FALLBACK]) {
            "calendar" -> Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_CALENDAR) }
            else -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
        }.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        try {
            context.startActivity(explicit)
        } catch (e: Exception) {
            runCatching { context.startActivity(fallback) }
        }
    }
}

@Composable
private fun Content(weather: WidgetWeather?) {
    val context = LocalContext.current
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .cornerRadius(20.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left column: time stacked over date, each independently clickable.
        Column(
            modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.Start,
        ) {
            Box(
                modifier = GlanceModifier
                    .clickable(
                        actionRunCallback<LaunchAppAction>(
                            actionParametersOf(
                                PARAM_PACKAGE to "com.vayunmathur.clock",
                                PARAM_CLASS to "com.vayunmathur.clock.MainActivity",
                                PARAM_FALLBACK to "alarms",
                            )
                        )
                    ),
            ) { TimeBlock(context) }
            Box(
                modifier = GlanceModifier
                    .padding(top = 2.dp)
                    .clickable(
                        actionRunCallback<LaunchAppAction>(
                            actionParametersOf(
                                PARAM_PACKAGE to "com.vayunmathur.calendar",
                                PARAM_CLASS to "com.vayunmathur.calendar.MainActivity",
                                PARAM_FALLBACK to "calendar",
                            )
                        )
                    ),
            ) { DateBlock(now) }
        }
        // Right: large weather icon + temperature, clickable into this app.
        Box(
            modifier = GlanceModifier.defaultWeight().fillMaxHeight()
                .padding(start = 8.dp)
                .clickable(actionStartActivity(Intent(LocalContext.current, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })),
            contentAlignment = Alignment.CenterEnd,
        ) { WeatherBlock(weather) }
    }
}

@Composable
private fun TimeBlock(context: Context) {
    val remoteViews = RemoteViews(context.packageName, R.layout.widget_text_clock)
    AndroidRemoteViews(remoteViews)
}

@Composable
private fun DateBlock(now: LocalDateTime) {
    val dateFormat = LocalDateTime.Format {
        monthName(MonthNames.ENGLISH_ABBREVIATED); char(' '); day(Padding.NONE)
    }
    Text(
        text = now.format(dateFormat),
        style = TextStyle(
            color = GlanceTheme.colors.onSurface,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
        ),
    )
}

@Composable
private fun WeatherBlock(weather: WidgetWeather?) {
    if (weather == null) {
        Text(
            text = "—",
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        return
    }
    val condition = weatherConditionForCode(weather.weatherCode)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            provider = ImageProvider(condition.iconRes(weather.isDay)),
            contentDescription = condition.label,
            modifier = GlanceModifier.size(44.dp),
        )
        Text(
            text = "  ${weather.temperatureCelsius.roundToInt()}°",
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}
