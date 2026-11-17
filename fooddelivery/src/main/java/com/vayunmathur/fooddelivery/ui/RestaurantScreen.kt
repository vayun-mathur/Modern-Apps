package com.vayunmathur.fooddelivery.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.fooddelivery.R
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.RadioButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.util.sharedContainer
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.fooddelivery.network.BitesCustomers
import com.vayunmathur.fooddelivery.network.BitesMerchants
import com.vayunmathur.fooddelivery.network.BitesRewards
import com.vayunmathur.fooddelivery.data.CartItem
import com.vayunmathur.fooddelivery.data.MerchantDetail
import com.vayunmathur.fooddelivery.data.MenuItem
import com.vayunmathur.fooddelivery.data.MerchantRewards
import com.vayunmathur.fooddelivery.data.SelectedModifier
import com.vayunmathur.fooddelivery.platform.AppInit
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** How long typing has to settle before the menu is re-filtered. */
internal const val FILTER_DEBOUNCE_MS = 150L

@Composable
fun RestaurantScreen(
    merchantId: Int,
    onBack: () -> Unit,
    onAddToCart: (CartItem) -> Unit,
) {
    var merchant by remember { mutableStateOf<MerchantDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var rewards by remember { mutableStateOf<MerchantRewards?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(merchantId) {
        AppInit.awaitReady()
        merchant = BitesMerchants.getMerchantDetail(merchantId)
        loading = false
        rewards = BitesRewards.getCustomerMerchantRewards().firstOrNull { it.merchantId == merchantId }
    }

    RestaurantContent(
        merchant = merchant,
        loading = loading,
        rewards = rewards,
        onJoinLoyalty = { code ->
            scope.launch {
                if (BitesCustomers.createCustomerMerchantLoyalty(code, merchantId)) {
                    rewards = BitesRewards.getCustomerMerchantRewards().firstOrNull { it.merchantId == merchantId }
                }
            }
        },
        onLeaveLoyalty = {
            scope.launch {
                if (BitesCustomers.deleteCustomerMerchantLoyalty(merchantId)) {
                    rewards = BitesRewards.getCustomerMerchantRewards().firstOrNull { it.merchantId == merchantId }
                }
            }
        },
        onBack = onBack,
        onAddItem = { item, selectedModifiers ->
            onAddToCart(CartItem(
                menuItem = item,
                merchantId = merchantId,
                merchantName = merchant?.name ?: "",
                selectedModifiers = selectedModifiers
            ))
        },
    )
}


/**
 * Pick modifiers for [item]. Pass [initialSelection] to reopen it over an existing choice
 * (editing a cart line) instead of starting empty.
 * Lives in ModifierDialog.kt.
 */

// MenuItemRow + MerchantRewardsCard live in RestaurantSections.kt
