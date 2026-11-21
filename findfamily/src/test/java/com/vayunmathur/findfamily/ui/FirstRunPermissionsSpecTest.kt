package com.vayunmathur.findfamily.ui

import android.Manifest
import com.vayunmathur.library.ui.PermissionRequirement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where each permission moved: the returning-user gate keeps the original
 * foreground → background → Bluetooth order, location sharing carries that
 * same split list to the enabling moment, and notifications are in neither
 * startup list (they are requested contextually at the first alert).
 */
class FirstRunPermissionsSpecTest {

    private fun permissionsOf(requirements: List<PermissionRequirement>): List<String> =
        requirements.flatMap {
            (it as PermissionRequirement.Runtime).permissions.toList()
        }

    @Test fun fullGateKeepsOriginalOrder() {
        assertEquals(
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                Manifest.permission.BLUETOOTH_SCAN,
            ),
            permissionsOf(findFamilyFullRequirements()),
        )
    }

    @Test fun locationSharingIsTheSplitGateList() {
        assertEquals(
            permissionsOf(findFamilyFullRequirements()),
            permissionsOf(findFamilyLocationSharingRequirements()),
        )
        assertEquals(3, findFamilyLocationSharingRequirements().size)
    }

    @Test fun notificationsAreNotStagedAtStartup() {
        assertFalse(permissionsOf(findFamilyFullRequirements()).contains(Manifest.permission.POST_NOTIFICATIONS))
        assertFalse(permissionsOf(findFamilyLocationSharingRequirements()).contains(Manifest.permission.POST_NOTIFICATIONS))
    }

    @Test fun notificationRequirementIsPostNotificationsOrEmpty() {
        val perms = findFamilyNotificationRequirement().permissions.toList()
        assertTrue(
            perms.isEmpty() || perms == listOf(Manifest.permission.POST_NOTIFICATIONS),
            "unexpected notification permissions: $perms",
        )
    }

    @Test fun foregroundRequestsFineAndCoarseTogether() {
        assertEquals(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            findFamilyForegroundLocationRequirement().permissions.toList(),
        )
    }
}
