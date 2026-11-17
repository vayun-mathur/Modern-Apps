package com.vayunmathur.vpn.domain

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoPickerDataTest {
    private val servers = listOf(
        GeoServer(3, "Example", "US", "New York", "Example — New York", "nyc.example:51820"),
        GeoServer(1, "Example", "DE", "Frankfurt", "Example — Frankfurt", "fra.example:51820"),
        GeoServer(2, "Example", "DE", "Berlin", "Example — Berlin", "ber.example:51820"),
        GeoServer(4, "Other", "DE", "Frankfurt", "Other — Frankfurt", "fra2.example:51820"),
    )

    @Test fun flagsFromCountryCodes() {
        assertEquals("🇩🇪", flagEmoji("DE"))
        assertEquals("🇺🇸", flagEmoji("us"))
        assertEquals("X", flagEmoji("X"))
        assertEquals("Germany", countryDisplayName("DE", Locale.US))
        assertEquals("United States", countryDisplayName("US", Locale.US))
    }

    @Test fun groupsCountriesCitiesServersSorted() {
        val countries = buildGeoCountries(servers, Locale.US)
        assertEquals(listOf("Germany", "United States"), countries.map { it.countryName })
        val germany = countries.first()
        assertEquals("🇩🇪", germany.flag)
        assertEquals(3, germany.serverCount)
        assertEquals(listOf("Berlin", "Frankfurt"), germany.cities.map { it.city })
        assertEquals(
            listOf("Example — Frankfurt", "Other — Frankfurt"),
            germany.cities.last().servers.map { it.name },
        )
    }

    @Test fun importedServersFlattenInPickerOrder() {
        val countries = buildGeoCountries(servers, Locale.US)
        assertEquals(listOf(2L, 1L, 4L, 3L), countries.flattenServers().map { it.configId })
    }

    @Test fun searchMatchesCountryCityProviderAndEndpoint() {
        val countries = buildGeoCountries(servers, Locale.US)
        assertEquals(listOf(3L), filterGeoCountries(countries, "new york").flattenServers().map { it.configId })
        assertEquals(listOf(4L), filterGeoCountries(countries, "other").flattenServers().map { it.configId })
        assertEquals(listOf(1L), filterGeoCountries(countries, "fra.example").flattenServers().map { it.configId })
        assertEquals(3, filterGeoCountries(countries, "germany").flattenServers().size)
        assertEquals(countries, filterGeoCountries(countries, "  "))
        assertTrue(filterGeoCountries(countries, "zzz").isEmpty())
    }
}
