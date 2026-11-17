package com.vayunmathur.youpipe.ui.dialogs
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.youpipe.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateSubscriptionCategory(backStack: NavBackStack<Route>, youPipeViewModel: YouPipeViewModel, id: String?) {
    val subscriptions by youPipeViewModel.subscriptions.collectAsState()
    val categories by youPipeViewModel.subscriptionCategories.collectAsState()
    val categoryNames = categories.map { it.category }
    var categoryName by remember { mutableStateOf(id?:"") }
    val subscriptionsAlreadyInCategory by remember(categoryName) {
        youPipeViewModel.subscriptionsInCategory(categoryName)
    }.collectAsState(initial = emptyList())
    var selectedSubscriptions by remember(subscriptionsAlreadyInCategory) { mutableStateOf(subscriptionsAlreadyInCategory) }
    val coroutineScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    Dialog({backStack.pop()}) {
        Card {
            Column(Modifier.padding(16.dp)) {
                if(id == null)
                    Text(stringResource(R.string.title_create_subscription_category), style = MaterialTheme.typography.titleLarge)
                else
                    Text(stringResource(R.string.title_update_subscription_category), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(categoryName, {categoryName = it}, label = {Text(stringResource(R.string.label_category_name))})
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.label_select_subscriptions))
                LazyColumn(Modifier.weight(1f)) {
                    items(subscriptions, key = { it.id }) {subscription ->
                        ListItem(trailingContent = {
                        Checkbox(subscription in selectedSubscriptions, {
                                selectedSubscriptions = if(subscription in selectedSubscriptions)
                                    selectedSubscriptions - subscription
                                else
                                    selectedSubscriptions + subscription
                            })
                        }, leadingContent = {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(subscription.avatarURL)
                                    .memoryCacheKey("sub-avatar-${subscription.id}")
                                    .build(),
                                contentDescription = null,
                                Modifier.size(24.dp).clip(CircleShape)
                            )
                        }) {
                            Text(subscription.name)
                        }
                    }
                }
                Button(
                    {
                        coroutineScope.launch {
                            youPipeViewModel.replaceCategory(
                                id,
                                categoryName,
                                selectedSubscriptions.map { it.id })
                            backStack.pop()
                        }
                    },
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    enabled = categoryName.isNotBlank() && selectedSubscriptions.isNotEmpty() && (id != null || categoryName !in categoryNames)
                ) {
                    if(id == null)
                        Text(stringResource(UiR.string.create))
                    else
                        Text(stringResource(R.string.action_update))
                }
            }
        }
    }
}
