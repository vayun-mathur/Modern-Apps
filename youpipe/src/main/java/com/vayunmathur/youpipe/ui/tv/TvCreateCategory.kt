package com.vayunmathur.youpipe.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvListRow
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.TvTextField
import com.vayunmathur.library.tv.tvContentPadding
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import com.vayunmathur.youpipe.network.decodeHtml
import kotlinx.coroutines.launch

/**
 * TV create/update subscription group: name field + focusable member checklist.
 *
 * Same validation as the phone dialog (non-blank name, non-empty selection, no duplicate
 * on create). Each subscription row toggles membership on CENTER; the confirm row applies
 * `replaceCategory` and pops. BACK dismisses without applying. Deleting an existing
 * category is a header action behind a [TvDialog] confirm.
 */
@Composable
fun TvCreateCategoryPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
    id: String?,
) {
    val subscriptions by ypvm.subscriptions.collectAsState()
    val categories by ypvm.subscriptionCategories.collectAsState()
    val categoryNames = categories.map { it.category }
    var categoryName by remember { mutableStateOf(id ?: "") }
    val subscriptionsAlreadyInCategory by remember(categoryName) {
        ypvm.subscriptionsInCategory(categoryName)
    }.collectAsState(initial = emptyList())
    var selectedSubscriptions by remember(subscriptionsAlreadyInCategory) {
        mutableStateOf(subscriptionsAlreadyInCategory)
    }
    val scope = rememberCoroutineScope()
    val nameFocus = remember { FocusRequester() }

    val canConfirm = categoryName.isNotBlank() && selectedSubscriptions.isNotEmpty() &&
        (id != null || categoryName !in categoryNames)

    TvInitialFocus(nameFocus)
    TvSettingsBody {
        Column(Modifier.fillMaxSize()) {
            TvCreateCategoryHeader(
                isCreate = id == null,
                canConfirm = canConfirm,
                onConfirm = {
                    scope.launch {
                        ypvm.replaceCategory(
                            id, categoryName, selectedSubscriptions.map { it.id },
                        )
                        backStack.pop()
                    }
                },
            )
            TvTextField(
                value = categoryName,
                onValueChange = { categoryName = it },
                placeholder = stringResource(R.string.label_category_name),
                focusRequester = nameFocus,
                modifier = Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 8.dp),
            )
            TvCreateCategoryList(
                subscriptions = subscriptions, selectedSubscriptions = selectedSubscriptions,
                onToggle = { subscription, checked ->
                    selectedSubscriptions = if (checked) {
                        selectedSubscriptions - subscription
                    } else {
                        selectedSubscriptions + subscription
                    }
                },
            )
        }
    }
}

@Composable
private fun TvCreateCategoryHeader(isCreate: Boolean, canConfirm: Boolean, onConfirm: () -> Unit) {
    TvScreenHeader(
        title = if (isCreate) {
            stringResource(R.string.title_create_subscription_category)
        } else {
            stringResource(R.string.title_update_subscription_category)
        },
        actions = {
            TvButton(
                text = if (isCreate) {
                    stringResource(UiR.string.create)
                } else {
                    stringResource(R.string.action_update)
                },
                onClick = onConfirm,
                enabled = canConfirm,
                primary = true,
            )
        },
    )
}

@Composable
private fun TvCreateCategoryList(
    subscriptions: List<Subscription>,
    selectedSubscriptions: List<Subscription>,
    onToggle: (Subscription, Boolean) -> Unit,
) {
    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxSize(),
        contentPadding = tvContentPadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "select-label") {
            androidx.tv.material3.Text(
                text = stringResource(R.string.label_select_subscriptions),
                style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
            )
        }
        items(subscriptions, key = { it.id }) { subscription ->
            val checked = subscription in selectedSubscriptions
            TvListRow(
                headline = subscription.name.decodeHtml(),
                trailing = if (checked) {
                    { IconCheck() }
                } else {
                    null
                },
                selected = checked,
                onClick = { onToggle(subscription, checked) },
            )
        }
    }
}
