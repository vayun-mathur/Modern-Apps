package com.vayunmathur.games.voxels.platform

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.voxels.MainActivity
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class ManifestTest {
    // No fixed orientation: Android 16 ignores fixed orientations on large screens, so the
    // game must not declare one (DiscouragedApi). MainActivity already handles orientation in
    // configChanges, so rotation resizes the surface instead of recreating the activity.
    @Test fun `the game screen does not declare a fixed orientation`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val info = ctx.packageManager.getActivityInfo(ComponentName(ctx, MainActivity::class.java), 0)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, info.screenOrientation)
    }
}
