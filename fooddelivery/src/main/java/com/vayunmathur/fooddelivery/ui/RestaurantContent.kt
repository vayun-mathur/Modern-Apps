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
import com.vayunmathur.library.ui.AppScaffold
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

/**
 * The menu, with no API call of its own so it can be rendered from a `@Preview` — see
 * `src/screenshotTest`, which is where the store listing images come from.
 */
@OptIn(FlowPreview::class)
@Composable
fun RestaurantContent(
    merchant: MerchantDetail?,
    loading: Boolean = false,
    rewards: MerchantRewards? = null,
    onJoinLoyalty: (String) -> Unit = {},
    onLeaveLoyalty: () -> Unit = {},
    onBack: () -> Unit = {},
    onAddItem: (MenuItem, List<SelectedModifier>) -> Unit = { _, _ -> },
) {
    var customizeItem by remember { mutableStateOf<MenuItem?>(null) }
    var query by remember { mutableStateOf("") }
    // The field itself stays instant; the menu is only re-filtered once typing settles, so a
    // keystroke no longer re-walks and re-sorts every category.
    var appliedQuery by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        snapshotFlow { query }.debounce(FILTER_DEBOUNCE_MS).collect { appliedQuery = it }
    }

    customizeItem?.let { item ->
        ModifierDialog(
            item = item,
            onDismiss = { customizeItem = null },
            onConfirm = { selectedModifiers ->
                onAddItem(item, selectedModifiers)
                customizeItem = null
            }
        )
    }

    AppScaffold(topBar = {}) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (merchant == null) {
            EmptyState(
                title = stringResource(R.string.restaurant_not_found),
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
            val m = merchant
            val q = appliedQuery.trim()
            // Rebuilding the id index and the filtered menu on every recomposition is what made
            // typing in the search field lag, so both are kept until their inputs change.
            val itemsById = remember(m.items) { m.items.associateBy { it.id } }
            val activeCategories = remember(m.categories) {
                m.categories.filter { it.isActive }.sortedBy { it.sortOrder }
            }
            val sections = remember(m.items, m.categories, q) {
                activeCategories.mapNotNull { category ->
                    val categoryItems = category.itemIds.mapNotNull { itemsById[it] }
                        .filter {
                            it.isAvailable && it.isInStock && (q.isEmpty() ||
                                it.name.contains(q, ignoreCase = true) ||
                                it.description.contains(q, ignoreCase = true))
                        }
                    if (categoryItems.isEmpty()) null else category to categoryItems
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier.padding(padding)
            ) {
                if (m.imageUrl.isNotEmpty()) {
                    item {
                        Box {
                            AsyncImage(
                                model = m.imageUrl,
                                contentDescription = m.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                                    .sharedContainer("food-merchant-${m.id}")
                            )
                            IconButton(onClick = onBack,
                                modifier = Modifier.padding(4.dp)) {
                                IconBack()
                            }
                        }
                    }
                } else {
                    item {
                        IconButton(onClick = onBack,
                            modifier = Modifier.padding(4.dp)) {
                            IconBack()
                        }
                    }
                }
                item {
                    Column(Modifier.padding(16.dp)) {
                        Text(m.name, style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.sharedText("food-merchant-name-${m.id}"))
                        if (m.merchantTags.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(m.merchantTags.joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.sharedText("food-merchant-tags-${m.id}"))
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            if ((m.averageRating ?: 0.0) > 0) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconStar(Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(2.dp))
                                    Text("%.1f (%d)".format(m.averageRating, m.totalRatings ?: 0),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (m.nextOpenWindow.isNotEmpty()) {
                                Text(m.nextOpenWindow, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            if ((m.rewardsPercentage ?: 0.0) > 0) {
                                Text(stringResource(R.string.back, (m.rewardsPercentage?.toInt()).toString()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }

                item {
                    MerchantRewardsCard(
                        rewards = rewards,
                        onJoin = onJoinLoyalty,
                        onLeave = onLeaveLoyalty,
                    )
                }

                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.search_menu)) },
                        singleLine = true,
                        leadingIcon = { IconSearch() },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) { IconClose() }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                if (sections.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.no_menu_items_match, q),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }

                sections.forEach { (category, categoryItems) ->
                    item {
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(category.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp))
                        Spacer(Modifier.height(8.dp))
                    }
                    // An item can sit in more than one category, so the key has to include the
                    // category to stay unique across the whole list.
                    items(categoryItems, key = { "${category.id}-${it.id}" }) { menuItem ->
                        MenuItemRow(menuItem) {
                            if (menuItem.modifierGroups.isNotEmpty()) {
                                customizeItem = menuItem
                            } else {
                                onAddItem(menuItem, emptyList())
                            }
                        }
                    }
                }
            }
        }
    }
}
