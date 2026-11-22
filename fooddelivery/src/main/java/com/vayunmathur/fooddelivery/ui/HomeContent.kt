package com.vayunmathur.fooddelivery.ui
import androidx.compose.ui.res.stringResource
import com.vayunmathur.fooddelivery.R
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.LoadingIndicator
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.sharedContainer
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.fooddelivery.network.BitesMerchants
import com.vayunmathur.fooddelivery.data.AddressStore
import com.vayunmathur.fooddelivery.data.Merchant
import com.vayunmathur.fooddelivery.platform.AppInit

/**
 * The nearby-restaurant list, with no API call or address lookup of its own so it can be
 * rendered from a `@Preview` — see `src/screenshotTest`, which is where the store listing
 * images come from.
 */
@Composable
fun HomeContent(
    merchants: List<Merchant>,
    loading: Boolean = false,
    noAddress: Boolean = false,
    onMerchantClick: (Int) -> Unit = {},
) {
    AppScaffold(topBar = {}) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
        } else if (noAddress) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconHome(modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.set_your_address), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.add_an_address_in_account_to_see_nearby),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (merchants.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.no_restaurants_found), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.check_your_connection_or_try_again_later),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 300.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(padding)
            ) {
                // Deliberately unkeyed: /merchants/all/stores can return several store rows
                // per merchant and Merchant has no id-independent identity, so a key risks the
                // duplicate-key crash. It would buy nothing anyway — `merchants` is assigned
                // once and never replaced while the grid is on screen.
                items(merchants) { merchant ->
                    MerchantCard(merchant) { onMerchantClick(merchant.id) }
                }
            }
        }
    }
}
