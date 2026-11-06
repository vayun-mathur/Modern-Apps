package com.vayunmathur.office

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.office.util.OfficeMember
import com.vayunmathur.office.util.OfficeRoles
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 4/4 share-dialog tests (Robolectric + createComposeRule):
 * [ShareOnlineDialog] viewer vs owner bodies. All collaborators arrive as
 * plain params/callbacks, so no ViewModel or network is needed.
 */
@RunWith(AndroidJUnit4::class)
class Slice4ShareDialogTest {

    @get:Rule val rule = createComposeRule()

    private fun viewerProps(
        onDismiss: () -> Unit = {},
    ): ShareDialogProps = ShareDialogProps(
        deviceId = "dev1",
        isOwner = false,
        isOnline = true,
        initialName = "Doc",
        myRole = OfficeRoles.VIEWER,
        members = listOf(OfficeMember("dev0", "Owner", OfficeRoles.OWNER)),
        onShare = { _, _, _, _ -> },
        onSetRole = { _, _ -> },
        onTransferOwner = {},
        onRename = {},
        onComputeCode = { _, _ -> },
        onDismiss = onDismiss,
    )

    // Local alias: ShareOnlineDialog takes many params; bundle the fixed ones.
    private data class ShareDialogProps(
        val deviceId: String,
        val isOwner: Boolean,
        val isOnline: Boolean,
        val initialName: String,
        val myRole: String,
        val members: List<OfficeMember>,
        val onShare: (String, String, String, (String?) -> Unit) -> Unit,
        val onSetRole: (String, String) -> Unit,
        val onTransferOwner: (String) -> Unit,
        val onRename: (String) -> Unit,
        val onComputeCode: (String, (String?) -> Unit) -> Unit,
        val onDismiss: () -> Unit,
    )

    private fun show(p: ShareDialogProps, shareLink: String? = null) {
        rule.setContent {
            ShareOnlineDialog(
                deviceId = p.deviceId,
                isOwner = p.isOwner,
                isOnline = p.isOnline,
                initialName = p.initialName,
                myRole = p.myRole,
                members = p.members,
                shareLink = shareLink,
                onShare = p.onShare,
                onSetRole = p.onSetRole,
                onTransferOwner = p.onTransferOwner,
                onRename = p.onRename,
                onComputeCode = p.onComputeCode,
                onDismiss = p.onDismiss,
            )
        }
    }

    @Test fun viewerSeesRoleNoteAndDeviceId() {
        show(viewerProps())
        rule.onNodeWithText("Share online").assertIsDisplayed()
        rule.onNodeWithText("viewer access", substring = true).assertIsDisplayed()
        rule.onNodeWithText("dev1").assertIsDisplayed()
        // Viewers get only a Close button (no Add/share confirm).
        rule.onNodeWithText("Close").assertIsDisplayed()
    }

    @Test fun viewerCloseDismisses() {
        var dismissed = false
        show(viewerProps(onDismiss = { dismissed = true }))
        rule.onNodeWithText("Close").performClick()
        assertTrue(dismissed)
    }

    @Test fun ownerOfflineNamesDocumentFirst() {
        var shared: Triple<String, String, String>? = null
        show(
            viewerProps().copy(
                isOwner = true,
                isOnline = false,
                members = emptyList(),
                onShare = { r, role, name, _ -> shared = Triple(r, role, name) },
            ),
        )
        rule.onNodeWithText("Document name", substring = true).assertIsDisplayed()
        // Add is disabled until a recipient is typed.
        rule.onNodeWithText("Add").assertIsDisplayed()
        assertNull(shared)
    }

    @Test fun ownerOfflineShareFlow() {
        var shared: Triple<String, String, String>? = null
        var dismissed = false
        show(
            viewerProps().copy(
                isOwner = true,
                isOnline = false,
                members = emptyList(),
                onShare = { r, role, name, done ->
                    shared = Triple(r, role, name)
                    done(null)
                },
                onDismiss = { dismissed = true },
            ),
        )
        // Type recipient (clears the empty default first), then Add.
        val fields = rule.onAllNodes(hasSetTextAction())
        // Recipient field is the empty one; doc-name field holds "Doc".
        fields[1].performTextClearance()
        fields[1].performTextInput("dev9")
        rule.onNodeWithText("Add").performClick()
        val s = shared
        assertTrue(s != null)
        assertEquals("dev9", s!!.first)
        assertEquals(OfficeRoles.EDITOR, s!!.second)
        assertTrue(!dismissed)
    }

    @Test fun ownerOnlineCanRename() {
        var renamed: String? = null
        show(
            viewerProps().copy(
                isOwner = true,
                isOnline = true,
                onRename = { renamed = it },
            ),
        )
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextClearance()
        fields[0].performTextInput("Renamed")
        // Exact match: the doc-name field now contains "Renamed" (substring match).
        rule.onNodeWithText("Rename").performClick()
        assertEquals("Renamed", renamed)
    }

    @Test fun ownerSeesShareLinkButton() {
        show(
            viewerProps().copy(isOwner = true, isOnline = true),
            shareLink = "https://example.com/join/abc",
        )
        rule.onNodeWithText("Share link", substring = true).assertIsDisplayed()
    }

    @Test fun ownerRoleMenuSwitchesToViewer() {
        var shared: Triple<String, String, String>? = null
        show(
            viewerProps().copy(
                isOwner = true,
                isOnline = true,
                members = emptyList(),
                onShare = { r, role, name, _ -> shared = Triple(r, role, name) },
            ),
        )
        // Open the role menu (shows current "editor") and pick viewer.
        rule.onNodeWithText("editor").performClick()
        rule.onNodeWithText("viewer").performClick()
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[1].performTextClearance()
        fields[1].performTextInput("dev9")
        rule.onNodeWithText("Add").performClick()
        assertEquals(OfficeRoles.VIEWER, shared?.second)
    }
}
