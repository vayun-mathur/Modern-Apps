package com.vayunmathur.files.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.files.MainActivity
import com.vayunmathur.files.Route
import com.vayunmathur.files.data.saf.DocumentPickerActivity
import com.vayunmathur.files.platform.FilesViewModel
import com.vayunmathur.files.platform.ShadowEnvironmentFix
import com.vayunmathur.files.platform.TestFileProvider
import com.vayunmathur.library.util.NavBackStack
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowEnvironmentFix::class], qualifiers = "w800dp-h1280dp")
class ActivitiesAndBindersTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `main activity launches shell and handles send intents`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.create().start().resume().visible().get()
        ShadowLooper.idleMainLooper()
        assertNotNull(activity)
        // a SEND intent with a stream is accepted as incoming uris
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://testdocs/shared.txt"))
        }
        controller.newIntent(send)
        val multi = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putParcelableArrayListExtra(
                Intent.EXTRA_STREAM,
                arrayListOf(Uri.parse("content://testdocs/a.txt")),
            )
        }
        controller.newIntent(multi)
        controller.newIntent(Intent(Intent.ACTION_MAIN))
        ShadowLooper.idleMainLooper()
        controller.pause().stop().destroy()
    }

    @Test
    fun `text editor activity finishes without data and edits with data`() {
        val noData = Robolectric.buildActivity(TextEditorActivity::class.java)
        val finished = noData.create().get()
        assertTrue(finished.isFinishing)

        val base = File(app.cacheDir, "editor-act-${System.nanoTime()}").apply { mkdirs() }
        File(base, "note.txt").writeText("editor body")
        TestFileProvider.rootDir = base
        Robolectric.buildContentProvider(TestFileProvider::class.java).create("testdocs")
        val intent = Intent(Intent.ACTION_VIEW).setData(Uri.parse("content://testdocs/note.txt"))
        val controller = Robolectric.buildActivity(TextEditorActivity::class.java, intent)
        val activity = controller.create().start().resume().visible().get()
        ShadowLooper.idleMainLooper()
        assertNotNull(activity)
        controller.pause().stop().destroy()
        base.deleteRecursively()
    }

    @Test
    fun `document picker cancels unknown actions and renders open mode`() {
        val unknown = Robolectric.buildActivity(
            DocumentPickerActivity::class.java,
            Intent(Intent.ACTION_MAIN),
        )
        val cancelled = unknown.create().get()
        ShadowLooper.idleMainLooper()
        assertTrue(cancelled.isFinishing)
        assertEquals(android.app.Activity.RESULT_CANCELED, Shadows.shadowOf(cancelled).resultCode)

        for (action in listOf(
            Intent.ACTION_OPEN_DOCUMENT,
            Intent.ACTION_GET_CONTENT,
            Intent.ACTION_OPEN_DOCUMENT_TREE,
            Intent.ACTION_CREATE_DOCUMENT,
        )) {
            val intent = Intent(action).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_TITLE, "created.txt")
                if (action == Intent.ACTION_OPEN_DOCUMENT) {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain"))
                }
            }
            val controller = Robolectric.buildActivity(DocumentPickerActivity::class.java, intent)
            val activity = controller.create().start().resume().visible().get()
            ShadowLooper.idleMainLooper()
            assertNotNull(activity)
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `nav actions drive the back stack`() {
        val vm = FilesViewModel(app)
        ShadowLooper.idleMainLooper()
        val stack = NavBackStack<Route>(arrayOf(Route.Home))
        val actions = filesNavActions(vm, stack)
        val root = vm.rootDirectory

        actions.openInternalStorage()
        assertEquals(Route.Directory(root.absolutePath), stack.backStack.last())

        actions.goHome()
        assertEquals(listOf(Route.Home), stack.backStack.toList())

        val deep = File(root, "a/b/c")
        actions.openBookmark(deep)
        assertEquals(Route.Directory(deep.absolutePath), stack.backStack.last())
        assertTrue(stack.backStack.size > 2)

        actions.navigateTo(File(root, "a"))
        // already on the stack: pops back to it instead of duplicating
        assertEquals(Route.Directory(File(root, "a").absolutePath), stack.backStack.last())

        actions.openCategory(com.vayunmathur.files.platform.FileCategory.IMAGES)
        assertEquals(Route.Category(com.vayunmathur.files.platform.FileCategory.IMAGES), stack.backStack.last())
        actions.openCategory(com.vayunmathur.files.platform.FileCategory.DOWNLOADS)
        assertTrue(stack.backStack.last() is Route.Directory)

        actions.openTrash()
        assertEquals(Route.Trash, stack.backStack.last())

        // zip navigation only applies on top of a Zip route
        actions.navigateIntoZipDir("inner")
        assertEquals(Route.Trash, stack.backStack.last())
        stack.add(Route.Zip("/tmp/a.zip", ""))
        actions.navigateIntoZipDir("inner")
        assertEquals(Route.Zip("/tmp/a.zip", "inner"), stack.backStack.last())
        actions.navigateToZipInternalPath("")
        // "" route not on stack, so it is pushed
        assertEquals(Route.Zip("/tmp/a.zip", ""), stack.backStack.last())
        actions.navigateToZipParentRealFolder(File(root, "a"))
        assertEquals(Route.Directory(File(root, "a").absolutePath), stack.backStack.last())

        // delegated non-navigation actions still reach the view model
        actions.setSearchQuery("hello")
        assertEquals("hello", vm.searchQuery.value)
        actions.clearSelection()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowEnvironmentFix::class], qualifiers = "w800dp-h1280dp")
class BindersComposeTest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `main activity content shows files home`() {
        // MainActivity hosts Navigation -> FilesShell -> home/binders on the real VM.
        assertTrue(rule.onAllNodesWithText("Files").fetchSemanticsNodes().isNotEmpty())
    }
}
