package com.vayunmathur.photos.util

import com.vayunmathur.photos.data.FaceGeometry
import com.vayunmathur.photos.data.Person
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoEmbedding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [PhotoStore]/[PhotoScanStore]/[FaceStore] fakes for slice 3/3 pass-3 tests. */
internal class FakePhotoStorePass3(initial: List<Photo> = emptyList()) : PhotoStore {
    val photos = MutableStateFlow(initial)
    var searchHits: List<Photo> = emptyList()
    val deleted = mutableListOf<Photo>()
    val trashed = mutableListOf<Long>()
    val albumSets = mutableListOf<Pair<List<Long>, String?>>()
    var byUri: List<Photo> = emptyList()

    override fun getAllFlow(): Flow<List<Photo>> = photos
    override fun getOCRCountFlow(): Flow<Int> = MutableStateFlow(3)
    override fun getIndexTargetCountFlow(): Flow<Int> = MutableStateFlow(10)
    override fun getClipCountFlow(): Flow<Int> = MutableStateFlow(4)
    override fun getFaceScannedCountFlow(): Flow<Int> = MutableStateFlow(5)
    override suspend fun searchPhotos(query: String): List<Photo> =
        searchHits.filter { it.name.contains(query, ignoreCase = true) || (it.ocrText?.contains(query, ignoreCase = true) == true) }
    override suspend fun delete(photo: Photo): Int { deleted.add(photo); return 1 }
    override suspend fun setTrashed(id: Long) { trashed.add(id) }
    override suspend fun setAlbum(ids: List<Long>, album: String?) { albumSets.add(ids to album) }
    override suspend fun getByUri(uri: String): List<Photo> = byUri.filter { it.uri == uri }
    override suspend fun getAll(): List<Photo> = photos.value
}

internal class FakeScanStorePass3(var embeddings: List<PhotoEmbedding> = emptyList()) : PhotoScanStore {
    override suspend fun getClipEmbeddings(): List<PhotoEmbedding> = embeddings
}

internal class FakeFaceStorePass3(
    var persons: List<Person> = emptyList(),
    var geometry: List<FaceGeometry> = emptyList(),
    var clusterIds: Map<Long, List<Long>> = emptyMap(),
) : FaceStore {
    val renames = mutableListOf<Pair<Long, String?>>()
    override fun personsFlow(): Flow<List<Person>> = MutableStateFlow(persons)
    override fun faceGeometryFlow(): Flow<List<FaceGeometry>> = MutableStateFlow(geometry)
    override suspend fun photoIdsForCluster(clusterId: Long): List<Long> = clusterIds[clusterId].orEmpty()
    override suspend fun setPersonName(id: Long, name: String?) { renames.add(id to name) }
}

internal fun pass3Photo(id: Long, name: String, date: Long = id * 1000, album: String? = null, trashed: Boolean = false, lat: Double? = null, long: Double? = null, ocr: String? = null) = Photo(
    id = id, name = name, uri = "content://media/$id", date = date, width = 100, height = 80,
    dateModified = date, exifSet = true, lat = lat, long = long, videoData = null, panoData = null,
    isTrashed = trashed, album = album, ocrText = ocr,
)
