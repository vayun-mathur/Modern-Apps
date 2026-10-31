package com.vayunmathur.todo.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vayunmathur.todo.data.TodoRepository
import com.vayunmathur.todo.platform.TodoBackground
import kotlinx.coroutines.launch

/**
 * Alarms do not survive a reboot, an app update, or a clock/zone change that moves their
 * wall-clock meaning; re-arm everything on each.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED) return
        val app = context.applicationContext
        val pending = goAsync()
        TodoRepository.get(app).appScope.launch {
            try {
                TodoBackground.reconcileNow(app)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
