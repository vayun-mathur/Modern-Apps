package com.vayunmathur.launcher.wear.sync

import android.content.Context

/** Asks the phone for fresh favorites; false when no session is live. */
suspend fun requestWearSync(context: Context): Boolean = WearLinkBridge.requestSync(context)
