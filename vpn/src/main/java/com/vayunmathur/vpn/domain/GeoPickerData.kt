package com.vayunmathur.vpn.domain

import java.util.Locale

/** One selectable imported server, flattened for the geo picker (config id + bundle metadata). */
data class GeoServer(
    val configId: Long,
    val provider: String,
    val countryCode: String,
    val city: String,
    val name: String,
    val endpoint: String,
    /** Bundle freshness shared by every server of the provider; empty for legacy bundles. */
    val freshness: BundleFreshness = BundleFreshness.EMPTY,
    val bundleId: String = "",
)

/** One imported provider bundle, summarised for the picker header / update UI. */
data class GeoProvider(
    val provider: String,
    val providerId: String?,
    val bundleId: String,
    val freshness: BundleFreshness,
    val state: BundleFreshnessState,
    val serverCount: Int,
)

/** Distinct provider bundles in [servers], in provider-name order, classified at [nowEpochMs]. */
fun buildGeoProviders(
    servers: List<GeoServer>,
    nowEpochMs: Long = System.currentTimeMillis(),
    remoteRevisions: Map<String, Long?> = emptyMap(),
    failedBundleIds: Set<String> = emptySet(),
): List<GeoProvider> =
    servers.groupBy { it.bundleId.ifBlank { it.provider } }
        .map { (bundleKey, inBundle) ->
            val first = inBundle.first()
            GeoProvider(
                provider = first.provider,
                providerId = first.freshness.providerId,
                bundleId = bundleKey,
                freshness = first.freshness,
                state = classifyFreshness(
                    first.freshness, nowEpochMs,
                    remoteRevision = remoteRevisions[bundleKey],
                    checkFailed = bundleKey in failedBundleIds,
                ),
                serverCount = inBundle.size,
            )
        }
        .sortedBy { it.provider.lowercase(Locale.ROOT) }

/** A city and its servers, sorted by server name. */
data class GeoCity(
    val city: String,
    val servers: List<GeoServer>,
)

/** A country and its cities, sorted by localised country name then city. */
data class GeoCountry(
    val countryCode: String,
    val countryName: String,
    val flag: String,
    val cities: List<GeoCity>,
) {
    val serverCount: Int get() = cities.sumOf { it.servers.size }
}

/**
 * Groups imported servers into countries → cities → servers for the geo
 * picker. Pure: country names come from the platform [Locale] data, so the UI
 * gets localisation without a bundled country table.
 */
fun buildGeoCountries(servers: List<GeoServer>, locale: Locale = Locale.getDefault()): List<GeoCountry> =
    servers.groupBy { it.countryCode.uppercase(Locale.ROOT) }
        .map { (code, inCountry) ->
            GeoCountry(
                countryCode = code,
                countryName = countryDisplayName(code, locale),
                flag = flagEmoji(code),
                cities = inCountry.groupBy { it.city }
                    .map { (city, inCity) -> GeoCity(city, inCity.sortedBy { it.name.lowercase(locale) }) }
                    .sortedBy { it.city.lowercase(locale) },
            )
        }
        .sortedBy { it.countryName.lowercase(locale) }

/** Case-insensitive search over country, city, provider, server name and endpoint. */
fun filterGeoCountries(countries: List<GeoCountry>, query: String): List<GeoCountry> {
    val needle = query.trim().lowercase(Locale.ROOT)
    if (needle.isEmpty()) return countries
    return countries.mapNotNull { country ->
        val countryHit = needle in country.countryName.lowercase(Locale.ROOT) ||
            needle in country.countryCode.lowercase(Locale.ROOT)
        val cities = country.cities.mapNotNull { city ->
            val cityHit = countryHit || needle in city.city.lowercase(Locale.ROOT)
            val servers = city.servers.filter { server ->
                cityHit || needle in server.provider.lowercase(Locale.ROOT) ||
                    needle in server.name.lowercase(Locale.ROOT) ||
                    needle in server.endpoint.lowercase(Locale.ROOT)
            }
            if (servers.isEmpty()) null else GeoCity(city.city, servers)
        }
        if (cities.isEmpty()) null else country.copy(cities = cities)
    }
}

/** Flattens grouped countries back to their servers, in picker display order. */
fun List<GeoCountry>.flattenServers(): List<GeoServer> =
    flatMap { country -> country.cities.flatMap { it.servers } }
