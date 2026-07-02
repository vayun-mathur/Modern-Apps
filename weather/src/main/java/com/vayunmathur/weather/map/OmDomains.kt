package com.vayunmathur.weather.map

/**
 * Grid geometry for an Open-Meteo spatial model, mirroring the relevant fields
 * of `weather-map-layer/src/domains.ts`. A regular lat/lon grid: node `(j, i)`
 * sits at `lat = latMin + dy*j`, `lon = lonMin + dx*i`, with the `.om` array
 * stored `[ny, nx]`.
 */
data class OmDomain(
    /** Model id used in the `data_spatial/<model>/…` bucket paths. */
    val model: String,
    val nx: Int,
    val ny: Int,
    val lonMin: Double,
    val latMin: Double,
    val dx: Double,
    val dy: Double,
)

/**
 * v1 model: DWD ICON global, a regular 0.125° lat/lon grid covering the whole
 * globe. Chosen because it exposes the direct variables we shade (temperature,
 * humidity, precipitation, gusts, pressure, cloud cover) plus the u/v wind
 * components we derive wind speed from. Projected/Gaussian grids and
 * multi-model selection are follow-ups.
 */
val DwdIconGlobal = OmDomain(
    model = "dwd_icon",
    nx = 2879,
    ny = 1441,
    lonMin = -180.0,
    latMin = -90.0,
    dx = 0.125,
    dy = 0.125,
)
