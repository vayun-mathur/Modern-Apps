package com.vayunmathur.weather.widget
import com.vayunmathur.weather.network.ForecastResponse
import com.vayunmathur.weather.domain.roundCoord
import com.vayunmathur.weather.data.weatherJson
import com.vayunmathur.weather.data.WeatherRepository
import com.vayunmathur.library.log.Log
import com.vayunmathur.weather.data.WeatherRefreshWorker
import com.vayunmathur.library.widgets.MaWidgetReceiver

import com.vayunmathur.library.util.DateNameStyle
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDefaults.defaultTextStyle
import androidx.glance.text.TextStyle
import com.vayunmathur.library.util.localizedMonthNames
import com.vayunmathur.library.widgets.MaWidget
import com.vayunmathur.weather.MainActivity
import com.vayunmathur.weather.R
import com.vayunmathur.weather.domain.celsiusTo
import com.vayunmathur.weather.domain.weatherConditionForCode
import com.vayunmathur.weather.platform.systemTempUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Clock

/** Weather Glance Widget: a ma widget implementation for weather glance widget. */
open class WeatherGlanceWidget : MaWidget<WidgetWeather?>() {
    override suspend fun loadData(context: Context, id: GlanceId): WidgetWeather? =
        loadWeatherSnapshot(context)

    override fun previewData(context: Context): WidgetWeather? = null

    @Composable
    override fun Content(data: WidgetWeather?) {
        WeatherContent(data)
    }

    @Composable
    override fun PreviewContent() {
        WeatherPreviewContent()
    }
}

@Composable
private fun WeatherContent(weather: WidgetWeather?) {
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
                modifier = GlanceModifier.clickable(
                    actionStartActivity(
                        Intent().apply {
                            setClassName(
                                "com.vayunmathur.clock",
                                "com.vayunmathur.clock.MainActivity",
                            )
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    ),
                ),
            ) { TimeBlock(context) }
            Box(
                modifier = GlanceModifier
                    .padding(top = 2.dp)
                    .clickable(
                        actionStartActivity(
                            Intent().apply {
                                setClassName(
                                    "com.vayunmathur.calendar",
                                    "com.vayunmathur.calendar.MainActivity",
                                )
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        ),
                    ),
            ) { DateBlock(now) }
        }
        // Right: large weather icon + temperature, clickable into this app.
        Box(
            modifier = GlanceModifier.defaultWeight().fillMaxHeight()
                .padding(start = 8.dp)
                .clickable(
                    actionStartActivity(
                        Intent(LocalContext.current, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    ),
                ),
            contentAlignment = Alignment.CenterEnd,
        ) { WeatherBlock(weather) }
    }
}

/** Preview-safe content – avoids AndroidRemoteViews/TextClock which crashes setWidgetPreviews on API 35+ */
@Composable
private fun WeatherPreviewContent() {
    // Static sample, no RemoteViews, no system attribute resolution.
    val previewWeather = WidgetWeather(temperatureCelsius = 22.0, weatherCode = 1, isDay = true)
    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .cornerRadius(20.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = "9:41",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                text = "Jul 6",
                modifier = GlanceModifier.padding(top = 2.dp),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
        Box(
            modifier = GlanceModifier.defaultWeight().fillMaxHeight()
                .padding(start = 8.dp),
            contentAlignment = Alignment.CenterEnd,
        ) { WeatherBlock(previewWeather) }
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
        monthName(MonthNames(localizedMonthNames(DateNameStyle.SHORT))); char(' '); day(Padding.NONE)
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
    val context = LocalContext.current
    if (weather == null) {
        Text(
            text = context.getString(R.string.weather_no_data),
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
            contentDescription = context.getString(condition.label),
            modifier = GlanceModifier.size(44.dp),
        )
        Text(
            text = context.getString(
                R.string.weather_temp_format,
                weather.temperatureCelsius.celsiusTo(systemTempUnit()).roundToInt(),
            ),
            modifier = GlanceModifier.padding(start = 8.dp),
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

/** Weather Glance Widget Receiver: a ma widget receiver implementation for weather glance widget receiver. */
class WeatherGlanceWidgetReceiver : MaWidgetReceiver<WeatherGlanceWidget>(
    widgetClass = WeatherGlanceWidget::class,
    receiverClass = WeatherGlanceWidgetReceiver::class,
    widgetFactory = ::WeatherGlanceWidget,
) {
    override fun onEnabledExtra(context: Context) {
        WeatherRefreshWorker.scheduleHourlyRefresh(context)
    }
}

/**
 * Snapshot of cached weather used by the widgets. Populated from the
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

// Broad catch is deliberate: a snapshot miss renders as a dash, and Room plus
// kotlinx.serialization throw undocumented RuntimeExceptions (not just IOException).
@Suppress("TooGenericExceptionCaught")
internal suspend fun loadWeatherSnapshot(context: Context): WidgetWeather? {
    return try {
        val repo = WeatherRepository.get(context)
        // Prefer the device-current row, fall back to the first pinned location.
        val location = repo.getCurrentDeviceLocation()
            ?: repo.getLocations().firstOrNull()
            ?: return null
        val cache = repo.getCache(
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
        Log.dev("WeatherWidget", "no cached snapshot yet", e)
        null
    }
}
