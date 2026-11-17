package com.vayunmathur.todo

import android.app.Application
import com.vayunmathur.todo.platform.TodoBackground

/** Application entry point: starts background work for reminders, widgets and the calendar mirror. */
class TodoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TodoBackground.start(this)
    }
}
