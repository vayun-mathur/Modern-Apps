package com.vayunmathur.vpn.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.ScaffoldSearch
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.vpn.R
import com.vayunmathur.vpn.Route
import com.vayunmathur.vpn.domain.filterGeoCountries
import com.vayunmathur.vpn.domain.flattenServers
import com.vayunmathur.vpn.platform.VpnViewModel
import com.vayunmathur.vpn.ui.components.GeoServerRow
import com.vayunmathur.vpn.ui.components.rememberServerActivation

/**
 * Geo picker root (`Route.GeoPicker`): countries from imported `.mavpn`
 * bundles, with in-screen search. Drilling into a country is navigation
 * ([Route.GeoCountry]); tapping a search-hit server activates it through the
 * existing tunnel path, and tapping its card opens its normal detail route.
 */
@Composable
fun GeoPickerPage(backStack: NavBackStack<Route>, vm: VpnViewModel) {
    val countries by vm.geoCountries.collectAsState()
    val configs by vm.configs.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = filterGeoCountries(countries, query)
    val activate = rememberServerActivation(vm)

    LazyListScaffold(
        title = stringResource(R.string.geo_picker_title),
        search = ScaffoldSearch(
            query = query,
            onQueryChange = { query = it },
            placeholder = stringResource(R.string.geo_search_hint),
        ),
        horizontalPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            countries.isEmpty() -> item { Text(stringResource(R.string.geo_no_bundles)) }
            filtered.isEmpty() -> item { Text(stringResource(R.string.geo_no_matches)) }
            query.isNotBlank() -> items(filtered.flattenServers(), key = { it.configId }) { server ->
                val config = configs.firstOrNull { it.id == server.configId }
                GeoServerRow(
                    server = server,
                    onConnect = { if (config != null) activate(config) },
                    onOpen = { backStack.add(Route.Detail(server.configId)) },
                )
            }
            else -> items(filtered, key = { it.countryCode }) { country ->
                Card(
                    Modifier.fillMaxWidth().clickable { backStack.add(Route.GeoCountry(country.countryCode)) },
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        "${country.flag} ${country.countryName}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 2.dp),
                    )
                    Text(
                        stringResource(R.string.geo_server_count, country.serverCount),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 12.dp),
                    )
                }
            }
        }
    }
}
