package com.vayunmathur.files

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.files.platform.FileBrowserItem
import com.vayunmathur.files.platform.FilesActions
import com.vayunmathur.files.platform.FilesUiState
import com.vayunmathur.files.platform.HomeUiState
import com.vayunmathur.files.platform.StorageInfo
import com.vayunmathur.files.platform.TrashUiState
import com.vayunmathur.files.ui.DirectoryScreen
import com.vayunmathur.files.ui.FilesDrawer
import com.vayunmathur.files.ui.HomeScreen
import com.vayunmathur.files.ui.TrashScreen
import com.vayunmathur.library.ui.DrawerValue
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.ModalDrawerSheet
import com.vayunmathur.library.ui.ModalNavigationDrawer
import com.vayunmathur.library.ui.rememberDrawerState
import java.io.File

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Expanded desktop/tablet window for the main (home) screen. */
private const val EXPANDED = "spec:width=1280dp,height=800dp,dpi=240"

private const val ROOT = "/storage/emulated/0"

/**
 * Store listing images for `:files`. See `common-conventions-preview-metadata`.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1…, Preview2…).
 *
 * The [File]s below are never touched — the browser only reads the names and sizes it is
 * handed — so these render without a filesystem, a device, or the all-files permission the
 * real app needs before it shows anything at all.
 *
 * Coverage: every `Route` destination — `Home`, `Directory`, `Category`, `Trash` and
 * `Zip` on phone, plus `Home` (main) on large-tablet. The text editor is a separate
 * activity (`TextEditorActivity`, ViewModel + SAF URI), not a `Route` destination.
 */
class MetadataPreviews {

    private fun dir(path: String) = FileBrowserItem(
        name = path.substringAfterLast('/'),
        isDirectory = true,
        size = null,
        realFile = File(path),
        zipInnerPath = null,
        key = path,
    )

    private fun file(path: String, size: Long) = FileBrowserItem(
        name = path.substringAfterLast('/'),
        isDirectory = false,
        size = size,
        realFile = File(path),
        zipInnerPath = null,
        key = path,
    )

    private fun zipDir(inner: String) = FileBrowserItem(
        name = inner.substringAfterLast('/'),
        isDirectory = true,
        size = null,
        realFile = null,
        zipInnerPath = inner,
        key = "zip:$inner",
    )

    private fun zipFile(inner: String, size: Long) = FileBrowserItem(
        name = inner.substringAfterLast('/'),
        isDirectory = false,
        size = size,
        realFile = null,
        zipInnerPath = inner,
        key = "zip:$inner",
    )

    @Composable
    private fun BrowseContent() {
        DirectoryScreen(
            state = FilesUiState(
                rootDirectory = File(ROOT),
                rootDisplayName = "Pixel 9 Pro",
                currentDirectory = File(ROOT),
                directories = listOf(
                    dir("$ROOT/DCIM"),
                    dir("$ROOT/Documents"),
                    dir("$ROOT/Download"),
                    dir("$ROOT/Movies"),
                    dir("$ROOT/Music"),
                    dir("$ROOT/Pictures"),
                ),
                files = listOf(
                    file("$ROOT/backup-2026-07.zip", 48_301_772),
                    file("$ROOT/reading-list.md", 12_408),
                ),
            ),
            actions = FilesActions.Noop,
        )
    }

    @PreviewTest
    @Preview(name = "1-browse", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Browse() {
        DynamicTheme(darkTheme = true) {
            BrowseContent()
        }
    }

    @PreviewTest
    @Preview(name = "2-selection", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Selection() {
        val downloads = "$ROOT/Download"
        val receipts = file("$downloads/receipts-june.pdf", 184_320)
        val slides = file("$downloads/offsite-slides.pdf", 2_517_912)
        DynamicTheme(darkTheme = true) {
            DirectoryScreen(
                state = FilesUiState(
                    rootDirectory = File(ROOT),
                    rootDisplayName = "Pixel 9 Pro",
                    currentDirectory = File(downloads),
                    directories = listOf(dir("$downloads/invoices")),
                    files = listOf(
                        file("$downloads/IMG_2481.jpg", 3_145_728),
                        slides,
                        receipts,
                        file("$downloads/trip-notes.txt", 4_096),
                    ),
                    selectedPaths = setOf(slides, receipts),
                ),
                actions = FilesActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-zip", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3Zip() {
        DynamicTheme(darkTheme = true) {
            DirectoryScreen(
                state = FilesUiState(
                    rootDirectory = File(ROOT),
                    rootDisplayName = "Pixel 9 Pro",
                    // Zip mode ignores the current directory; the breadcrumb comes from the
                    // archive's own path, exactly as the ViewModel leaves it.
                    currentDirectory = File("/"),
                    zipPath = File("$ROOT/Download/backup-2026-07.zip"),
                    directories = listOf(zipDir("photos"), zipDir("receipts")),
                    files = listOf(
                        zipFile("manifest.json", 2_048),
                        zipFile("notes.md", 9_512),
                        zipFile("settings.xml", 1_204),
                    ),
                ),
                actions = FilesActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "4-trash", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview4Trash() {
        DynamicTheme(darkTheme = true) {
            TrashScreen(
                state = TrashUiState(
                    items = listOf(
                        file("$ROOT/Download/receipts-june.pdf", 184_320),
                        file("$ROOT/DCIM/IMG_2481.jpg", 3_145_728),
                    ),
                ),
                actions = FilesActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "5-drawer", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview5Drawer() {
        DynamicTheme(darkTheme = true) {
            ModalNavigationDrawer(
                drawerState = rememberDrawerState(DrawerValue.Open),
                drawerContent = {
                    ModalDrawerSheet {
                        FilesDrawer(
                            bookmarks = listOf(
                                dir("$ROOT/Documents"),
                                file("$ROOT/reading-list.md", 12_408),
                            ),
                            actions = FilesActions.Noop,
                            closeDrawer = {},
                        )
                    }
                },
            ) {
                BrowseContent()
            }
        }
    }

    @Composable
    private fun HomeContent() {
        HomeScreen(
            home = HomeUiState(
                rootDisplayName = "Pixel 9 Pro",
                storage = StorageInfo(totalBytes = 256_000_000_000, freeBytes = 84_000_000_000),
                recents = listOf(
                    file("$ROOT/Download/receipts-june.pdf", 184_320),
                    file("$ROOT/DCIM/IMG_2481.jpg", 3_145_728),
                ),
                bookmarks = listOf(dir("$ROOT/Documents")),
            ),
            actions = FilesActions.Noop,
        )
    }

    @PreviewTest
    @Preview(name = "6-home", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview6Home() {
        DynamicTheme(darkTheme = true) {
            HomeContent()
        }
    }

    @PreviewTest
    @Preview(name = "7-category", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview7Category() {
        DynamicTheme(darkTheme = true) {
            DirectoryScreen(
                state = FilesUiState(
                    rootDirectory = File(ROOT),
                    rootDisplayName = "Pixel 9 Pro",
                    currentDirectory = File(ROOT),
                    categoryTitle = "Images",
                    files = listOf(
                        file("$ROOT/DCIM/IMG_2481.jpg", 3_145_728),
                        file("$ROOT/Pictures/offsite.png", 1_204_112),
                    ),
                ),
                actions = FilesActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "8-home-expanded", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    fun Preview8HomeExpanded() {
        DynamicTheme(darkTheme = true) {
            HomeContent()
        }
    }
}
