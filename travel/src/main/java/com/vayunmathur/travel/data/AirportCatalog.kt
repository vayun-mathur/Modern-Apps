package com.vayunmathur.travel.data

import android.content.Context
import com.vayunmathur.travel.network.AircraftDto
import com.vayunmathur.travel.network.AirlineDto
import com.vayunmathur.travel.network.AirportDto
import com.vayunmathur.travel.network.CityDto
import com.vayunmathur.travel.network.PlaceDto
import com.vayunmathur.travel.network.StaySuggestionDto
import java.text.Normalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One searchable reference row: an airport (`type == "airport"`) or a
 * multi-airport metro (`type == "city"`). Plain data with no Android
 * dependency so ranking stays unit-testable on the JVM.
 */
data class CatalogPlaceRow(
    val code: String,
    val name: String,
    val city: String,
    val country: String,
    val type: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/** Lowercase + strip diacritics so "sao" matches "São Paulo". */
internal fun foldText(s: String): String {
    val lower = s.lowercase()
    val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
    return decomposed.filter { it.category != CharCategory.NON_SPACING_MARK }
}

private fun nameTokens(name: String): List<String> =
    foldText(name).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

/**
 * Pure ranking over in-memory [rows]: IATA exact first (airports, then
 * metros), then metro-code prefix, airport-code prefix, city prefix, airport/
 * city name token prefix, and finally country-name prefix. Within a tier,
 * metros come before airports and ties break by code, so results are
 * deterministic. Dedupes by code (first occurrence wins) and honors [limit].
 * Returns empty for queries shorter than 2 characters.
 */
fun rankPlaces(query: String, rows: List<CatalogPlaceRow>, limit: Int = 8): List<CatalogPlaceRow> {
    val trimmed = query.trim()
    if (trimmed.length < 2) return emptyList()
    val exact = trimmed.uppercase()
    val folded = foldText(trimmed)
    val airports = rows.filter { it.type == "airport" }
    val metros = rows.filter { it.type != "airport" }
    // Later tiers prefer metros (a metro code is the better autocomplete
    // pick), with codes sorting ties.
    val metroFirst = (metros + airports).sortedWith(compareBy({ it.type == "airport" }, { it.code }))
    val out = LinkedHashMap<String, CatalogPlaceRow>()
    fun add(row: CatalogPlaceRow) {
        out.putIfAbsent(row.code.uppercase(), row)
    }
    airports.filter { it.code.equals(exact, ignoreCase = true) }.sortedBy { it.code }.forEach(::add)
    metros.filter { it.code.equals(exact, ignoreCase = true) }.sortedBy { it.code }.forEach(::add)
    metros.filter {
        !it.code.equals(exact, ignoreCase = true) && it.code.startsWith(exact, ignoreCase = true)
    }.sortedBy { it.code }.forEach(::add)
    airports.filter { it.code.startsWith(exact, ignoreCase = true) }.sortedBy { it.code }.forEach(::add)
    metroFirst.filter { foldText(it.city).startsWith(folded) }.forEach(::add)
    metroFirst.filter { row -> nameTokens(row.name).any { it.startsWith(folded) } }.forEach(::add)
    metroFirst.filter { foldText(it.country).startsWith(folded) }.forEach(::add)
    return out.values.take(limit)
}

private fun CatalogPlaceRow.toPlaceDto() = PlaceDto(
    code = code,
    name = name,
    city = city,
    country = country,
    type = type,
)

/** Fully loaded snapshot; null until [AirportCatalog.ensureLoaded] runs. */
private data class CatalogSnapshot(
    val rows: List<CatalogPlaceRow>,
    val airlines: List<AirlineDto>,
    val airlineByCode: Map<String, String>,
    val aircraftByCode: Map<String, String>,
    val cities: List<CityDto>,
    val cityByCode: Map<String, String>,
    val airportByCode: Map<String, AirportDto>,
    val metroCoordsByCode: Map<String, Pair<Double, Double>>,
    val airportCoordsByCode: Map<String, Pair<Double, Double>>,
)

/**
 * Offline reference data bundled as TSV files in `assets/` (see
 * `analysis/gen_travel_assets.py`): airports, multi-airport metros, countries,
 * airlines and aircraft. Loads once, lazily, off the main thread; call
 * [ensureLoaded] with the application context before the getters (the
 * `TravelViewModel` ops do this). Getters return empty/fallback values when
 * not yet loaded and never throw.
 *
 * Reuses the network DTO shapes ([PlaceDto], [AirlineDto], [AircraftDto],
 * [CityDto], [AirportDto]) with empty logo URLs, so callers are unchanged.
 */
object AirportCatalog {

    private val mutex = Mutex()

    @Volatile
    private var snapshot: CatalogSnapshot? = null

    /** True once the TSVs have been loaded into memory. */
    val isLoaded: Boolean
        get() = snapshot != null

    /**
     * Load the TSVs from assets on first call (idempotent and thread-safe).
     * Must be called with the application context before [search]/getters;
     * subsequent calls are cheap.
     */
    suspend fun ensureLoaded(appContext: Context) {
        if (snapshot != null) return
        mutex.withLock {
            if (snapshot != null) return
            val assets = appContext.applicationContext.assets
            snapshot = withContext(Dispatchers.IO) { loadSnapshot(assets) }
        }
    }

    /**
     * Airport/city autocomplete for [query] (blank/short queries yield empty).
     * Airports carry `type == "airport"`, metros `type == "city"`.
     */
    suspend fun search(query: String, limit: Int = 8): List<PlaceDto> {
        val rows = snapshot?.rows ?: return emptyList()
        return rankPlaces(query, rows, limit).map { it.toPlaceDto() }
    }

    /** Location suggestions for the stays search box, with coordinates. */
    suspend fun staySuggestions(query: String, limit: Int = 8): List<StaySuggestionDto> {
        val rows = snapshot?.rows ?: return emptyList()
        return rankPlaces(query, rows, limit).map { row ->
            val label = if (row.type == "airport") {
                "${row.name} (${row.code})"
            } else {
                "${row.city}, ${row.country}"
            }
            StaySuggestionDto(
                name = label,
                kind = row.type,
                latitude = row.latitude,
                longitude = row.longitude,
            )
        }
    }

    /** Reference data for a single airport by IATA code, or null if unknown. */
    fun airport(code: String): AirportDto? {
        val key = code.trim().uppercase()
        if (key.isEmpty()) return null
        return snapshot?.airportByCode?.get(key)
    }

    /** Airlines sorted by name (logo URLs are empty offline). */
    fun airlines(): List<AirlineDto> = snapshot?.airlines ?: emptyList()

    /** Airline name for a 2-letter IATA code, falling back to the code. */
    fun airlineName(iata: String): String {
        val key = iata.trim().uppercase()
        return snapshot?.airlineByCode?.get(key)?.takeIf { it.isNotBlank() } ?: iata
    }

    /** Aircraft list for label lookups. */
    fun aircraft(): List<AircraftDto> =
        snapshot?.aircraftByCode?.map { (code, name) ->
            AircraftDto(id = code, name = name, iataCode = code)
        } ?: emptyList()

    /** Human aircraft name for an IATA aircraft code, falling back to the code. */
    fun aircraftName(code: String): String {
        val key = code.trim().uppercase()
        return snapshot?.aircraftByCode?.get(key)?.takeIf { it.isNotBlank() } ?: code
    }

    /** Metro cities backing the city reference list, sorted by name. */
    fun cities(): List<CityDto> = snapshot?.cities ?: emptyList()

    /** City name for a metro code, falling back to the code. */
    fun cityName(code: String): String {
        val key = code.trim().uppercase()
        return snapshot?.cityByCode?.get(key)?.takeIf { it.isNotBlank() } ?: code
    }

    /**
     * Coordinates for a metro or airport code (metros first: city-centre
     * coordinates suit the stays search), or null if unknown.
     */
    fun coordinates(code: String): Pair<Double, Double>? {
        val key = code.trim().uppercase()
        if (key.isEmpty()) return null
        val snap = snapshot ?: return null
        return snap.metroCoordsByCode[key] ?: snap.airportCoordsByCode[key]
    }

    private fun assetsReader(assets: android.content.res.AssetManager, name: String): List<List<String>> =
        runCatching {
            assets.open(name).bufferedReader().useLines { seq ->
                seq.drop(1).map { line -> line.split('\t') }.toList()
            }
        }.getOrDefault(emptyList())


    /** Airlines, aircraft names and city DTOs from their TSV snapshots. */
    private fun loadAirlinesAircraftCities(
        assets: android.content.res.AssetManager,
        metroCells: List<List<String>>,
    ): Triple<List<AirlineDto>, Map<String, String>, List<CityDto>> {
    val airlines = assetsReader(assets, "airlines.tsv")
        .filter { it.size >= 2 && it[0].isNotBlank() && it[1].isNotBlank() }
        .distinctBy { it[0].uppercase() }
        .map { cols -> AirlineDto(name = cols[1], iataCode = cols[0].uppercase()) }
        .sortedBy { it.name }

    val aircraft = assetsReader(assets, "aircraft.tsv")
        .filter { it.size >= 2 && it[0].isNotBlank() && it[1].isNotBlank() }
        .distinctBy { it[0].uppercase() }
        .associate { it[0].uppercase() to it[1] }

    val metros = metroCells.map { cols ->
        Triple(cols[0].uppercase(), cols[1], cols[2].uppercase())
    }
    val cities = metros
        .map { (code, city, countryCode) ->
            CityDto(name = city, iataCode = code, iataCountryCode = countryCode)
        }
        .sortedBy { it.name }
        return Triple(airlines, aircraft, cities)
    }

    /** Metro (city) rows, skipping codes a real airport already covers. */
    private fun loadMetroRows(
        assets: android.content.res.AssetManager,
        airportCodes: Set<String>,
        countryName: (String) -> String,
    ): Pair<List<CatalogPlaceRow>, List<List<String>>> {
    // A metro code that collides with a real airport IATA (e.g. IST, BKK,
    // BER) is skipped: the airport row already covers that code.
    val metroCells = assetsReader(assets, "metros.tsv").filter { it.size >= 5 }
    val metroRows = metroCells
        .filter { !airportCodes.contains(it[0].uppercase()) }
        .map { cols ->
            val city = cols[1]
            CatalogPlaceRow(
                code = cols[0].uppercase(),
                name = city,
                city = city,
                country = countryName(cols[2].uppercase()),
                type = "city",
                latitude = cols[3].toDoubleOrNull(),
                longitude = cols[4].toDoubleOrNull(),
            )
        }
        .sortedBy { it.code }
        return metroRows to metroCells
    }
    private fun loadSnapshot(assets: android.content.res.AssetManager): CatalogSnapshot {
        val countries = assetsReader(assets, "countries.tsv")
            .filter { it.size >= 2 }
            .associate { it[0] to it[1] }
        val countryName: (String) -> String = { code ->
            countries[code]?.takeIf { it.isNotBlank() } ?: code
        }

        val airportCells = assetsReader(assets, "airports.tsv")
            .filter { it.size >= 6 && it[0].length == 3 }
        val airportRows = airportCells
            .map { cols ->
                CatalogPlaceRow(
                    code = cols[0].uppercase(),
                    name = cols[1],
                    city = cols[2],
                    country = countryName(cols[3].uppercase()),
                    type = "airport",
                    latitude = cols[4].toDoubleOrNull(),
                    longitude = cols[5].toDoubleOrNull(),
                )
            }
            .sortedBy { it.code }
        val airportCodes = airportRows.map { it.code }.toSet()

        val (metroRows, metroCells) = loadMetroRows(assets, airportCodes, countryName)
        val metros = metroCells.map { cols ->
            Triple(cols[0].uppercase(), cols[1], cols[2].uppercase())
        }
        val (airlines, aircraft, cities) = loadAirlinesAircraftCities(assets, metroCells)
        val airportByCode = airportRows.associate { row ->
            row.code to AirportDto(
                iataCode = row.code,
                name = row.name,
                cityName = row.city,
                iataCountryCode = "",
                latitude = row.latitude,
                longitude = row.longitude,
            )
        }
        // AirportDto.iataCountryCode needs the raw 2-letter code, which the
        // display row already folded into the country name; recover it from
        // the raw TSV cells.
        val countryByAirport = airportCells.associate { it[0].uppercase() to it[3].uppercase() }
        val airportsWithCountry = airportByCode.mapValues { (code, dto) ->
            dto.copy(iataCountryCode = countryByAirport[code].orEmpty())
        }

        fun coordsOf(rows: List<CatalogPlaceRow>): Map<String, Pair<Double, Double>> =
            rows.mapNotNull { row ->
                val lat = row.latitude
                val lon = row.longitude
                if (lat != null && lon != null) row.code to (lat to lon) else null
            }.toMap()

        return CatalogSnapshot(
            rows = metroRows + airportRows,
            airlines = airlines,
            airlineByCode = airlines.associate { it.iataCode to it.name },
            aircraftByCode = aircraft,
            cities = cities,
            cityByCode = metros.associate { (code, city, _) -> code to city },
            airportByCode = airportsWithCountry,
            metroCoordsByCode = coordsOf(metroRows),
            airportCoordsByCode = coordsOf(airportRows),
        )
    }
}
