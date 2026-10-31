package com.vayunmathur.todo

import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.todo.data.TodoRepository
import com.vayunmathur.todo.data.TodoSettings
import com.vayunmathur.todo.platform.IntentRouter
import com.vayunmathur.todo.platform.TodoViewModel

class MainActivity : MaActivity() {
    private lateinit var todoRepository: TodoRepository
    private val todoViewModel: TodoViewModel by viewModels {
        TodoViewModel.factory(application, todoRepository, TodoSettings(applicationContext))
    }

    override fun onCreateBeforeContent() {
        todoRepository = TodoRepository.get(applicationContext)
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
