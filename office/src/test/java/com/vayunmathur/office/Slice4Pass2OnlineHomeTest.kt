package com.vayunmathur.office

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.Text
import com.vayunmathur.office.ui.OfficeEditorWideLayout
import com.vayunmathur.office.util.OfficeDocMeta
import com.vayunmathur.office.util.OfficeSync
import com.vayunmathur.office.util.OfficeViewModel
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Slice 4 pass 2: OnlineTab enabled lists, OfficeTabs, wide layout, themes, routes. */
@RunWith(AndroidJUnit4::class)
class Slice4Pass2OnlineHomeTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun str(id: Int, vararg args: Any) = if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args)

    private fun vm(): OfficeViewModel =
        OfficeViewModel(ApplicationProvider.getApplicationContext<android.app.Application>())

    /** VM init asynchronously resets onlineEnabled from stored identity; settle it first. */
    private fun settledVm(): OfficeViewModel {
        val viewModel = vm()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        return viewModel
    }

    private fun meta(docId: String, title: String, owner: Boolean) =
        OfficeDocMeta(docId = docId, title = title, keyB64 = "a2V5", owner = owner)

    @Test fun onlineTabEnabledEmptyShowsNoticeAndDeviceHeader() {
        val viewModel = settledVm()
        rule.setContent { OnlineTab(viewModel = viewModel, onOpenDoc = {}) }
        rule.waitForIdle()
        viewModel.onlineEnabledMutable.value = true
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.online)).assertExists()
        rule.onNodeWithText(str(R.string.no_online_documents_yet_open_a_document), substring = true).assertExists()
        rule.onNodeWithText(str(R.string.your_device_id_share_this_so_others_can), substring = true).assertExists()
    }

    @Test fun onlineTabEnabledListsDocsAndRequestsAndOpensDoc() {
        val viewModel = settledVm()
        val owned = meta("doc-owned-1", "Owned Report", owner = true)
        val shared = meta("doc-shared-2", "Shared Notes", owner = false)
        var opened: OfficeDocMeta? = null
        rule.setContent { OnlineTab(viewModel = viewModel, onOpenDoc = { opened = it }) }
        rule.waitForIdle()
        viewModel.onlineEnabledMutable.value = true
        rule.waitForIdle()
        viewModel.onlineDocsMutable.value = listOf(owned, shared)
        viewModel.pendingRequestsMutable.value = listOf(
            OfficeSync.JoinRequest(docId = "doc-owned-1", requesterId = "device-abcdef", name = "Carol"),
            OfficeSync.JoinRequest(docId = "missing-doc", requesterId = "device-123456", name = ""),
        )
        rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasScrollAction())
            .performScrollToNode(androidx.compose.ui.test.hasText(str(R.string.requests_header)))
        rule.onNodeWithText(str(R.string.requests_header), substring = true).assertExists()
        rule.onNodeWithText("Carol", substring = true).assertExists()
        rule.onNode(androidx.compose.ui.test.hasScrollAction())
            .performScrollToNode(androidx.compose.ui.test.hasText("Owned Report"))
        rule.onNodeWithText("Owned Report").assertExists()
        rule.onNodeWithText(str(R.string.shared_by_you), substring = true).assertExists()
        rule.onNodeWithText("Owned Report").performClick()
        assertEquals(owned, opened)
    }

    @Test fun onlineTabJoinRequestButtonsRender() {
        val viewModel = settledVm()
        viewModel.onlineDocsMutable.value = listOf(meta("doc-owned-1", "Owned Report", owner = true))
        viewModel.pendingRequestsMutable.value = listOf(
            OfficeSync.JoinRequest(docId = "doc-owned-1", requesterId = "device-abcdef", name = "Dan"),
        )
        rule.setContent { OnlineTab(viewModel = viewModel, onOpenDoc = {}) }
        rule.waitForIdle()
        viewModel.onlineEnabledMutable.value = true
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.approve)).assertExists()
        rule.onNodeWithText(str(R.string.deny)).assertExists()
    }

    @Test fun officeTabsShowsOfflineHomeAndOnlineTab() {
        val viewModel = vm()
        var editorNavs = 0
        rule.setContent {
            OfficeTabs(
                viewModel = viewModel,
                onOpenDocument = {},
                onNavigateEditor = { editorNavs++ },
                onOpenOnlineDoc = {},
            )
        }
        // Offline (first) page: the initial/home screen actions, plus both tab labels.
        rule.onNodeWithText(str(R.string.open_document)).assertExists()
        rule.onNodeWithText("Online").assertExists()
        rule.onNodeWithText("Offline").assertExists()
        assertEquals(0, editorNavs)
    }

    @Test fun initialScreenNewDocButtonsNavigate() {
        val viewModel = vm()
        var editorNavs = 0
        rule.setContent {
            InitialScreen(viewModel = viewModel, onOpenDocument = {}, onNavigateEditor = { editorNavs++ })
        }
        rule.onNodeWithText(str(R.string.new_doc)).performClick()
        assertEquals(1, editorNavs)
        assertTrue(viewModel.state.value is OfficeViewModel.ViewState.Loaded)
    }

    @Test fun wideLayoutRendersBothSlots() {
        rule.setContent {
            OfficeEditorWideLayout(
                outline = { Text("wide outline") },
                document = { Text("wide document") },
            )
        }
        rule.onNodeWithText("wide outline").assertExists()
        rule.onNodeWithText("wide document").assertExists()
    }

    @Test fun lightThemeAndDarkModeInvertDraw() {
        var dark by mutableStateOf(false)
        rule.setContent {
            OfficeLightTheme {
                Box(if (dark) Modifier.documentDarkModeInvert() else Modifier) {
                    Text("paper text")
                }
            }
        }
        rule.onNodeWithText("paper text").assertExists()
        rule.onRoot().captureToImage()
        dark = true
        rule.onRoot().captureToImage()
        rule.onNodeWithText("paper text").assertExists()
    }

    @Test fun officeRoutesSerializeRoundTrip() {
        assertEquals(
            OfficeRoute.OfflineEditor("content://x"),
            Json.decodeFromString(OfficeRoute.serializer(), Json.encodeToString(OfficeRoute.serializer(), OfficeRoute.OfflineEditor("content://x"))),
        )
        assertEquals(
            OfficeRoute.OnlineEditor("doc9"),
            Json.decodeFromString(OfficeRoute.serializer(), Json.encodeToString(OfficeRoute.serializer(), OfficeRoute.OnlineEditor("doc9"))),
        )
        assertEquals(
            OfficeRoute.Main,
            Json.decodeFromString(OfficeRoute.serializer(), Json.encodeToString(OfficeRoute.serializer(), OfficeRoute.Main)),
        )
        assertEquals(null, OfficeRoute.OfflineEditor().uri)
    }
}
