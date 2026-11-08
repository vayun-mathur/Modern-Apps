package com.vayunmathur.library.ui

import android.provider.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice 3/3 round 15: legacy-SDK branches of the permission helpers — the
 * pre-API-34 full-screen-intent fallback and the pre-Tiramisu notifications
 * requirement, unreachable at the repo-default SDK 34.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Slice3Round15Sdk33Test {

    private val context = androidx.test.core.app.ApplicationProvider
        .getApplicationContext<android.content.Context>()

    @Test fun fullScreenIntentGrantedByDefaultBeforeApi34() {
        assertTrue(SpecialAccess.hasFullScreenIntent(context))
        assertTrue(PermissionRequirement.FullScreenIntent.isGranted(context))
    }

    @Test fun fullScreenIntentRequestFallsBackToAppSettingsBeforeApi34() {
        val shadow = org.robolectric.Shadows.shadowOf(
            context as android.app.Application,
        )
        SpecialAccess.requestFullScreenIntent(context)
        val intent = shadow.nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${context.packageName}", intent.data?.toString())
    }

    @Test fun notificationsRequirementExistsOnTiramisu() {
        val req = PermissionRequirement.notifications()
        assertEquals(1, req.permissions.size)
        assertEquals(
            android.Manifest.permission.POST_NOTIFICATIONS,
            req.permissions[0],
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class Slice3Round15Sdk32Test {

    private val context = androidx.test.core.app.ApplicationProvider
        .getApplicationContext<android.content.Context>()

    @Test fun notificationsRequirementEmptyBeforeTiramisu() {
        val req = PermissionRequirement.notifications()
        assertEquals(0, req.permissions.size)
        // An empty batch reports granted, so callers can gate unconditionally.
        assertTrue(req.isGranted(context))
    }

    @Test fun exactAlarmsCheckedThroughAlarmManagerOnS() {
        // SDK 32 >= S: the AlarmManager path (not the pre-S shortcut) runs and
        // Robolectric's shadow reports the platform default without crashing.
        SpecialAccess.hasExactAlarms(context)
    }
}
