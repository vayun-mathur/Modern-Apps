package com.vayunmathur.photos.util

import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.IndexCounts
import com.vayunmathur.photos.data.Person
import com.vayunmathur.photos.data.FaceGeometry
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.platform.FaceStore
import com.vayunmathur.photos.platform.GalleryViewModel
import com.vayunmathur.photos.platform.ImageLoader
import com.vayunmathur.photos.platform.PhotoScanStore
import com.vayunmathur.photos.platform.PhotoStore
import com.vayunmathur.photos.platform.ForegroundMediaObserver
import com.vayunmathur.photos.platform.MediaStoreRegistrar
import com.vayunmathur.photos.platform.OneTimeFlag
import com.vayunmathur.photos.platform.exifRunsInline
import com.vayunmathur.photos.platform.generationWatermarkAfter
import com.vayunmathur.photos.platform.syncLibrary
import com.vayunmathur.photos.platform.syncPhotos
import com.vayunmathur.photos.platform.syncTriggeredPhotos
import com.vayunmathur.photos.platform.triggeredItemUris
import com.vayunmathur.photos.platform.workInfosOverride
import com.vayunmathur.photos.ui.groupPhotosByMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * Gallery reload profile, in test terms (no device timing): how much work
 * stands between a fake repository holding N rows and the gallery's first
 * emission, and which upstream flows a cold ViewModel asks for.
 *
 * Before the load-path fixes this suite's counters read: getAllFlow()
 * invoked 3x per ViewModel (photos + people + albums each opened the DAO
 * flow), 4 separate count flows per write, and the grouping ran one
 * timezone conversion per photo. After: 1 shared upstream, 1 combined
 * counts flow, and one conversion per month-run of consecutive photos.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GalleryLoadProfileTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context.applicationContext as Application

    private fun photo(id: Long, date: Long, trashed: Boolean = false) = Photo(
        id = id,
        name = "p$id",
        uri = "content://media/external/images/media/$id",
        date = date,
        width = 100,
        height = 100,
        dateModified = date,
        exifSet = true,
        lat = null,
        long = null,
        videoData = null,
        panoData = null,
        isTrashed = trashed,
        album = "Camera",
        mimeType = "image/jpeg",
    )

    private class CountingPhotoStore(rows: List<Photo>) : PhotoStore {
        val source = MutableStateFlow(rows)
        var allFlowCalls = 0
        var individualCountCalls = 0
        var indexCountsCalls = 0

        override fun getAllFlow(): Flow<List<Photo>> {
            allFlowCalls++
            return source
        }

        // Override so the interface default (which derives favourites from
        // getAllFlow) does not add a second getAllFlow() invocation; the
        // production adapter serves favourites from its own DAO query.
        override fun getFavoritesFlow(): Flow<List<Photo>> =
            source.map { all -> all.filter { it.isFavorite && !it.isTrashed } }

        override fun getOCRCountFlow(): Flow<Int> { individualCountCalls++; return flowOf(0) }
        override fun getIndexTargetCountFlow(): Flow<Int> { individualCountCalls++; return flowOf(0) }
        override fun getClipCountFlow(): Flow<Int> { individualCountCalls++; return flowOf(0) }
        override fun getFaceScannedCountFlow(): Flow<Int> { individualCountCalls++; return flowOf(0) }
        override fun getIndexCountsFlow(): Flow<IndexCounts> {
            indexCountsCalls++
            return flowOf(IndexCounts(ocrCount = 7, clipCount = 8, faceScannedCount = 9, targetCount = 10))
        }

        override suspend fun searchPhotos(query: String): List<Photo> = emptyList()
        override suspend fun delete(photo: Photo): Int = 0
        override suspend fun setTrashed(id: Long) {}
        override suspend fun setAlbum(ids: List<Long>, album: String?) {}
        override suspend fun setFavorite(ids: List<Long>, value: Boolean) {}
        override suspend fun getByUri(uri: String): List<Photo> = emptyList()
        override suspend fun getAll(): List<Photo> = source.value
    }

    private class FakeScanStore : PhotoScanStore {
        override suspend fun getClipEmbeddings() = emptyList<com.vayunmathur.photos.data.PhotoEmbedding>()
    }

    private class FakeFaceStore : FaceStore {
        override fun personsFlow(): Flow<List<Person>> = flowOf(emptyList())
        override fun faceGeometryFlow(): Flow<List<FaceGeometry>> = flowOf(emptyList())
        override suspend fun photoIdsForCluster(clusterId: Long): List<Long> = emptyList()
        override suspend fun setPersonName(id: Long, name: String?) {}
    }

    /** A store whose library flow emits nothing until [gate] completes. */
    private class GatedPhotoStore(
        private val gate: kotlinx.coroutines.CompletableDeferred<List<Photo>>,
    ) : PhotoStore {
        override fun getAllFlow(): Flow<List<Photo>> =
            kotlinx.coroutines.flow.flow { emit(gate.await()) }
        override fun getFavoritesFlow(): Flow<List<Photo>> =
            kotlinx.coroutines.flow.flow { emit(gate.await().filter { it.isFavorite && !it.isTrashed }) }
        override fun getOCRCountFlow(): Flow<Int> = flowOf(0)
        override fun getIndexTargetCountFlow(): Flow<Int> = flowOf(0)
        override fun getClipCountFlow(): Flow<Int> = flowOf(0)
        override fun getFaceScannedCountFlow(): Flow<Int> = flowOf(0)
        override fun getIndexCountsFlow(): Flow<IndexCounts> = flowOf(IndexCounts())
        override suspend fun searchPhotos(query: String): List<Photo> = emptyList()
        override suspend fun delete(photo: Photo): Int = 0
        override suspend fun setTrashed(id: Long) {}
        override suspend fun setAlbum(ids: List<Long>, album: String?) {}
        override suspend fun setFavorite(ids: List<Long>, value: Boolean) {}
        override suspend fun getByUri(uri: String): List<Photo> = emptyList()
        override suspend fun getAll(): List<Photo> = emptyList()
    }

    private fun viewModel(store: PhotoStore): GalleryViewModel {
        workInfosOverride = { _, _ -> flowOf(emptyList<androidx.work.WorkInfo>()) }
        return GalleryViewModel(app, store, FakeScanStore(), FakeFaceStore()).also {
            it.runSyncHook = {}
        }
    }

    @Test
    fun firstEmissionFromFakeRepositoryWith2000RowsUsesOneUpstreamFlow() {
        val n = 2_000
        val base = 1_800_000_000_000L
        // Date-descending like the DAO, every 100th row trashed.
        val rows = (1..n).map { i ->
            photo(id = i.toLong(), date = base - i * 60_000L, trashed = i % 100 == 0)
        }
        val store = CountingPhotoStore(rows)
        val vm = viewModel(store)
        // Reading StateFlow.value alone never subscribes (WhileSubscribed
        // stays stopped), so collect in the background while polling.
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { vm.photos.collect {} }
        scope.launch { vm.galleryPhotos.collect {} }
        scope.launch { vm.albums.collect {} }

        val started = System.nanoTime()
        // Poll the StateFlow value while idling Robolectric's main looper:
        // viewModelScope is on Dispatchers.Main, which a plain runBlocking
        // would block forever (and a runTest virtual clock would time out
        // before the looper runs).
        val first = awaitValue { vm.photos.value.takeIf { it.size == n } }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        // Timing log for before/after comparison in test output; the
        // assertion is only that first emission arrives (no hardware claim).
        println("GalleryLoadProfile: N=$n items-to-first-emission=${elapsedMs}ms (test host)")
        assertEquals(n, first.size)
        assertEquals(rows.first().id, first.first().id)

        // The grid projection and the albums/people derivations share the
        // same upstream: still one getAllFlow() call for the whole ViewModel.
        val gallery = awaitValue { vm.galleryPhotos.value.takeIf { it.isNotEmpty() } }
        assertEquals(n - n / 100, gallery.size)
        assertTrue(gallery.none { it.isTrashed })
        awaitValue { vm.albums.value.takeIf { it.isNotEmpty() } }
        assertEquals(1, store.allFlowCalls, "one upstream flow for photos+galleryPhotos+albums+people")
        scope.cancel()
    }

    @Test
    fun galleryPhotosEmitUserVisibleItemsAndLoadingTransitionsToContent() {
        val gate = kotlinx.coroutines.CompletableDeferred<List<Photo>>()
        val vm = viewModel(GatedPhotoStore(gate))
        val scope = CoroutineScope(Dispatchers.Default)
        // The device path: the binder collects galleryPhotos (+ loading),
        // not the raw photos flow, so collect exactly those two.
        scope.launch { vm.galleryPhotos.collect {} }
        scope.launch { vm.galleryLoading.collect {} }

        // Before the first upstream emission the user-visible rows are the
        // StateFlow initial empty list, and the model must say "loading" —
        // not collapse into a genuine empty library.
        assertTrue(vm.galleryPhotos.value.isEmpty())
        assertEquals(true, awaitValue { vm.galleryLoading.value.takeIf { it } })

        gate.complete(
            listOf(
                photo(3L, 3_000L),
                photo(2L, 2_000L, trashed = true),
                photo(1L, 1_000L),
            )
        )
        val visible = awaitValue { vm.galleryPhotos.value.takeIf { it.size == 2 } }
        assertEquals(listOf(3L, 1L), visible.map { it.id })
        assertEquals(false, awaitValue { vm.galleryLoading.value.takeIf { !it } })
        scope.cancel()
    }

    @Test
    fun galleryLoadingClearsOnGenuinelyEmptyFirstEmission() {
        val gate = kotlinx.coroutines.CompletableDeferred<List<Photo>>()
        val vm = viewModel(GatedPhotoStore(gate))
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { vm.galleryPhotos.collect {} }
        scope.launch { vm.galleryLoading.collect {} }
        assertEquals(true, awaitValue { vm.galleryLoading.value.takeIf { it } })
        gate.complete(emptyList())
        // An emitted empty library is a real empty (the screen may now show
        // its empty state): loading cleared even though rows stayed empty.
        assertEquals(false, awaitValue { vm.galleryLoading.value.takeIf { !it } })
        assertTrue(vm.galleryPhotos.value.isEmpty())
        scope.cancel()
    }

    @Test
    fun triggeredSyncNeverAdvancesGenerationWatermark() {
        // A triggered sync imports only its URIs; the watermark may only move
        // after a generation-complete sync, else every other change at or
        // below that generation is skipped by all later incremental syncs.
        assertNull(generationWatermarkAfter(triggered = true, currentGeneration = 99L))
        assertEquals(99L, generationWatermarkAfter(triggered = false, currentGeneration = 99L))
    }

    @Test
    fun exifTrailsTriggeredSyncsButRunsInlineForGenerationCompleteSyncs() {
        // 0e2d54511 invariant extended: the watermark still only advances
        // after an inline EXIF pass on a generation-complete sync, but a
        // triggered sync defers EXIF to ExifWorker — its rows are already
        // upserted/visible, so the backlog cannot delay them.
        assertEquals(false, exifRunsInline(triggered = true))
        assertEquals(true, exifRunsInline(triggered = false))
    }

    @Test
    fun triggeredForegroundImportIsItemOnlyAndCollectionUriNeverWidensToFullScan() = runBlocking {
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, OneRowProvider())
        val repository = FakePhotosRepository(context)

        // Collection-level callback (no item id): importing nothing is the
        // contract — widening this to syncPhotos(null-selection) would be a
        // full provider scan on the foreground path.
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        assertEquals(emptyList(), triggeredItemUris(listOf(collection)))
        assertEquals(false, syncTriggeredPhotos(context, repository, listOf(collection)))
        assertTrue(repository.upsertedBatches.isEmpty())
        assertTrue(repository.deletedBatches.isEmpty())

        // One new item URI: exactly that row is upserted, with enrichment
        // fields still unset (EXIF trails the visible row).
        val item = android.content.ContentUris.withAppendedId(collection, 1L)
        assertEquals(listOf(item), triggeredItemUris(listOf(item, collection)))
        assertEquals(true, syncTriggeredPhotos(context, repository, listOf(item)))
        val imported = repository.upsertedBatches.single().single()
        assertEquals(1L, imported.id)
        assertEquals(false, imported.exifSet)
        assertEquals(null, imported.lat)
        Unit
    }

    private class RecordingRegistrar : MediaStoreRegistrar {
        val registeredUris = mutableListOf<Uri>()
        var unregisterCalls = 0
        override fun register(
            observer: android.database.ContentObserver,
            uri: Uri,
            notifyForDescendants: Boolean,
        ) {
            registeredUris += uri
        }

        override fun unregister(observer: android.database.ContentObserver) {
            unregisterCalls++
        }
    }

    @Test
    fun foregroundObserverRegistersOnStartUnregistersOnStopWithoutLeak() {
        val registrar = RecordingRegistrar()
        val scope = CoroutineScope(Dispatchers.Default)
        val received = mutableListOf<List<Uri>>()
        val observer = ForegroundMediaObserver(registrar, scope, debounceMs = 50L) { uris ->
            synchronized(received) { received += uris }
        }
        assertEquals(false, observer.isRegistered)

        observer.start()
        assertEquals(true, observer.isRegistered)
        assertEquals(ForegroundMediaObserver.OBSERVED_URIS, registrar.registeredUris)
        // Repeat start is a no-op: no double registration to leak.
        observer.start()
        assertEquals(2, registrar.registeredUris.size)

        // A pending debounced import is cancelled by stop, not delivered
        // after the observer is gone.
        val item = Uri.parse("content://media/external/images/media/9")
        observer.onMediaChanged(item)
        observer.stop()
        assertEquals(false, observer.isRegistered)
        assertEquals(1, registrar.unregisterCalls)
        // Repeat stop is a no-op.
        observer.stop()
        assertEquals(1, registrar.unregisterCalls)
        Thread.sleep(200)
        synchronized(received) { assertTrue(received.isEmpty(), received.toString()) }

        // Late callbacks after stop are dropped; a restart re-registers.
        observer.onMediaChanged(item)
        observer.start()
        assertEquals(true, observer.isRegistered)
        assertEquals(4, registrar.registeredUris.size)
        observer.stop()
        assertEquals(2, registrar.unregisterCalls)
        scope.cancel()
    }

    @Test
    fun foregroundObserverDebouncesAndDedupesBurstIntoOneTriggeredImport() {
        val registrar = RecordingRegistrar()
        val scope = CoroutineScope(Dispatchers.Default)
        val received = mutableListOf<List<Uri>>()
        val observer = ForegroundMediaObserver(registrar, scope, debounceMs = 50L) { uris ->
            synchronized(received) { received += uris }
        }
        observer.start()
        val a = Uri.parse("content://media/external/images/media/9")
        val b = Uri.parse("content://media/external/video/media/10")
        // One capture firing several callbacks (incl. a duplicate) coalesces.
        observer.onMediaChanged(a)
        observer.onMediaChanged(a)
        observer.onMediaChanged(b)
        observer.onMediaChanged(null)
        val batch = awaitValue {
            synchronized(received) { received.singleOrNull() }
        }
        assertEquals(listOf(a, b), batch)
        Thread.sleep(150)
        synchronized(received) { assertEquals(1, received.size) }
        observer.stop()
        scope.cancel()
    }

    /** Idles the paused main looper until [read] yields a value (bounded, real time). */
    private fun <T> awaitValue(read: () -> T?): T {
        val looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        repeat(500) {
            looper.idle()
            read()?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("condition not met within 5s of looper polling")
    }

    @Test
    fun indexCountsComeFromOneCombinedFlow() {
        val store = CountingPhotoStore(listOf(photo(1L, 1_000L)))
        val vm = viewModel(store)
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { vm.ocrCount.collect {} }
        scope.launch { vm.clipCount.collect {} }
        scope.launch { vm.faceScannedCount.collect {} }
        scope.launch { vm.indexTargetCount.collect {} }
        assertEquals(7, awaitValue { vm.ocrCount.value.takeIf { it == 7 } })
        assertEquals(8, awaitValue { vm.clipCount.value.takeIf { it == 8 } })
        assertEquals(9, awaitValue { vm.faceScannedCount.value.takeIf { it == 9 } })
        assertEquals(10, awaitValue { vm.indexTargetCount.value.takeIf { it == 10 } })
        scope.cancel()
        assertEquals(1, store.indexCountsCalls)
        assertEquals(0, store.individualCountCalls)
    }

    @Test
    fun groupingLargeSortedLibraryKeepsMonthOrderAndRowOrder() {
        // Mid-month timestamps (Jan/Feb 2024) so the device zone cannot
        // push a row across a month boundary.
        val feb = (1..500).map { photo(it.toLong(), 1_707_500_000_000L - it * 1_000L) }
        val jan = (501..2000).map { photo(it.toLong(), 1_705_300_000_000L - it * 1_000L) }
        val grouped = groupPhotosByMonth(feb + jan, context.resources)
        assertEquals(2, grouped.size)
        val groups = grouped.values.toList()
        assertEquals(feb.map { it.id }, groups[0].map { it.id })
        assertEquals(jan.map { it.id }, groups[1].map { it.id })
    }

    @Test
    fun thumbnailRequestIsUnsizedSoAsyncImageDerivesDecodeSizeFromTile() {
        val request = ImageLoader.thumbnailRequest(context, photo(42L, 1_000L))
        // Null size is the contract: AsyncImage's deriveDecodeSize then
        // sizes the decode to the tile's layout constraints instead of a
        // fixed 256px guess (or a full-resolution decode).
        assertNull(request.size)
        assertEquals("thumb_42_1000", request.memoryCacheKey)
    }

    // ---- sync: incremental scans must not rescan the whole provider ----

    private class FakePhotosRepository(context: Context) : PhotosRepository(context) {
        var all: List<Photo> = emptyList()
        val deletedBatches = mutableListOf<List<Long>>()
        val upsertedBatches = mutableListOf<List<Photo>>()

        override suspend fun getAllForSync(): List<Photo> = all
        override suspend fun deleteByIds(ids: List<Long>) { deletedBatches += ids }
        override suspend fun upsertAll(photos: List<Photo>) { upsertedBatches += photos }
        override suspend fun setFavorite(ids: List<Long>, value: Boolean) {}
    }

    private class OneRowProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor = throw UnsupportedOperationException("bundle query only")

        override fun query(
            uri: Uri, projection: Array<out String>?, queryArgs: Bundle?,
            cancellationSignal: android.os.CancellationSignal?,
        ): Cursor {
            val columns = projection ?: COLUMNS
            val cursor = MatrixCursor(columns)
            if (!uri.toString().contains("/video/")) {
                val byName = COLUMNS.zip(
                    arrayOf<Any?>(1L, "p1", 1_000L, 1L, 100L, 100, 100, 0, "image/jpeg", "Camera", 0, null),
                ).toMap()
                cursor.addRow(columns.map { byName[it] })
            }
            return cursor
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(
            uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
        ) = 0

        companion object {
            val COLUMNS = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_TAKEN,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT,
                MediaStore.MediaColumns.IS_TRASHED,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.IS_FAVORITE,
                MediaStore.Video.Media.DURATION,
            )
        }
    }

    private fun syncPhoto(id: Long) = Photo(
        id = id, name = "p$id", uri = "content://media/external/images/media/$id",
        date = 1_000L, width = 100, height = 100, dateModified = 100L,
        exifSet = true, lat = null, long = null, videoData = null, panoData = null,
        isTrashed = false, mimeType = "image/jpeg", album = "Camera",
    )

    @Test
    fun incrementalSyncSkipsFullDeletionScanFullSyncStillReconciles() = runBlocking {
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, OneRowProvider())
        val repository = FakePhotosRepository(context).apply {
            all = listOf(syncPhoto(1L), syncPhoto(2L))
        }
        // Incremental (generation moved): provider row 1 is unchanged, row 2
        // is simply absent from the generation window — it must NOT be
        // deleted by a full-library ID rescan on this path.
        syncPhotos(context, repository, null, 42L)
        assertTrue(repository.deletedBatches.isEmpty(), repository.deletedBatches.toString())

        // Full scan: the same absence is a deletion and is reconciled.
        syncPhotos(context, repository, null, 0L)
        assertEquals(listOf(listOf(2L)), repository.deletedBatches)
        Unit
    }

    private class FakeFlag(initial: Boolean) : OneTimeFlag {
        var value = initial
        override suspend fun isSet(): Boolean = value
        override suspend fun set() { value = true }
    }

    @Test
    fun generationRecoveryForcesOneFullScanThenReturnsToIncremental() = runBlocking {
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, OneRowProvider())
        val repository = FakePhotosRepository(context).apply {
            all = listOf(syncPhoto(1L), syncPhoto(2L))
        }
        val favoritesDone = FakeFlag(initial = true)
        val recovery = FakeFlag(initial = false)

        // Despite a moved generation (42), the owed recovery pass is a full
        // scan: row 2's absence is reconciled (a poisoned watermark's missed
        // cohort is re-walked by the un-bounded selection) and the flag set.
        syncLibrary(context, repository, 42L, false, favoritesDone, recovery)
        assertEquals(listOf(listOf(2L)), repository.deletedBatches)
        assertTrue(recovery.value)

        // Recovery runs once: the next library sync is incremental again.
        repository.deletedBatches.clear()
        syncLibrary(context, repository, 42L, false, favoritesDone, recovery)
        assertTrue(repository.deletedBatches.isEmpty(), repository.deletedBatches.toString())
        Unit
    }
}
