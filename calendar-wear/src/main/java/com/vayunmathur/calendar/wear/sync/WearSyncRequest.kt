package com.vayunmathur.calendar.wear.sync

import android.content.Context

/**
 * Asks the phone for a fresh snapshot over WearLink. Returns false when no
 * phone session is live — the watch keeps rendering its last synced copy,
 * and the UI says exactly that instead of pretending a sync happened.
 */
suspend fun requestWearSync(context: Context): Boolean = WearLinkBridge.requestSync(context)
