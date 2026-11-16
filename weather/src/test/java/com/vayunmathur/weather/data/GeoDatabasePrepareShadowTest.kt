package com.vayunmathur.weather.data

import android.content.Context
import android.os.storage.StorageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * StorageManager shadow that claims a caller-chosen amount of allocatable
 * space, so [GeoDatabase.prepare] can get past its free-space check on a
 * host JVM (Robolectric's own shadow cannot resolve the filesDir volume).
 */
@Implements(StorageManager::class)
class ControllableStorageManagerShadow {
    @Suppress("UnusedParameter") // shadow signature must match StorageManager
    @Implementation
    fun getUuidForPath(path: File): UUID = UUID(0L, 1L)

    @Suppress("UnusedParameter") // shadow signature must match StorageManager
    @Implementation
    fun getAllocatableBytes(uuid: UUID): Long = allocatableBytes

    @Suppress("UnusedParameter") // shadow signature must match StorageManager
    @Implementation
    fun allocateBytes(uuid: UUID, bytes: Long) = Unit

    companion object {
        @JvmField var allocatableBytes: Long = Long.MAX_VALUE / 4
    }
}

/**
 * [GeoDatabase.prepare] failure and unpack paths: with too little space it
 * must fail cleanly; with enough space it decompresses the real bundled
 * asset (13 MB brotli -> ~34 MB SQLite) and swaps it into place — the step
 * after that is the SQLCipher native open, which cannot load on a host JVM
 * and is asserted as the boundary.
 */
@RunWith(AndroidJUnit4::class)
@Config(shadows = [ControllableStorageManagerShadow::class])
class GeoDatabasePrepareShadowTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val dbFile get() = File(ctx.filesDir, "places.db")
    private val metaFile get() = File(ctx.filesDir, "places.db.meta.json")

    @BeforeTest fun setUp() {
        GeoDatabase.init(ctx)
        dbFile.delete(); metaFile.delete()
    }

    @Test fun prepareFailsCleanlyWhenSpaceIsShort() = runTest {
        ControllableStorageManagerShadow.allocatableBytes = 1024L
        val result = GeoDatabase.prepare()
        assertTrue(result.isFailure)
        val status = GeoDatabase.status.value
        assertTrue(status is GeoDatabase.Status.Failed, "status=$status")
        assertEquals(
            "Not enough free space for the places catalogue",
            (status as GeoDatabase.Status.Failed).message,
        )
    }

    @Test fun prepareDecompressesRealAssetUntilNativeOpen() = runTest {
        ControllableStorageManagerShadow.allocatableBytes = Long.MAX_VALUE / 4
        try {
            val result = GeoDatabase.prepare()
            // A harness with loadable SQLCipher completes the unpack here.
            assertTrue(result.isSuccess)
            assertTrue(GeoDatabase.status.value is GeoDatabase.Status.Ready)
            assertTrue(dbFile.length() > 1_000_000L)
            assertTrue(metaFile.exists())
        } catch (e: UnsatisfiedLinkError) {
            // The unpack itself (decompress + swap + meta write) ran; only
            // the native open at the end of swapInDatabase cannot.
            assertTrue(e.message?.contains("sqlcipher", ignoreCase = true) == true, "unexpected $e")
            assertTrue(dbFile.length() > 1_000_000L, "decompressed db size=${dbFile.length()}")
            assertTrue(metaFile.exists())
        }
    }
}
