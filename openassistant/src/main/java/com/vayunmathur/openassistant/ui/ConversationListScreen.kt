package com.vayunmathur.openassistant.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.library.ui.ListPage
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.openassistant.Route
import com.vayunmathur.openassistant.data.Conversation

@Composable
fun ConversationListScreen(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel) {
    ListPage<Conversation, Route, Route.Conversation>(backStack, viewModel, "Conversations", {
        Text(it.title)
    }, {}, { Route.Conversation(it) }, {Route.Conversation(0)})
}