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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.vpn.Route
import com.vayunmathur.vpn.platform.VpnViewModel

/** Geo picker country (`Route.GeoCountry`): its cities; tapping a city is navigation. */
@Composable
fun GeoCountryPage(backStack: NavBackStack<Route>, vm: VpnViewModel, countryCode: String) {
    val countries by vm.geoCountries.collectAsState()
    val country = countries.firstOrNull { it.countryCode == countryCode }
    LazyListScaffold(
        title = if (country != null) "${country.flag} ${country.countryName}" else countryCode,
        horizontalPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        scrollBehavior = appBarScrollBehavior(),
    ) {
        if (country == null) {
            item { Text(countryCode) }
        } else {
            items(country.cities, key = { it.city }) { city ->
                Card(
                    Modifier.fillMaxWidth()
                        .clickable { backStack.add(Route.GeoCity(countryCode, city.city)) },
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        city.city,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 2.dp),
                    )
                    Text(
                        "${city.servers.size}",
                        fontSize = 12.sp,
                        modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 12.dp),
                    )
                }
            }
        }
    }
}
