package com.vayunmathur.vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.vpn.Route
import com.vayunmathur.vpn.platform.VpnViewModel
import com.vayunmathur.vpn.ui.components.GeoServerRow
import com.vayunmathur.vpn.ui.components.rememberServerActivation

/**
 * Geo picker city (`Route.GeoCity`): its servers, each showing its endpoint.
 * Selecting a server activates that config through the existing tunnel path;
 * tapping its card opens the normal tunnel detail route.
 */
@Composable
fun GeoCityPage(backStack: NavBackStack<Route>, vm: VpnViewModel, countryCode: String, city: String) {
    val countries by vm.geoCountries.collectAsState()
    val configs by vm.configs.collectAsState()
    val activate = rememberServerActivation(vm)
    val geoCity = countries.firstOrNull { it.countryCode == countryCode }
        ?.cities?.firstOrNull { it.city == city }
    LazyListScaffold(
        title = city,
        horizontalPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        scrollBehavior = appBarScrollBehavior(),
    ) {
        if (geoCity == null) {
            item { Text(city) }
        } else {
            items(geoCity.servers, key = { it.configId }) { server ->
                val config = configs.firstOrNull { it.id == server.configId }
                GeoServerRow(
                    server = server,
                    onConnect = { if (config != null) activate(config) },
                    onOpen = { backStack.add(Route.Detail(server.configId)) },
                )
            }
        }
    }
}
