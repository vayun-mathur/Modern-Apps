package com.vayunmathur.openassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavKey
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.IntentLauncher
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.openassistant.data.Conversation
import com.vayunmathur.openassistant.data.Message
import com.vayunmathur.openassistant.data.database.MessageDatabase
import com.vayunmathur.openassistant.ui.ConversationListScreen
import com.vayunmathur.openassistant.ui.ConversationScreen
import kotlinx.serialization.Serializable


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val db = buildDatabase<MessageDatabase>()
        val viewModel = DatabaseViewModel(db, Message::class to db.messageDao(), Conversation::class to db.conversationDao())
        val ds = DataStoreUtils.getInstance(this)
        intentLauncher = IntentLauncher(this)

        setContent {
            DynamicTheme {
                Navigation(viewModel, ds)
            }
        }
    }
}

lateinit var intentLauncher: IntentLauncher

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data class Conversation(val conversationID: Long) : Route
    @Serializable
    data object ConversationList : Route
}

@Composable
fun Navigation(viewModel: DatabaseViewModel, ds: DataStoreUtils) {
    val backStack = rememberNavBackStack(Route.ConversationList, Route.Conversation(0))
    MainNavigation(backStack) {
        entry<Route.ConversationList>(metadata = ListPage{
            ConversationScreen(backStack, viewModel, ds, 0)
        }) { ConversationListScreen(backStack, viewModel) }
        entry<Route.Conversation>(metadata = ListDetailPage()) { ConversationScreen(backStack, viewModel, ds, it.conversationID) }
    }
}