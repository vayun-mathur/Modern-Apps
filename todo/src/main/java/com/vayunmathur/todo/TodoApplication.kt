package com.vayunmathur.todo

import android.app.Application
import com.vayunmathur.todo.platform.TodoBackground

class TodoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TodoBackground.start(this)
    }
}
