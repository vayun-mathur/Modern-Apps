package com.vayunmathur.photos.util

import android.app.Application
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Pass-3 tests for [WallpaperViewModel], [WallpaperUtil] and [LiveWallpaperLauncher]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WallpaperPass3Test {

    private val app: Application
        get() = ApplicationProvider.getApplicationContext<Context>() as Application
    private val ctx: Context get() = app

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out: $desc")
    }

    private fun writePng(file: File, w: Int = 60, h: Int = 40): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.BLUE)
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return file
    }

    @Test
    fun viewModelLoadsRealPngAndFailsForBadUri() {
        val dir = File(System.getProperty("java.io.tmpdir"), "wp-${System.nanoTime()}").also { it.mkdirs() }
        val png = writePng(File(dir, "wp.png"))
        val vm = WallpaperViewModel(app)
        // Robolectric's ImageDecoder shadow NPEs, so decode via the VM's seam with
        // a real bitmap (the production default still uses ImageDecoder).
        vm.bitmapDecoder = { _, _ -> Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888) }
        assertEquals(WallpaperLoadState.Idle, vm.loadState.value)
        vm.load(android.net.Uri.fromFile(png).toString())
        await("loaded") { vm.loadState.value == WallpaperLoadState.Loaded }
        assertEquals(60, vm.bitmap.value?.width)
        // Loading again while not loading is allowed (state stays Loaded).
        vm.load(android.net.Uri.fromFile(png).toString())
        await("loaded again") { vm.loadState.value == WallpaperLoadState.Loaded }

        val vm2 = WallpaperViewModel(app)
        vm2.bitmapDecoder = { _, _ -> throw java.io.IOException("no such image") }
        vm2.load("content://nonexistent/image/1")
        await("failed") { vm2.loadState.value == WallpaperLoadState.Failed }
        dir.deleteRecursively()
    }

    @Test
    fun viewModelFactoryCreates() {
        val vm = WallpaperViewModelFactory(app).create(
            WallpaperViewModel::class.java, androidx.lifecycle.viewmodel.CreationExtras.Empty,
        )
        assertTrue(vm is WallpaperViewModel)
    }

    @Test
    fun computeCropRectMath() {
        // No zoom, viewport = container: whole source.
        val whole = WallpaperUtil.computeCropRect(100, 50, 100f, 50f, 1f, 0f, 0f, 100f, 50f, 100f, 50f)
        assertEquals(Rect(0, 0, 100, 50), whole)
        // Degenerate inputs fall back to a clamped full rect.
        val fallback = WallpaperUtil.computeCropRect(0, 0, 0f, 0f, 1f, 0f, 0f, 10f, 10f, 10f, 10f)
        assertEquals(Rect(0, 0, 1, 1), fallback)
        // Zoomed 2x and panned: crop is a sub-rect inside the source.
        val zoomed = WallpaperUtil.computeCropRect(200, 200, 100f, 100f, 2f, 0f, 0f, 100f, 100f, 100f, 100f)
        assertTrue(zoomed.width() in 1..200 && zoomed.height() in 1..200, "zoomed=$zoomed")
        assertNotEquals(Rect(0, 0, 200, 200), zoomed)
    }

    @Test
    fun coverMinScaleMath() {
        assertEquals(1f, WallpaperUtil.coverMinScale(0f, 10f, 100f, 100f))
        assertEquals(2f, WallpaperUtil.coverMinScale(50f, 100f, 100f, 100f))
        assertEquals(1f, WallpaperUtil.coverMinScale(200f, 200f, 100f, 100f))
    }

    @Test
    fun setWallpaperAppliesBitmap() = runBlocking {
        val source = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.GREEN)
        }
        val result = WallpaperUtil.setWallpaper(
            context = ctx, source = source, viewport = Rect(0, 0, 80, 60),
            targetWidth = 40, targetHeight = 30,
            which = WallpaperManager.FLAG_SYSTEM, isScrollable = false,
            screenW = 40, screenH = 30,
        )
        assertTrue(result is WallpaperUtil.SetResult.Success, "result=$result")
        source.recycle()
    }

    @Test
    fun setWallpaperScrollableAndCombinedFlags() = runBlocking {
        val source = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        val result = WallpaperUtil.setWallpaper(
            context = ctx, source = source, viewport = Rect(100, 100, 10, 10),
            targetWidth = 20, targetHeight = 20,
            which = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK,
            isScrollable = true, screenW = 20, screenH = 20,
        )
        // Out-of-bounds viewport is clamped, not a crash.
        assertTrue(result is WallpaperUtil.SetResult.Success, "result=$result")
        source.recycle()
    }

    @Test
    fun liveWallpaperApplyAttemptsLaunchAndPersistsChoice() = runBlocking {
        LiveWallpaperLauncher.apply(ctx, "content://media/42", isVideo = true)
        val ds = com.vayunmathur.library.util.DataStoreUtils.getInstance(ctx)
        // DataStore writes are async; poll for the persisted URI.
        val deadline = System.currentTimeMillis() + 10_000
        var uri: String? = null
        while (System.currentTimeMillis() < deadline) {
            uri = ds.getString(com.vayunmathur.photos.service.LiveWallpaperService.KEY_WALLPAPER_URI)
            if (uri != null) break
            Thread.sleep(50)
        }
        assertEquals("content://media/42", uri)
        assertEquals(true, ds.getBoolean(com.vayunmathur.photos.service.LiveWallpaperService.KEY_WALLPAPER_IS_VIDEO, false))
    }
}
