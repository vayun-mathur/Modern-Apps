package com.vayunmathur.photos.util

import com.vayunmathur.photos.domain.FaceClusterConfig
import android.app.Application
import android.database.sqlite.SQLiteException
import android.net.Uri
import com.vayunmathur.library.log.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vayunmathur.photos.data.FaceRepository
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoScanRepository
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.library.util.DataStoreUtils
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import androidx.work.WorkInfo
import androidx.work.WorkManager

/**
 * ViewModel for the photos gallery screen.
 *
 * Owns:
 *  - the observable list of [Photo]s (backed by the DAO Flow)
 *  - OCR search query and asynchronously fetched search results
 *  - multi-select state (set of photo ids)
 *  - OCR feature-enabled flag (DataStore) and OCR progress counters (Flow)
 *  - sync worker entry-point side effects
 */
@OptIn(FlowPreview::class)
class GalleryViewModel(
    application: Application,
    private val repository: PhotosRepository,
    private val scanRepository: PhotoScanRepository = PhotoScanRepository.get(application),
    private val faceRepository: FaceRepository = FaceRepository.get(application),
) : AndroidViewModel(application), GalleryActions {

    private val dataStore = DataStoreUtils.getInstance(application)

    /**
     * User-chosen album covers, `albumName -> photoId`. MediaStore has no per-folder
     * cover concept, so this is the one piece of app-side album *metadata* (not
     * membership, which stays in MediaStore folders). Seeded from DataStore in
     * [init] and folded into [albums]; a null/absent entry falls back to the
     * newest photo in the bucket.
     */
    private val _albumCovers = MutableStateFlow<Map<String, Long>>(emptyMap())

    /**
     * A pending album move awaiting the MediaStore write-consent result. Set by
     * [requestAlbumMove] before the binder launches [android.provider.MediaStore.createWriteRequest],
     * consumed by [consumePendingMove] on RESULT_OK. Held here rather than in
     * Compose state so the launcher callback never reads a stale closure.
     */
    private var pendingMove: Pair<List<Photo>, String?>? = null

    val photos: StateFlow<List<Photo>> = repository.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Photo>>(emptyList())
    val searchResults: StateFlow<List<Photo>> = _searchResults.asStateFlow()

    /**
     * Availability of on-device semantic search, updated on each search so the UI can explain
     * why AI results are missing when the bundled model fails to load. OCR/filename search is
     * unaffected.
     */
    private val _searchAiState = MutableStateFlow(SearchAiState.READY)
    val searchAiState: StateFlow<SearchAiState> = _searchAiState.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    /** True while a media sync is enqueued or running (drives pull-to-refresh). */
    val isRefreshing: StateFlow<Boolean> = WorkManager.getInstance(application)
        .getWorkInfosForUniqueWorkFlow(SyncWorker.WORK_NAME)
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val ocrCount: StateFlow<Int> = repository.getOCRCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Photos that count toward on-device indexing: the denominator shared by the
     * OCR, CLIP and face progress bars, which all measure progress over the same
     * set of photos. One flow, so one observer and one query per write.
     */
    val indexTargetCount: StateFlow<Int> = repository.getIndexTargetCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Photos already embedded with TinyCLIP for semantic search (progress numerator). */
    val clipCount: StateFlow<Int> = repository.getClipCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** True while the face-grouping worker is actively indexing. */
    val faceIndexing: StateFlow<Boolean> = WorkManager.getInstance(application)
        .getWorkInfosForUniqueWorkFlow(FaceWorker.WORK_NAME)
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Photos already scanned for faces (progress numerator). */
    val faceScannedCount: StateFlow<Int> = repository.getFaceScannedCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Photos grouped by person-cluster, for the People view.
     *
     * A cluster's representative is derived here rather than stored: whichever of
     * its faces covers the most *source* pixels, which is the one that survives
     * being cropped into a thumbnail. Area is
     * `(right-left) * (bottom-top) * photo.width * photo.height`, and those two
     * fractions multiply against both dimensions, so the ranking is unaffected by
     * MediaStore's width/height being transposed for EXIF-rotated JPEGs.
     *
     * Deriving it also means the cover photo and the box always come from the
     * same face, so a trashed photo can no longer leave a box cropping an
     * unrelated image.
     */
    val people: StateFlow<List<PersonCluster>> =
        combine(
            repository.getAllFlow(),
            faceRepository.personsFlow(),
            faceRepository.faceGeometryFlow(),
        ) { allPhotos, persons, faces ->
            Triple(allPhotos, persons, faces)
        }
            // Every per-photo write during indexing re-emits upstream, far faster
            // than the UI can consume. Conflating ahead of the projection means
            // superseded snapshots are dropped instead of projected and thrown away.
            .conflate()
            .map { (allPhotos, persons, faces) ->
                val byId = allPhotos.filter { !it.isTrashed }.associateBy { it.id }
                val facesByCluster = faces.groupBy { it.clusterId }
                persons.mapNotNull { person ->
                    val clusterFaces = facesByCluster[person.id].orEmpty()
                        .mapNotNull { face -> byId[face.photoId]?.let { face to it } }
                    if (clusterFaces.isEmpty()) return@mapNotNull null
                    // Tiny groups are mostly strangers in the background; a named one
                    // is the user's choice and always shows.
                    if (clusterFaces.size < FaceClusterConfig.MIN_CLUSTER_FACES && person.name == null) {
                        return@mapNotNull null
                    }
                    val (bestFace, bestPhoto) = clusterFaces.maxBy { (face, photo) ->
                        (face.right - face.left).toDouble() * (face.bottom - face.top) *
                            photo.width * photo.height
                    }
                    PersonCluster(
                        id = person.id,
                        name = person.name,
                        coverPhoto = bestPhoto,
                        faceLeft = bestFace.left,
                        faceTop = bestFace.top,
                        faceRight = bestFace.right,
                        faceBottom = bestFace.bottom,
                        photos = clusterFaces.map { it.second }
                            .distinctBy { it.id }
                            .sortedByDescending { it.date },
                    )
                }.sortedByDescending { it.photos.size }
            }
            // Per-photo writes during indexing re-emit the source flows constantly;
            // drop emissions where the projected cluster list is unchanged (value
            // equality of the PersonCluster data class) so the grid doesn't churn.
            // The representative can legitimately change mid-scan, so real changes
            // still get through.
            .distinctUntilChanged()
            // Below flowOn, so the O(library) projection *and* the deep list
            // comparison above both run off the main thread. Without this,
            // stateIn(viewModelScope) runs every one of them on Main.immediate,
            // which is what delayed a freshly-entered name from reaching the grid.
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Number of detected faces (i.e. people) per photo, for the photo detail overlay. */
    val faceCountByPhoto: StateFlow<Map<Long, Int>> =
        faceRepository.faceGeometryFlow()
            .conflate()
            .map { faces -> faces.groupBy { it.photoId }.mapValues { (_, list) -> list.size } }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Photos grouped by MediaStore bucket (album), for the Albums view.
     *
     * Derived from the photos flow rather than a DAO distinct query, mirroring
     * the [people] style: the cover photo (newest in the album) travels with
     * the group so the tile needs no second lookup. Trashed photos are
     * excluded, like the gallery grid. Null/blank buckets (rows written before
     * the album column existed, or MediaStore rows with no bucket) fall back to
     * [Album.UNKNOWN_NAME]; the screen resolves that to
     * R.string.albums_unknown.
     */
    val albums: StateFlow<List<Album>> =
        combine(repository.getAllFlow(), _albumCovers) { allPhotos, covers -> allPhotos to covers }
            .conflate()
            .map { (allPhotos, covers) ->
                allPhotos.filter { !it.isTrashed }
                    .groupBy { it.album?.takeIf { name -> name.isNotBlank() } ?: Album.UNKNOWN_NAME }
                    .map { (name, albumPhotos) ->
                        // getAllFlow is ORDER BY date DESC, so the first row is
                        // the album's newest photo — the default cover. A
                        // user-chosen cover overrides it when that photo is still
                        // in the bucket.
                        val cover = covers[name]?.let { id -> albumPhotos.firstOrNull { it.id == id } }
                            ?: albumPhotos.first()
                        Album(
                            name = name,
                            coverPhoto = cover,
                            photos = albumPhotos,
                        )
                    }
                    .sortedBy { it.name.lowercase() }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Face geometry per photo, for the viewer's face overlay. Combined with the
     * clusters because the label text lives on [com.vayunmathur.photos.data.Person],
     * not on the face row — which is also why [faceCountByPhoto] can't be reused.
     */
    val faceBoxesByPhoto: StateFlow<Map<Long, PhotoFaceBoxes>> =
        combine(faceRepository.faceGeometryFlow(), faceRepository.personsFlow()) { faces, persons ->
            faces to persons
        }
            .conflate()
            .map { (faces, persons) ->
                val nameByCluster = persons.associate { it.id to it.name }
                faces.groupBy { it.photoId }.mapValues { (_, rows) ->
                    PhotoFaceBoxes(
                        // Duplicated across a photo's rows, so any one of them serves.
                        srcWidth = rows.first().srcWidth,
                        srcHeight = rows.first().srcHeight,
                        faces = rows.map { row ->
                            FaceBox(
                                clusterId = row.clusterId,
                                name = row.clusterId?.let { nameByCluster[it] },
                                left = row.left,
                                top = row.top,
                                right = row.right,
                                bottom = row.bottom,
                            )
                        },
                    )
                }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        // Restore user-chosen album covers.
        viewModelScope.launch {
            val json = dataStore.getStringAwait(ALBUM_COVERS_KEY)
            if (!json.isNullOrBlank()) {
                runCatching { Json.decodeFromString<Map<String, Long>>(json) }
                    .getOrNull()?.let { _albumCovers.value = it }
            }
        }

        // Debounced search: re-query whenever the query string changes.
        viewModelScope.launch {
            _searchQuery
                .debounce(SEARCH_DEBOUNCE_MS)
                .collectLatest { query ->
                    if (query.isBlank()) {
                        _searchResults.value = emptyList()
                        return@collectLatest
                    }
                    val results = withContext(Dispatchers.IO) {
                        combinedSearch(query)
                    }
                    Log.dev(TAG, "Search '$query' returned ${results.size} photos")
                    _searchResults.value = results
                }
        }
    }

    /**
     * Combine the two on-device search signals, then order the result set
     * chronologically for the search menu — the query acts as a *filter*, not a
     * ranking:
     *  - OCR/name search: the existing case-insensitive LIKE over recognised text
     *    and file name.
     *  - Semantic search: embed the query on-device with TinyCLIP and
     *    cosine-compare it against stored image embeddings (see [ClipEmbedder]);
     *    keep matches at/above [SEMANTIC_THRESHOLD], capped to the top
     *    [MAX_SEMANTIC_RESULTS].
     *
     * The semantic cosine similarity, the [SEMANTIC_THRESHOLD]/[MAX_SEMANTIC_RESULTS]
     * cap and the [OCR_MATCH_BOOST] still decide **membership** (which photos are
     * relevant enough to include). Once that set is chosen, matches are
     * de-duplicated by photo id and displayed **most-recent-first**, like the
     * main grid — so the search bar reads as a chronological filter rather than a
     * relevance-ranked list.
     */
    private suspend fun combinedSearch(query: String): List<Photo> {
        // (a) OCR + filename LIKE search (existing behaviour).
        val ocrHits = try {
            repository.searchPhotos(query)
        } catch (e: SQLiteException) {
            Log.error(TAG, "searchPhotos failed", e)
            emptyList()
        }
        val ocrIds = ocrHits.map { it.id }.toSet()

        // (b) Semantic search: embed the query on-device with TinyCLIP, then cosine vs stored
        // image embeddings. Inert (empty) if the model can't load; the AI-state flow is updated
        // so the UI can explain why.
        val semanticById: Map<Long, Float> = try {
            val textEmb = ClipEmbedder.textEmbedding(getApplication(), query)
            _searchAiState.value = SearchAiState.READY
            scanRepository.getClipEmbeddings()
                .asSequence()
                .map { it.id to ClipEmbedder.cosine(textEmb, ClipEmbedder.bytesToFloats(it.clipEmbedding)) }
                .filter { it.second >= SEMANTIC_THRESHOLD }
                .sortedByDescending { it.second }
                .take(MAX_SEMANTIC_RESULTS)
                .toMap()
        } catch (e: IllegalStateException) {
            Log.error(TAG, "semantic search failed", e)
            _searchAiState.value = SearchAiState.UNAVAILABLE
            emptyMap()
        } catch (e: IOException) {
            Log.error(TAG, "semantic search failed", e)
            _searchAiState.value = SearchAiState.UNAVAILABLE
            emptyMap()
        }

        // (c) Merge + dedupe by id, ranked by combined score. Include ocrHits in
        // the lookup so literal matches are never lost if the photos cache is cold.
        val photoById = (photos.value + ocrHits).associateBy { it.id }
        val ids = LinkedHashSet<Long>().apply {
            addAll(ocrIds)
            addAll(semanticById.keys)
        }
        return ids.mapNotNull { id ->
            val photo = photoById[id] ?: return@mapNotNull null
            if (photo.isTrashed) return@mapNotNull null
            photo
        }.sortedByDescending { it.date }
    }

    override fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    override fun toggleSelection(id: Long) {
        _selectedIds.update { if (id in it) it - id else it + id }
    }

    override fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun deletePhoto(photo: Photo) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.delete(photo)
        }
    }

    /**
     * Move a photo to trash in the DB immediately (so the viewer/grid drop it
     * without waiting for the MediaStore resync). The file itself is trashed via
     * the MediaStore request in the UI; [runSync] later reconciles the rest.
     */
    fun trashPhotoLocally(photo: Photo) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setTrashed(photo.id)
        }
    }

    override fun runSync() {
        SyncWorker.runOnce(getApplication())
    }

    /**
     * Index the single media [uri] into the gallery DB (so a freshly-captured
     * item handed to us via ACTION_VIEW exists before we open it), then return
     * its row id on the main thread. Also kicks a full background sync so the
     * rest of the library is fresh for swiping. Returns null if the item
     * couldn't be resolved.
     */
    fun resolveAndIndex(uri: Uri, onResolved: (Long?) -> Unit) {
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                runCatching { syncPhotos(getApplication(), repository, listOf(uri)) }
                repository.getByUri(uri.toString()).firstOrNull()?.id
            }
            runSync()
            onResolved(id)
        }
    }

    fun enqueueSync() {
        SyncWorker.enqueue(getApplication())
    }

    /**
     * The library photos a person-cluster appears in, newest first.
     *
     * Resolved on demand so the viewer doesn't have to collect the whole [people]
     * list (and recompute every cluster's cover photo) just to answer this for the
     * one face that was tapped.
     */
    suspend fun photosForCluster(clusterId: Long): List<Photo> {
        val ids = faceRepository.photoIdsForCluster(clusterId).toSet()
        if (ids.isEmpty()) return emptyList()
        return photos.value.filter { it.id in ids && !it.isTrashed }
    }

    /** Name a person-cluster, or clear its name with null. */
    fun setPersonName(id: Long, name: String?) {
        // No Dispatchers.IO: Room runs suspend DAO functions on its own
        // dispatcher already, so naming an IO dispatcher here only queued the
        // write behind whatever image work was occupying it.
        viewModelScope.launch {
            faceRepository.setPersonName(id, name)
        }
    }

    // ------------------------------------------------------------------
    // Albums (MediaStore folder moves)
    // ------------------------------------------------------------------

    /**
     * Record a pending move of [photos] into [album] (null = remove from album)
     * for the binder to run [android.provider.MediaStore.createWriteRequest]
     * against; [consumePendingMove] performs the actual move once write access is
     * granted.
     */
    fun requestAlbumMove(photos: List<Photo>, album: String?) {
        pendingMove = photos to album
    }

    /**
     * Run the move recorded by [requestAlbumMove], if any. Returns true when a
     * move was pending (and started), so the caller knows not to fall back to the
     * trash/delete refresh path that shares the same result launcher.
     */
    fun consumePendingMove(): Boolean {
        val move = pendingMove ?: return false
        pendingMove = null
        moveToAlbum(move.first, move.second)
        return true
    }

    /**
     * Persist the RELATIVE_PATH move for [photos] into [album] (null = move back
     * to the media root), then reflect the new bucket locally and resync. Write
     * access must already be granted (see [requestAlbumMove]).
     */
    fun moveToAlbum(photos: List<Photo>, album: String?) {
        if (photos.isEmpty()) return
        val target = album?.takeIf { it.isNotBlank() }
        viewModelScope.launch(Dispatchers.IO) {
            val movedIds = AlbumMediaStore.applyMove(getApplication(), photos, target)
            if (movedIds.isNotEmpty()) {
                // Column-targeted so the optimistic update never nulls a clipEmbedding.
                repository.setAlbum(movedIds, target)
            }
            clearSelection()
            runSync()
        }
    }

    /** Choose which photo represents [albumName] in the Albums grid. */
    fun setAlbumCover(albumName: String, photoId: Long) {
        val updated = _albumCovers.value + (albumName to photoId)
        _albumCovers.value = updated
        viewModelScope.launch { dataStore.setString(ALBUM_COVERS_KEY, Json.encodeToString(updated)) }
    }

    companion object {
        private const val TAG = "GalleryViewModel"

        /** DataStore key holding the `albumName -> photoId` cover map as JSON. */
        private const val ALBUM_COVERS_KEY = "album_covers"

        /**
         * Minimum cosine similarity for a photo to count as a semantic match.
         * Single most-important tunable knob. SigLIP2 is sigmoid-trained and
         * 768-d, so its cosine distribution differs from the previous 512-d
         * embedding space — **retune this against real photos** after the move to
         * TinyCLIP. Higher = stricter/fewer, lower = looser/more; results
         * are ranked and capped so the best matches still surface first.
         */
        private const val SEMANTIC_THRESHOLD = 0.15f

        /** Cap on semantic matches merged into results (keeps the grid relevant). */
        private const val MAX_SEMANTIC_RESULTS = 100

        /** Search re-query debounce window. */
        private const val SEARCH_DEBOUNCE_MS = 150L
    }
}

@Suppress("FunctionName")
fun GalleryViewModelFactory(
    application: Application,
    repository: PhotosRepository,
): ViewModelProvider.Factory = viewModelFactory {
    initializer { GalleryViewModel(application, repository) }
}

/** A person-cluster and the library photos they appear in. */
data class PersonCluster(
    val id: Long,
    /** The name the user picked from their contacts, or null while unnamed. */
    val name: String?,
    val coverPhoto: Photo,
    val faceLeft: Float,
    val faceTop: Float,
    val faceRight: Float,
    val faceBottom: Float,
    val photos: List<Photo>,
)

/**
 * A MediaStore bucket (album) and the library photos in it, newest first.
 *
 * [name] is the BUCKET_DISPLAY_NAME, or [UNKNOWN_NAME] when the row has no
 * bucket (see [GalleryViewModel.albums]); the screen resolves that sentinel
 * to R.string.albums_unknown.
 */
data class Album(
    val name: String,
    val coverPhoto: Photo,
    val photos: List<Photo>,
) {
    /** Photo count shown under the album tile. */
    val count: Int get() = photos.size

    companion object {
        const val UNKNOWN_NAME = "__unknown__"
    }
}

/**
 * The faces detected in one photo, plus the dimensions their boxes are
 * normalised against — the display-orientation dimensions of the detection
 * bitmap, which is what the overlay needs to rebuild the letterbox rect.
 */
data class PhotoFaceBoxes(
    val srcWidth: Int,
    val srcHeight: Int,
    val faces: List<FaceBox>,
)

/** One face box (normalised 0..1) and the person it was grouped into, if any. */
data class FaceBox(
    val clusterId: Long?,
    val name: String?,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Availability of on-device semantic search, for the search UI. */
enum class SearchAiState {
    /** The bundled TinyCLIP model loaded and is serving embeddings. */
    READY,

    /** The bundled model could not be loaded, so only OCR/filename search works. */
    UNAVAILABLE,
}
