package com.vayunmathur.library.avatar

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class YuuAvatarAssetsTest {

    @Test
    fun `bundled mesh path is the documented asset`() {
        assertEquals("models/base_upperbody.glb", YuuAvatarAssets.BASE_MESH_PATH)
    }

    @Test
    fun `adopt tolerates a missing bundled mesh asset`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // No base_upperbody.glb is bundled for unit tests, so openFd fails and
        // the procedural fallback path must swallow it quietly.
        YuuAvatarAssets.adoptBundledMesh(context.assets)
    }

    @Test
    fun `handOff detaches the fd into native`() {
        val file = java.io.File.createTempFile("mesh", ".glb")
        try {
            file.writeBytes(ByteArray(256) { 0x67 })
            val pfd = android.os.ParcelFileDescriptor.open(
                file,
                android.os.ParcelFileDescriptor.MODE_READ_ONLY,
            )
            val afd = android.content.res.AssetFileDescriptor(pfd, 0, file.length())
            // Ownership moves to native; this must not throw even though the
            // bytes are not a real mesh (parsing happens on surface creation).
            YuuAvatarAssets.handOff(afd)
        } finally {
            file.delete()
        }
    }
}
