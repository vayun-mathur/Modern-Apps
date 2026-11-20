package com.vayunmathur.weather.network

import java.util.Locale
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Boundary mapper: self-hosted [WxBundle] → the existing
 * [ForecastResponse]/[AirQualityResponse] types.
 *
 * Epoch→local-ISO formatting reproduces exactly the naive-local strings
 * the domain layer consumes (`yyyy-MM-dd'T'HH:mm` hourly/current/sun/moon,
 * `yyyy-MM-dd` daily) using the bundle's own `utcOffset` — so
 * [com.vayunmathur.weather.domain.SelectedData] string-matching,
 * `MetricSeries`, the [com.vayunmathur.weather.platform.precipitationNowcast]
 * nowcast, and `todayIndex` behave identically. Nothing downstream of
 * `network/` changes.
 *
 * Missing `pprob` passes through as-is (the server always sends the §4.6
 * heuristic today). Pollen collapses to grass/tree/weed at the display
 * layer, unchanged.
 */

private fun epochToNaiveLocal(epochSec: Long, utcOffsetSec: Int): String {
    // Shift by the location's offset, then format the wall clock — exactly
    // the naive-local strings the domain layer consumes.
    val ldt = Instant.fromEpochSeconds(epochSec + utcOffsetSec)
        .toLocalDateTime(TimeZone.UTC)
    val d = ldt.date
    val t = ldt.time
    return String.format(
        Locale.US, "%04d-%02d-%02dT%02d:%02d",
        d.year, d.monthNumber, d.dayOfMonth, t.hour, t.minute,
    )
}


/** Daily section of the bundle mapped to the forecast shape, or null without daily data. */
private fun WxBundle.dailyResponse(off: Int): Daily? {
    val d = daily
    // Daily `start` is the first LOCAL date; entries are consecutive days.
    // Add k days with date arithmetic (DST-safe; avoids 86400 assumptions).
    val firstDate: kotlinx.datetime.LocalDate? = runCatching {
        kotlinx.datetime.LocalDate.parse(d?.start ?: "")
    }.getOrNull()
    val startDate = firstDate ?: kotlinx.datetime.LocalDate(2000, 1, 1)
    // UV gaps (past the CAMS horizon) map to NaN; consumers use getOrNull +
    // takeIf(isFinite) fallbacks (see SelectedData), mirroring how the map
    // colorizer already treats NaN as missing.
    return if (d == null || firstDate == null) null else Daily(
        time = List(d.count) { k ->
            val date = kotlinx.datetime.LocalDate.fromEpochDays(startDate.toEpochDays() + k)
            String.format(Locale.US, "%04d-%02d-%02d", date.year, date.monthNumber, date.dayOfMonth)
        },
        weatherCode = d.code,
        temperatureMax = d.tmax,
        temperatureMin = d.tmin,
        apparentTemperatureMax = d.feelsMax,
        apparentTemperatureMin = d.feelsMin,
        sunrise = d.sunrise.map { epochToNaiveLocal(it, off) },
        sunset = d.sunset.map { epochToNaiveLocal(it, off) },
        daylightDuration = d.daylight.map { it.toDouble() },
        sunshineDuration = d.sunshine.map { it.toDouble() },
        uvIndexMax = d.uvMax,
        precipitationProbabilityMax = d.pprobMax,
        precipitationSum = d.precipSum,
        moonPhase = d.moonPhase,
        moonrise = d.moonrise.map { it?.let { e -> epochToNaiveLocal(e, off) } },
        moonset = d.moonset.map { it?.let { e -> epochToNaiveLocal(e, off) } },
    )
}
/** `WxBundle` → [ForecastResponse] (mapped shape, cache-compatible). */
fun WxBundle.toForecastResponse(): ForecastResponse {
    val off = utcOffset
    val h = hourly
    val hourlyResp = if (h == null) null else Hourly(
        time = List(h.count) { k -> epochToNaiveLocal(h.start + k * h.step, off) },
        temperature = h.temp,
        apparentTemperature = h.feels,
        relativeHumidity = h.rh,
        dewPoint = h.dew,
        weatherCode = h.code,
        precipitationProbability = h.pprob,
        precipitation = h.precip,
        windSpeed = h.windSpd,
        windDirection = h.windDir,
        pressureMsl = h.pres,
        visibility = h.vis,
        cloudCover = h.cloud,
        windGusts = h.gust,
        uvIndex = h.uv,
        isDay = h.day,
    )
    val dailyResp = dailyResponse(off)
    val m = min15
    val min15Resp = if (m == null) null else Minutely15(
        time = List(m.count) { k -> epochToNaiveLocal(m.start + k * m.step, off) },
        precipitation = m.precip,
    )
    val c = current
    val currentResp = if (c == null) null else Current(
        time = epochToNaiveLocal(c.time, off),
        temperature = c.temp,
        apparentTemperature = c.feels,
        relativeHumidity = c.rh,
        dewPoint = c.dew,
        weatherCode = c.code,
        windSpeed = c.windSpd,
        windDirection = c.windDir,
        pressureMsl = c.pres,
        visibility = c.vis,
        cloudCover = c.cloud,
        windGusts = c.gust,
        isDay = c.day,
    )
    return ForecastResponse(
        latitude = lat,
        longitude = lon,
        timezone = tz,
        timezoneAbbreviation = tzAbbr,
        utcOffsetSeconds = off,
        current = currentResp,
        hourly = hourlyResp,
        daily = dailyResp,
        minutely15 = min15Resp,
    )
}

/** `WxBundle` → [AirQualityResponse]? (null when the `aq` section is absent). */
fun WxBundle.toAirQuality(): AirQualityResponse? {
    val a = aq ?: return null
    // Server sends current-hourestamp; the display layer parses `time` as a
    // naive-local string like today's DTO.
    return AirQualityResponse(
        current = AirQualityCurrent(
            time = epochToNaiveLocal(a.time, utcOffset),
            usAqi = a.usAqi,
            alderPollen = a.alder,
            birchPollen = a.birch,
            grassPollen = a.grass,
            mugwortPollen = a.mugwort,
            olivePollen = a.olive,
            ragweedPollen = a.ragweed,
        ),
    )
}

/** `RegionTimezone` from a tz-only bundle (envelope-only body). */
fun WxBundle.toRegionTimezone(): RegionTimezone = RegionTimezone(
    timezone = tz,
    abbreviation = tzAbbr,
    utcOffsetSeconds = utcOffset,
)
