package com.vayunmathur.youpipe.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.navigation3.runtime.NavBackStack
import coil.compose.AsyncImage
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.pop
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.data.SubscriptionCategory
import com.vayunmathur.youpipe.data.SubscriptionCategoryDao
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateSubscriptionCategory(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel, id: String?) {
    val categoriesDao = viewModel.getDaoInterface<SubscriptionCategory>().dao as SubscriptionCategoryDao
    val subscriptions by viewModel.data<Subscription>().collectAsState()
    val categories by viewModel.data<SubscriptionCategory>().collectAsState()
    val categoryNames = categories.map { it.category }
    var categoryName by remember { mutableStateOf(id?:"") }
    val subscriptionsAlreadyInCategory = categories.filter { it.category == categoryName }.map { it.subscriptionID }.map { id -> subscriptions.first { it.id == id } }
    var selectedSubscriptions by remember { mutableStateOf(subscriptionsAlreadyInCategory) }
    val coroutineScope = rememberCoroutineScope()
    Dialog({backStack.pop()}) {
        Card() {
            Column(Modifier.padding(16.dp)) {
                if(id == null)
                    Text("Create subscription category", style = MaterialTheme.typography.titleLarge)
                else
                    Text("Update subscription category", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(categoryName, {categoryName = it}, label = {Text("Category name")})
                Spacer(Modifier.height(8.dp))
                Text("Select subscriptions:")
                LazyColumn(Modifier.weight(1f)) {
                    items(subscriptions) {subscription ->
                        ListItem({
                            Text(subscription.name)
                        }, trailingContent = {
                            Checkbox(subscription in selectedSubscriptions, {checked ->
                                selectedSubscriptions = if(subscription in selectedSubscriptions)
                                    selectedSubscriptions - subscription
                                else
                                    selectedSubscriptions + subscription
                            })
                        }, leadingContent = {
                            AsyncImage(
                                model = subscription.avatarURL,
                                contentDescription = null,
                                Modifier.size(24.dp).clip(CircleShape)
                            )
                        })
                    }
                }
                Button(
                    {
                        coroutineScope.launch {
                            categoriesDao.replaceCategory(
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
                        Text("Create")
                    else
                        Text("Update")
                }
            }
        }
    }
}