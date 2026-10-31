package com.vayunmathur.todo.ui

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.todo.R

/** "Synced 5 minutes ago", or null if everysync has never synced this app. */
@Composable
fun syncStatus(lastSyncAt: Long): String? {
    if (lastSyncAt <= 0L) return null
    val ago = DateUtils.getRelativeTimeSpanString(lastSyncAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    return stringResource(R.string.synced_ago, ago)
}
