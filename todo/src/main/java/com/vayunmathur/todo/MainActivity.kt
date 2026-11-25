package com.vayunmathur.todo

import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.todo.data.TodoRepository
import com.vayunmathur.todo.data.TodoSettings
import com.vayunmathur.todo.platform.IntentRouter
import com.vayunmathur.todo.platform.TodoViewModel

/**
 * Entry activity: builds the repository and view model, forwards intents to IntentRouter, and hosts the
 * navigation graph.
 */
class MainActivity : MaActivity() {
    private lateinit var todoRepository: TodoRepository
    private val todoViewModel: TodoViewModel by viewModels {
        TodoViewModel.factory(application, todoRepository, TodoSettings(applicationContext))
    }

    override fun onCreateBeforeContent() {
        com.vayunmathur.library.wearlink.WearLinkPermissions.requestIfNeeded(this)
        todoRepository = TodoRepository.get(applicationContext)
        // Wear companion lives and dies with this process (WearLink has no
        // system wakeup); it publishes once the link connects.
        com.vayunmathur.todo.sync.WearTodoSync.get(applicationContext).start()
        IntentRouter.handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        IntentRouter.handle(intent)
    }

    @Composable
    override fun Content() {
        Navigation(todoViewModel)
    }
}
