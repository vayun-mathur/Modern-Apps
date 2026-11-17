package com.vayunmathur.web.platform

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.vayunmathur.web.MainActivity

fun launchNewWebWindow(context: Context, incognito: Boolean) {
    val windowId = java.util.UUID.randomUUID().toString()
    val intent = Intent(context, MainActivity::class.java).apply {
        putExtra(MainActivity.EXTRA_WINDOW_ID, windowId)
        putExtra(MainActivity.EXTRA_INCOGNITO, incognito)
        data = "web-window://$windowId".toUri()
        addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_NEW_DOCUMENT
        )
    }
    context.startActivity(intent)
}
