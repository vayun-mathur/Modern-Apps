package com.vayunmathur.translate.platform

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.translate.MainActivity
import com.vayunmathur.translate.data.SharedImageCache
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Share / Open with intents reaching the image translate screen (#846). */
@RunWith(RobolectricTestRunner::class)
class SharedImageIntentTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun installFakeKeyStore() {
            if (java.security.Security.getProvider("AndroidKeyStore") !is FakeAndroidKeyStoreProvider) {
                runCatching { java.security.Security.removeProvider("AndroidKeyStore") }
                java.security.Security.insertProviderAt(FakeAndroidKeyStoreProvider(), 1)
            }
        }

        private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(100)
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val image = Uri.parse("content://com.example.gallery/media/42")

    private fun send(uri: Uri?, type: String? = "image/jpeg") = Intent(Intent.ACTION_SEND).apply {
        if (uri != null) putExtra(Intent.EXTRA_STREAM, uri)
        this.type = type
    }

    private fun view(uri: Uri?, type: String? = "image/png") = Intent(Intent.ACTION_VIEW).apply {
        if (uri != null) setDataAndType(uri, type) else this.type = type
    }

    // --- parsing ----------------------------------------------------------------------

    @Test
    fun `send takes the stream extra`() {
        assertEquals(image, SharedImageIntent.imageUri(send(image)))
        assertEquals(image, SharedImageIntent.imageUri(send(image, type = "image/*")))
        assertEquals(image, SharedImageIntent.imageUri(send(image, type = null)))
    }

    @Test
    fun `view takes the data uri`() {
        assertEquals(image, SharedImageIntent.imageUri(view(image)))
        assertEquals(image, SharedImageIntent.imageUri(view(image, type = null)))
    }

    @Test
    fun `send without a stream or view without data carries nothing`() {
        assertNull(SharedImageIntent.imageUri(send(null)))
        assertNull(SharedImageIntent.imageUri(view(null)))
        // The data uri of a SEND is not its payload.
        assertNull(SharedImageIntent.imageUri(Intent(Intent.ACTION_SEND, image)))
    }

    @Test
    fun `other actions and a null intent carry nothing`() {
        assertNull(SharedImageIntent.imageUri(null))
        assertNull(SharedImageIntent.imageUri(Intent(Intent.ACTION_MAIN)))
        assertNull(
            SharedImageIntent.imageUri(
                Intent(Intent.ACTION_PROCESS_TEXT).putExtra(Intent.EXTRA_STREAM, image),
            ),
        )
        assertNull(SharedImageIntent.imageUri(Intent(Intent.ACTION_EDIT, image)))
    }

    @Test
    fun `non image types are rejected`() {
        assertNull(SharedImageIntent.imageUri(send(image, type = "text/plain")))
        assertNull(SharedImageIntent.imageUri(view(image, type = "application/pdf")))
        assertNull(SharedImageIntent.imageUri(send(image, type = "video/mp4")))
    }

    @Test
    fun `only content uris are accepted`() {
        val file = Uri.fromFile(File("/data/data/com.vayunmathur.translate/databases/history.db"))
        assertNull(SharedImageIntent.imageUri(send(file)))
        assertNull(SharedImageIntent.imageUri(view(file)))
        assertNull(SharedImageIntent.imageUri(view(Uri.parse("https://example.com/a.png"))))
    }

    @Test
    fun `a relaunch from recents is ignored`() {
        val intent = send(image).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(SharedImageIntent.imageUri(intent))
    }

    // --- ViewModel + activity ---------------------------------------------------------

    @Test
    fun `viewmodel copies a shared image into the cache and publishes its file uri`() {
        shadowOf(context.contentResolver).registerInputStream(image, ByteArrayInputStream(PNG))
        val vm = TranslateViewModel(ApplicationProvider.getApplicationContext<Application>())
        vm.openSharedImage(image)
        val arrival = assertIs<SharedImageArrival.Ready>(awaitArrival(vm))
        val uri = Uri.parse(arrival.imageUri)
        assertEquals("file", uri.scheme)
        val file = File(assertNotNull(uri.path))
        assertEquals(File(context.cacheDir, SharedImageCache.DIR_NAME), file.parentFile)
        assertTrue(file.readBytes().contentEquals(PNG))
        vm.consumeSharedImage()
        assertNull(vm.sharedImage.value)
    }

    @Test
    fun `viewmodel rejects shared content that is not an image`() {
        val pdf = Uri.parse("content://com.example.files/doc/7")
        shadowOf(context.contentResolver).registerInputStream(pdf, ByteArrayInputStream("%PDF-1.7".toByteArray()))
        val vm = TranslateViewModel(ApplicationProvider.getApplicationContext<Application>())
        vm.openSharedImage(pdf)
        val arrival = assertIs<SharedImageArrival.Rejected>(awaitArrival(vm))
        assertEquals(SharedImageCache.Result.NotAnImage, arrival.reason)
    }

    @Test
    fun `viewmodel reports an unreadable uri`() {
        val vm = TranslateViewModel(ApplicationProvider.getApplicationContext<Application>())
        vm.openSharedImage(Uri.parse("content://com.example.gone/never/registered"))
        val arrival = assertIs<SharedImageArrival.Rejected>(awaitArrival(vm))
        assertEquals(SharedImageCache.Result.Unreadable, arrival.reason)
    }

    @Test
    fun `main activity copies an image shared to it`() {
        val shared = Uri.parse("content://com.example.gallery/media/77")
        shadowOf(context.contentResolver).registerInputStream(shared, ByteArrayInputStream(PNG))
        val controller = Robolectric.buildActivity(MainActivity::class.java, send(shared, "image/png"))
            .create().start().resume()
        // The model is not downloaded here, so the navigation never consumes the
        // arrival: it waits in the activity's ViewModel, as it would behind the gate.
        val vm = ViewModelProvider(controller.get())[TranslateViewModel::class.java]
        assertIs<SharedImageArrival.Ready>(awaitArrival(vm))
        controller.pause().stop().destroy()
    }

    @Test
    fun `main activity handles an image delivered to a running instance`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_MAIN))
            .create().start().resume()
        val shared = Uri.parse("content://com.example.gallery/media/78")
        shadowOf(context.contentResolver).registerInputStream(shared, ByteArrayInputStream(PNG))
        val vm = ViewModelProvider(controller.get())[TranslateViewModel::class.java]
        assertNull(vm.sharedImage.value)
        controller.newIntent(view(shared, "image/png"))
        assertEquals(Intent.ACTION_VIEW, controller.get().intent.action)
        assertEquals(shared, SharedImageIntent.imageUri(controller.get().intent))
        val arrival = awaitArrival(vm)
        assertIs<SharedImageArrival.Ready>(arrival, "got $arrival")
        controller.pause().stop().destroy()
    }

    /** The copy runs on Dispatchers.IO and publishes on main: pump main until it lands. */
    private fun awaitArrival(vm: TranslateViewModel): SharedImageArrival? {
        awaitTrue { vm.sharedImage.value != null }
        return vm.sharedImage.value
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("condition not met within 10s")
    }
}
