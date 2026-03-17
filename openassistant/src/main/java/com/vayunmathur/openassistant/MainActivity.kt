package com.vayunmathur.openassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.InitialDownloadChecker
import com.vayunmathur.library.ui.ListPage
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val ds = DataStoreUtils.getInstance(this)
        val db = buildDatabase<AppDatabase>()
        val viewModel = DatabaseViewModel(db, Conversation::class to db.conversationDao(), Message::class to db.messageDao())

        setContent {
            DynamicTheme {
                InitialDownloadChecker(ds, listOf(
                    Triple("https://huggingface.co/na5h13/gemma-3n-E2B-it-litert-lm/resolve/main/gemma-3n-E2B-it-int4.litertlm", "model.litertlm", "Model"),
                )) {
                    // Once downloads are complete, find the file in internal storage
                    Navigation(viewModel)
                }
            }
        }
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object ListPage: Route
    @Serializable
    data class ConversationPage(val id: Long): Route
}

@Composable
fun Navigation(viewModel: DatabaseViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.ListPage)
    MainNavigation(backStack) {
        entry<Route.ListPage>() {
            ConversationListPage(backStack, viewModel)
        }
        entry<Route.ConversationPage>() {
            LiteRTChatUi(backStack, it.id, viewModel)
        }
    }
}

@Composable
fun ConversationListPage(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel) {
    ListPage<Conversation, Route, Route.ConversationPage>(backStack, viewModel, "Open Assistant", {
        Text(it.title)
    }, {}, {
        Route.ConversationPage(it)
    }, {
        Route.ConversationPage(0)
    })
}