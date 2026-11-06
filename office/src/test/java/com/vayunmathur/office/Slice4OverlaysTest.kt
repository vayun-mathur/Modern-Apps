package com.vayunmathur.office

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.odf.OdfAnnotation
import com.vayunmathur.library.ui.odf.OdfBookmark
import com.vayunmathur.library.ui.odf.OdfChange
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.ParagraphStyle
import com.vayunmathur.office.ui.HeadingItem
import com.vayunmathur.office.util.OfficeMember
import com.vayunmathur.office.util.OfficeRoles
import com.vayunmathur.office.util.OfficeViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 4/4 overlay composition tests (Robolectric + createComposeRule):
 * outline pane, share-members list, tracked-changes and comments overlays.
 */
@RunWith(AndroidJUnit4::class)
class Slice4OverlaysTest {

    @get:Rule val rule = createComposeRule()

    private fun vm(): OfficeViewModel =
        OfficeViewModel(ApplicationProvider.getApplicationContext())

    private fun textDoc(): OdfDocument.TextDocument = OdfDocument.TextDocument(
        "Doc",
        listOf(
            OdfContentBlock.Paragraph(
                OdfParagraph(listOf(OdfSpan("Title")), style = ParagraphStyle.HEADING1)),
            OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("body")))),
        ),
    )

    // ---- OfficeOutlinePane ----

    @Test fun outlinePaneShowsHeadingsAndBookmarks() {
        rule.setContent {
            OfficeOutlinePane(
                bookmarks = listOf(OdfBookmark("ch1", 1)),
                headings = listOf(
                    HeadingItem("Title", 1, 0),
                    HeadingItem("Sub", 2, 1),
                    HeadingItem("Deep", 3, 1),
                ),
                listState = androidx.compose.foundation.lazy.LazyListState(),
                drawerState = com.vayunmathur.library.ui.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed),
                scope = rememberCoroutineScope(),
            )
        }
        rule.onNodeWithText("Outline", substring = true).assertIsDisplayed()
        rule.onNodeWithText("ch1", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Title").assertIsDisplayed()
        rule.onNodeWithText("Sub").assertIsDisplayed()
        rule.onNodeWithText("Deep").assertIsDisplayed()
    }

    @Test fun outlinePaneEmptyBookmarksHidesSection() {
        rule.setContent {
            OfficeOutlinePane(
                bookmarks = emptyList(),
                headings = listOf(HeadingItem("Only", 1, 0)),
                listState = androidx.compose.foundation.lazy.LazyListState(),
                drawerState = com.vayunmathur.library.ui.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed),
                scope = rememberCoroutineScope(),
            )
        }
        rule.onNodeWithText("Only").assertIsDisplayed()
        rule.onNodeWithText("Bookmarks", substring = true).assertDoesNotExist()
    }

    // ---- ShareMembersList ----

    @Test fun shareMembersShowsRolesAndYou() {
        var menuFor: String? = "unset"
        rule.setContent {
            ShareMembersList(
                members = listOf(
                    OfficeMember("dev1", "Ann", OfficeRoles.OWNER),
                    OfficeMember("dev2", "", OfficeRoles.EDITOR),
                    OfficeMember("dev3", "Bob", OfficeRoles.REVOKED),
                ),
                deviceId = "dev1",
                isOwner = true,
                memberMenu = null,
                onMemberMenu = { menuFor = it },
                onSetRole = { _, _ -> },
                onTransferOwner = {},
            )
        }
        // Owner sees themselves with "(you)".
        rule.onNodeWithText("Ann (you)", substring = true).assertIsDisplayed()
        // Blank name falls back to id prefix.
        rule.onNodeWithText("dev2", substring = true).assertIsDisplayed()
        // Revoked members are filtered out.
        rule.onNodeWithText("Bob", substring = true).assertDoesNotExist()
        assertEquals("unset", menuFor)
    }

    @Test fun shareMembersNonOwnerSeesNoManage() {
        rule.setContent {
            ShareMembersList(
                members = listOf(OfficeMember("dev2", "Ann", OfficeRoles.EDITOR)),
                deviceId = "dev1",
                isOwner = false,
                memberMenu = null,
                onMemberMenu = {},
                onSetRole = { _, _ -> },
                onTransferOwner = {},
            )
        }
        rule.onNodeWithText("Manage", substring = true).assertDoesNotExist()
    }

    @Test fun shareMembersManageOpensMenu() {
        var menuFor: String? = "unset"
        var roleSet: Pair<String, String>? = null
        rule.setContent {
            ShareMembersList(
                members = listOf(OfficeMember("dev2", "Ann", OfficeRoles.EDITOR)),
                deviceId = "dev1",
                isOwner = true,
                memberMenu = "dev2",
                onMemberMenu = { menuFor = it },
                onSetRole = { id, role -> roleSet = id to role },
                onTransferOwner = {},
            )
        }
        // Editor gets "make viewer" + owner-transfer + remove options (Material3 dropdown).
        rule.onNodeWithText("Make viewer", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Make viewer", substring = true).performClick()
        assertEquals("dev2" to OfficeRoles.VIEWER, roleSet)
        assertEquals(null, menuFor)
    }

    @Test fun shareMembersViewerGetsMakeEditor() {
        var roleSet: Pair<String, String>? = null
        rule.setContent {
            ShareMembersList(
                members = listOf(OfficeMember("dev2", "Ann", OfficeRoles.VIEWER)),
                deviceId = "dev1",
                isOwner = true,
                memberMenu = "dev2",
                onMemberMenu = {},
                onSetRole = { id, role -> roleSet = id to role },
                onTransferOwner = {},
            )
        }
        rule.onNodeWithText("Make editor", substring = true).performClick()
        assertEquals("dev2" to OfficeRoles.EDITOR, roleSet)
    }

    @Test fun shareMembersTransferOwner() {
        var transferred: String? = null
        rule.setContent {
            ShareMembersList(
                members = listOf(OfficeMember("dev2", "Ann", OfficeRoles.EDITOR)),
                deviceId = "dev1",
                isOwner = true,
                memberMenu = "dev2",
                onMemberMenu = {},
                onSetRole = { _, _ -> },
                onTransferOwner = { transferred = it },
            )
        }
        rule.onNodeWithText("Make owner", substring = true).performClick()
        assertEquals("dev2", transferred)
    }

    @Test fun shareMembersEmptyShowsNothing() {
        rule.setContent {
            ShareMembersList(
                members = emptyList(),
                deviceId = "dev1",
                isOwner = true,
                memberMenu = null,
                onMemberMenu = {},
                onSetRole = { _, _ -> },
                onTransferOwner = {},
            )
        }
        rule.onNodeWithText("access", substring = true).assertDoesNotExist()
    }

    // ---- ChangesOverlays ----

    private fun changeDoc() = OdfDocument.TextDocument(
        "Doc",
        listOf(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("hi"))))),
        changes = listOf(
            OdfChange("c1", "insertion", author = "me", date = "2024-05-01T10:00:00"),
            OdfChange("c2", "deletion", author = null, date = null),
        ),
    )

    @Test fun changesHiddenByDefault() {
        val state = DocumentOverlayState()
        val viewModel = vm()
        viewModel.stateMutable.value = OfficeViewModel.ViewState.Loaded(changeDoc())
        rule.setContent {
            ChangesOverlays(state = state, document = changeDoc(), viewModel = viewModel)
        }
        rule.onNodeWithText("Tracked", substring = true).assertDoesNotExist()
    }

    @Test fun changesDialogListsAndAccepts() {
        val state = DocumentOverlayState().apply { showChanges = true }
        val viewModel = vm()
        viewModel.stateMutable.value = OfficeViewModel.ViewState.Loaded(changeDoc())
        rule.setContent {
            ChangesOverlays(state = state, document = changeDoc(), viewModel = viewModel)
        }
        rule.onNodeWithText("Tracked", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Insertion", substring = true).assertIsDisplayed()
        // Two changes -> two exact "Accept" buttons; accept the first (c1).
        rule.onAllNodesWithText("Accept")[0].performClick()
        val cur = (viewModel.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.TextDocument
        assertEquals(listOf("c2"), cur.changes.map { it.id })
    }

    @Test fun changesDialogRejects() {
        val state = DocumentOverlayState().apply { showChanges = true }
        val viewModel = vm()
        viewModel.stateMutable.value = OfficeViewModel.ViewState.Loaded(changeDoc())
        rule.setContent {
            ChangesOverlays(state = state, document = changeDoc(), viewModel = viewModel)
        }
        rule.onAllNodesWithText("Reject")[0].performClick()
        val cur = (viewModel.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.TextDocument
        assertEquals(listOf("c2"), cur.changes.map { it.id })
    }

    @Test fun changesEmptyShowsPlaceholder() {
        val state = DocumentOverlayState().apply { showChanges = true }
        val viewModel = vm()
        val doc = textDoc()
        viewModel.stateMutable.value = OfficeViewModel.ViewState.Loaded(doc)
        rule.setContent {
            ChangesOverlays(state = state, document = doc, viewModel = viewModel)
        }
        rule.onNodeWithText("No tracked", substring = true).assertIsDisplayed()
    }

    // ---- CommentsOverlays ----

    private fun commentDoc() = OdfDocument.TextDocument(
        "Doc",
        listOf(
            OdfContentBlock.Paragraph(
                OdfParagraph(
                    listOf(
                        OdfSpan(
                            "noted",
                            annotation = OdfAnnotation(
                                author = "me",
                                date = "2024-01-01",
                                paragraphs = listOf(
                                    OdfParagraph(listOf(OdfSpan("fix this")))))),
                    ),
                )),
        ),
    )

    @Test fun commentsDialogListsAndResolves() {
        val state = DocumentOverlayState().apply { showComments = true }
        val viewModel = vm()
        viewModel.stateMutable.value = OfficeViewModel.ViewState.Loaded(commentDoc())
        rule.setContent {
            CommentsOverlays(
                state = state,
                document = commentDoc(),
                viewModel = viewModel,
                listState = rememberLazyListState(),
                scope = rememberCoroutineScope(),
            )
        }
        rule.onNodeWithText("fix this").assertIsDisplayed()
        rule.onNodeWithText("Resolve").performClick()
        val cur = (viewModel.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.TextDocument
        val spans = (cur.content[0] as OdfContentBlock.Paragraph).paragraph.spans
        assertTrue(spans.all { it.annotation == null })
        assertTrue(state.showComments)
    }

    @Test fun commentsEmptyShowsPlaceholder() {
        val state = DocumentOverlayState().apply { showComments = true }
        val viewModel = vm()
        val doc = textDoc()
        viewModel.stateMutable.value = OfficeViewModel.ViewState.Loaded(doc)
        rule.setContent {
            CommentsOverlays(
                state = state,
                document = doc,
                viewModel = viewModel,
                listState = rememberLazyListState(),
                scope = rememberCoroutineScope(),
            )
        }
        rule.onNodeWithText("No comments", substring = true).assertIsDisplayed()
    }

    @Test fun overlayStateDefaults() {
        val s = DocumentOverlayState()
        assertTrue(!s.showChanges)
        assertTrue(!s.showComments)
        assertTrue(!s.showMetadata)
        assertEquals(-1, s.editingChartBlock)
        val full = DocumentScreenState()
        assertTrue(!full.showSearch)
        assertEquals("", full.searchQuery)
        assertEquals(-1, full.activeRunStart)
        assertEquals(null, full.activeCell)
    }
}
