package com.vayunmathur.backup.data.backend

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.backup.FakeDocumentsProvider
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.File
import java.io.FileNotFoundException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(shadows = [com.vayunmathur.backup.DocsContentResolverShadow::class])
class SafBackendTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var backend: SafBackend
    private lateinit var root: File

    @Before fun setUp() {
        root = File(ctx.cacheDir, "saf-${System.nanoTime()}").apply { mkdirs() }
        FakeDocumentsProvider.rootDir = root
        val provider = FakeDocumentsProvider()
        provider.attachInfo(ctx, android.content.pm.ProviderInfo().apply {
            authority = FakeDocumentsProvider.AUTHORITY; exported = true; grantUriPermissions = true
            readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
            writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
        })
        registerProvider(provider)
        val treeUri = DocumentsContract.buildTreeDocumentUri(FakeDocumentsProvider.AUTHORITY, FakeDocumentsProvider.ROOT_ID)
        backend = SafBackend(ctx, treeUri)
    }

    private fun registerProvider(provider: FakeDocumentsProvider) {
        try {
            val m = Class.forName("org.robolectric.shadows.ShadowContentResolver")
                .getMethod("registerProviderInternal", String::class.java, android.content.ContentProvider::class.java)
            m.invoke(null, FakeDocumentsProvider.AUTHORITY, provider)
        } catch (e: Exception) {
            // Fallback: ContentProviderMap via shadow instance
            val shadow = Shadows.shadowOf(ctx.contentResolver)
            val m = shadow.javaClass.methods.firstOrNull { it.name.contains("registerProvider") }
            if (m != null) {
                val params = m.parameterTypes
                when {
                    params.size == 2 -> m.invoke(shadow, FakeDocumentsProvider.AUTHORITY, provider)
                    else -> throw e
                }
            } else throw e
        }
    }

    @Test fun writeReadListExistsDeleteRoundTrip() = runBlocking<Unit> {
        assertTrue(backend.displayName.isNotEmpty())
        backend.ensureDir("kv/com.example")
        backend.ensureDir("kv/com.example") // idempotent
        assertTrue(backend.exists("kv/com.example"))
        backend.save("kv/com.example/k1", "value1".toByteArray())
        backend.save("kv/com.example/k2", byteArrayOf(0, 1, 2))
        assertTrue(backend.exists("kv/com.example/k1"))
        assertFalse(backend.exists("kv/com.example/nope"))
        assertContentEquals("value1".toByteArray(), backend.load("kv/com.example/k1"))
        assertEquals(listOf("k1", "k2"), backend.list("kv/com.example"))
        assertEquals(emptyList(), backend.list("no/such/dir"))
        // Overwrite
        backend.save("kv/com.example/k1", "changed".toByteArray())
        assertEquals("changed", backend.load("kv/com.example/k1").decodeToString())
        // Root file + nested creation via write
        backend.write("full/pkg.tar") { it.write("tar".toByteArray()) }
        assertEquals("tar", backend.read("full/pkg.tar") { it.readBytes().decodeToString() })
        backend.delete("kv/com.example/k1")
        assertFalse(backend.exists("kv/com.example/k1"))
        backend.delete("kv/com.example/k1") // no-op
        backend.delete("missing/deep/file") // no-op parent missing
        assertFailsWith<FileNotFoundException> { backend.load("kv/com.example/k1") }
        assertTrue(backend.exists(""))
        // Verify on-disk layout
        assertTrue(File(root, "kv/com.example/k2").isFile)
        assertEquals(3, File(root, "kv/com.example/k2").length().toInt())
    }

    @Test fun ensureDirBlockedByFile() = runBlocking<Unit> {
        backend.save("blocker", "x".toByteArray())
        assertFailsWith<Exception> { backend.ensureDir("blocker/sub") }
        assertFailsWith<Exception> { backend.save("blocker/sub", "y".toByteArray()) }
    }

    @Test fun displayNameFromUri() {
        val uri = Uri.parse("content://com.example/tree/primary%3AMyFolder")
        // SafBackend needs DocumentsContract tree handling only for ops; displayName uses lastPathSegment.
        val b = SafBackend(ctx, DocumentsContract.buildTreeDocumentUri(FakeDocumentsProvider.AUTHORITY, "root"))
        assertTrue(b.displayName.isNotEmpty())
        assertEquals("root", b.displayName)
    }
}
