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
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.sharedContainer
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.fooddelivery.network.BitesMerchants
import com.vayunmathur.fooddelivery.data.AddressStore
import com.vayunmathur.fooddelivery.data.Merchant
import com.vayunmathur.fooddelivery.platform.AppInit

@Composable
fun HomeScreen(onMerchantClick: (Int) -> Unit) {
    val context = LocalContext.current
    var merchants by remember { mutableStateOf<List<Merchant>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var noAddress by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // The saved token is restored by the background warm-up; wait for it so a signed-in
        // session isn't asked for the catalog anonymously.
        AppInit.awaitReady()
        val defaultAddr = AddressStore.getDefault(context)
        if (defaultAddr != null) {
            merchants = BitesMerchants.getMerchants(
                lat = defaultAddr.latitude,
                lng = defaultAddr.longitude,
            )
                .sortedBy { it.distance ?: Double.MAX_VALUE }
            noAddress = false
        } else {
            noAddress = true
        }
        loading = false
    }

    HomeContent(
        merchants = merchants,
        loading = loading,
        noAddress = noAddress,
        onMerchantClick = onMerchantClick,
    )
}


@Composable
internal fun MerchantCard(merchant: Merchant, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column {
            if (merchant.displayImage.isNotEmpty()) {
                AsyncImage(
                    model = merchant.displayImage,
                    contentDescription = merchant.name,
                    contentScale = ContentScale.Crop,
                    // Keyed on imageUrl, not displayImage: the restaurant header draws that same
                    // server field and draws nothing when it is empty, so the brand fallback shown
                    // here in that case has no counterpart to travel to.
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .then(
                            if (merchant.imageUrl.isEmpty()) Modifier
                            else Modifier.sharedContainer("food-merchant-${merchant.id}")
                        )
                )
            }
            Column(Modifier.padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        if (merchant.displayLogo.isNotEmpty()) {
                            AsyncImage(
                                model = merchant.displayLogo,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp).clip(CircleShape)
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Column {
                            Text(merchant.name, fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.sharedText("food-merchant-name-${merchant.id}"))
                            if (merchant.merchantTags.isNotEmpty()) {
                                Text(
                                    merchant.merchantTags.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.sharedText("food-merchant-tags-${merchant.id}")
                                )
                            }
                        }
                    }
                    if (merchant.displayRating > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconStar(modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(2.dp))
                            Text("%.1f".format(merchant.displayRating),
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (!merchant.isOpen) {
                    Spacer(Modifier.height(4.dp))
                    Text(merchant.closingTime.ifEmpty { "Closed" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error)
                } else if (merchant.nextOpenWindow.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(merchant.nextOpenWindow,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (merchant.distance != null) {
                        Text("%.1f mi".format(merchant.distance),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (merchant.address.isNotEmpty()) {
                        Text(merchant.addressCity.ifEmpty { merchant.address },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if ((merchant.doordashMarkup ?: 0) > 0) {
                        val saving = ((merchant.doordashMarkupComparison ?: 0) - (merchant.doordashMarkup ?: 0)) / 100.0
                        if (saving > 0) {
                            Text("Save $%.0f vs DoorDash".format(saving),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (merchant.displayRewardsPercentage > 0) {
                        Text(stringResource(R.string.back, merchant.displayRewardsPercentage.toInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
