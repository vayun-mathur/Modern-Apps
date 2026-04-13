package com.vayunmathur.openassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.InitialDownloadChecker
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.IntentLauncher
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.io.File
import com.vayunmathur.openassistant.data.AppDatabase
import com.vayunmathur.openassistant.data.Conversation
import com.vayunmathur.openassistant.data.Message

class MainActivity : ComponentActivity() {

    companion object {
        lateinit var intentLauncher: IntentLauncher
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intentLauncher = IntentLauncher(this)

        val ds = DataStoreUtils.getInstance(this)
        val db = buildDatabase<AppDatabase>()
        val viewModel = DatabaseViewModel(db, Conversation::class to db.conversationDao(), Message::class to db.messageDao())

        val oldModelFile = File(applicationContext.getExternalFilesDir(null)!!, "model.litertlm")
        if(oldModelFile.exists()) {
            oldModelFile.delete()
            runBlocking {
                ds.setBoolean("dbSetupComplete", false)
            }
        }
        setContent {
            DynamicTheme {
                InitialDownloadChecker(ds, listOf(
                    Triple("https://huggingface.co/samirsayyed/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm", "gemma4.litertlm", "Model"),
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
    data class ConversationPage(val id: Long): Route
}

@Composable
fun Navigation(viewModel: DatabaseViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.ConversationPage(0))
    MainNavigation(backStack) {
        entry<Route.ConversationPage> {
            LiteRTChatUi(backStack, it.id, viewModel)
        }
    }
}