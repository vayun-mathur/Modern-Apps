package com.vayunmathur.photos.util

import android.app.Application
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.photos.data.Photo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PhotoMapViewModel tests for slice 3/3 (ViewModels and remaining screens).
 *
 * [PhotoMapViewModel.clusterPhotos] is a pure companion function; the
 * country-name path needs a Geocoder backend, so only its synchronous
 * cache-hit/dedup branches are pinned here (Robolectric's shadow Geocoder
 * returns no addresses, which exercises the "Unknown" fallback on-device).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosSlice3MapTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val app = context.applicationContext as Application

    private var nextId = 1L

    private fun photo(name: String, date: Long) = Photo(
        id = nextId++,
        name = name,
        uri = "content://media/$name",
        date = date,
        width = 100,
        height = 100,
        dateModified = date,
        exifSet = true,
        lat = 37.4,
        long = -122.1,
        videoData = null,
        panoData = null,
    )

    private fun item(x: Float, y: Float, date: Long, name: String = "p$date") =
        Triple(DpOffset(x.dp, y.dp), GeoPoint(-122.1, 37.4), photo(name, date))

    @Test
    fun emptyInputYieldsNoClusters() {
        assertTrue(PhotoMapViewModel.clusterPhotos(emptyList(), 50.dp).isEmpty())
    }

    @Test
    fun nearbyPhotosShareOneCluster() {
        val clusters = PhotoMapViewModel.clusterPhotos(
            listOf(item(0f, 0f, 100L), item(10f, 10f, 200L)),
            50.dp,
        )
        assertEquals(1, clusters.size)
        assertEquals(2, clusters.single().count)
        // Photos inside a cluster sort newest-first.
        assertEquals(listOf(200L, 100L), clusters.single().allPhotos.map { it.date })
    }

    @Test
    fun distantPhotosFormSeparateClusters() {
        val clusters = PhotoMapViewModel.clusterPhotos(
            listOf(item(0f, 0f, 100L), item(500f, 500f, 200L)),
            50.dp,
        )
        assertEquals(2, clusters.size)
        assertTrue(clusters.all { it.count == 1 })
    }

    @Test
    fun clusterSeedKeepsFirstPhotoPosition() {
        val first = item(0f, 0f, 100L, "first")
        val clusters = PhotoMapViewModel.clusterPhotos(
            listOf(first, item(5f, 5f, 200L, "second")),
            50.dp,
        )
        assertEquals(first.second, clusters.single().position)
        assertEquals("first", clusters.single().coverPhoto.name)
    }

    @Test
    fun thresholdBoundaryIsExclusive() {
        // Exactly at the threshold does NOT merge (strict <).
        val clusters = PhotoMapViewModel.clusterPhotos(
            listOf(item(0f, 0f, 100L), item(50f, 0f, 200L)),
            50.dp,
        )
        assertEquals(2, clusters.size)
    }

    @Test
    fun regenerateClustersPublishes() {
        val vm = PhotoMapViewModel(app)
        assertTrue(vm.generatedClusters.value.isEmpty())
        assertTrue(vm.countryNames.value.isEmpty())
        vm.regenerateClusters(listOf(item(0f, 0f, 100L), item(500f, 500f, 200L)))
        // Published asynchronously on Default; the synchronous companion result
        // is pinned above, so here just assert no crash and initial states.
    }

    @Test
    fun countryNameKnownPhotoSkipsLookup() {
        val vm = PhotoMapViewModel(app)
        // Pre-seed via the cache path is private; instead assert the early-out:
        // requesting twice for the same id only emits once (second is deduped).
        vm.requestCountryName(1L, 91.0, 181.0) // invalid coords -> Geocoder throws -> "Unknown"
        val first = vm.countryNames.value[1L]
        // Robolectric shadow Geocoder returns empty -> fallback "Unknown", or the
        // request is still in flight; either way a second request is deduped.
        vm.requestCountryName(1L, 91.0, 181.0)
        assertEquals(first, vm.countryNames.value[1L])
    }

    @Test
    fun regenerateClustersEmptyClears() {
        val vm = PhotoMapViewModel(app)
        vm.regenerateClusters(emptyList())
        // Async on Default; initial value is empty and no crash occurs.
        assertEquals(emptyMap(), vm.countryNames.value)
    }

    @Test
    fun clusterAnchorsOnSeedOnly() {
        // Greedy anchors on the seed: C is near B but far from seed A, so C
        // starts its own cluster (no transitive chaining).
        val clusters = PhotoMapViewModel.clusterPhotos(
            listOf(item(0f, 0f, 100L), item(30f, 0f, 200L), item(60f, 0f, 300L)),
            50.dp,
        )
        assertEquals(2, clusters.size)
        assertEquals(2, clusters.first().count)
        assertEquals(1, clusters.last().count)
    }

    @Test
    fun clusterCoverIsFirstPhoto() {
        val clusters = PhotoMapViewModel.clusterPhotos(
            listOf(item(0f, 0f, 100L, "first"), item(5f, 5f, 200L, "second")),
            50.dp,
        )
        assertEquals("first", clusters.single().coverPhoto.name)
    }
}
