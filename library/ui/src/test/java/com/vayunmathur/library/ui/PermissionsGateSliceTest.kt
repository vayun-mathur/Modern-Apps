package com.vayunmathur.library.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Slice 3/3: permission gate — an empty spec renders content directly; a spec
 * with already-granted entries also passes through; missing entries show the
 * wall with one row per requirement.
 */
@RunWith(RobolectricTestRunner::class)
class PermissionsGateSliceTest {

    @get:Rule
    val rule = createComposeRule()

    @Test fun emptySpecRendersContent() {
        rule.setContent {
            AppPermissionsGate(
                spec = AppPermissionsSpec(title = "T", requirements = emptyList()),
            ) {
                Text("Inner content")
            }
        }
        rule.onNodeWithText("Inner content").assertIsDisplayed()
    }

    @Test fun emptyPermissionBatchCountsAsGranted() {
        // PermissionRequirement.notifications() is empty pre-Tiramisu; an empty
        // Runtime batch reports granted, so the gate passes through.
        rule.setContent {
            AppPermissionsGate(
                spec = AppPermissionsSpec(
                    title = "T",
                    requirements = listOf(
                        PermissionRequirement.Runtime(emptyArray()),
                    ),
                ),
            ) {
                Text("Passed through")
            }
        }
        rule.onNodeWithText("Passed through").assertIsDisplayed()
    }

    @Test fun missingRuntimePermissionShowsWall() {
        rule.setContent {
            AppPermissionsGate(
                spec = AppPermissionsSpec(
                    title = "T",
                    requirements = listOf(
                        PermissionRequirement.Runtime(
                            arrayOf("android.permission.CAMERA"),
                        ),
                    ),
                ),
            ) {
                Text("Should not show")
            }
        }
        // Wall title from resources; content hidden behind the gate.
        rule.waitForIdle()
        assertEquals(
            0,
            rule.onAllNodesWithText("Should not show").fetchSemanticsNodes().size,
        )
    }
}
