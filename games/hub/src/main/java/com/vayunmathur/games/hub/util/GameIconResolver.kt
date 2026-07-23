package com.vayunmathur.games.hub.util

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

object GameIconResolver {

    fun resolveAppIcon(context: Context, packageName: String): Drawable? {
        return try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
