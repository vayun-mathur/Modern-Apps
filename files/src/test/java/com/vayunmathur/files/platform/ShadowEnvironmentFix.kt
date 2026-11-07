package com.vayunmathur.files.platform

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowEnvironment

/**
 * Robolectric's stock [android.os.Environment] blows up inside
 * `isExternalStorageManager()` (no user list in the sandbox). Inheriting the
 * stock shadow keeps its external-storage directory while fixing that one call
 * to report granted, which is the state the UI tests exercise.
 */
@Implements(android.os.Environment::class)
class ShadowEnvironmentFix : ShadowEnvironment() {
    companion object {
        @Implementation
        @JvmStatic
        fun isExternalStorageManager(): Boolean = true
    }
}

/** Same shadow but reporting all-files access as not granted (the shell's gate). */
@Implements(android.os.Environment::class)
class ShadowEnvironmentDeniedFix : ShadowEnvironment() {
    companion object {
        @Implementation
        @JvmStatic
        fun isExternalStorageManager(): Boolean = false
    }
}
