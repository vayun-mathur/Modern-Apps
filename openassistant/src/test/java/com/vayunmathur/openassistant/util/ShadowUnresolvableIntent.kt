package com.vayunmathur.openassistant.util

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Every intent reads as unresolvable, so cross-app launches take the missing-app path. */
@Implements(Intent::class)
class ShadowUnresolvableIntent {
    @Implementation
    fun resolveActivity(pm: PackageManager): ResolveInfo? = null
}
