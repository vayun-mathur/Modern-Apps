package com.vayunmathur.photos.util

import android.app.Application
import android.location.Geocoder
import android.util.Log
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.ui.MapCluster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * ViewModel for the photos map screen.
 *
 * Owns:
 *  - CPU-bound clustering of projected photo positions on Dispatchers.Default
 *  - reverse-geocoded country names with a bounded LRU cache
 *
 * The actual MapLibre camera/projection state stays in the Composable since it
 * is inherently UI-bound. The Composable feeds projected positions to
 * [regenerateClusters] which performs the work off the main thread.
 */
class PhotoMapViewModel(application: Application) : AndroidViewModel(application) {

    private val _generatedClusters = MutableStateFlow<List<MapCluster>>(emptyList())
    val generatedClusters: StateFlow<List<MapCluster>> = _generatedClusters.asStateFlow()

    private val _countryNames = MutableStateFlow<Map<Long, String>>(emptyMap())
    val countryNames: StateFlow<Map<Long, String>> = _countryNames.asStateFlow()

    private val pendingCountryRequests = mutableSetOf<Long>()

    // Bounded LRU cache for resolved country names keyed by lat/long bucket so
    // that nearby photos share a cached result.
    private val countryCache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > 32
    }

    private val geocoder by lazy { Geocoder(application) }

    /**
     * Run greedy clustering on the projected positions. The composable passes
     * already-projected DpOffsets so the VM only does CPU work.
     */
    fun regenerateClusters(items: List<Pair<DpOffset, Photo>>, threshold: Dp = 50.dp) {
        viewModelScope.launch(Dispatchers.Default) {
            val result = clusterPhotos(items, threshold)
            _generatedClusters.value = result
        }
    }

    /**
     * Request reverse-geocoding for a photo. Results are deduplicated and cached
     * across photos using a coarse lat/long key.
     */
    @Suppress("DEPRECATION")
    fun requestCountryName(photoId: Long, lat: Double, long: Double) {
        if (_countryNames.value.containsKey(photoId)) return
        val key = bucketKey(lat, long)
        synchronized(countryCache) {
            countryCache[key]?.let { cached ->
                _countryNames.update { it + (photoId to cached) }
                return
            }
            if (!pendingCountryRequests.add(photoId)) return
        }
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) {
                try {
                    geocoder.getFromLocation(lat, long, 1)?.firstOrNull()?.countryName ?: "Unknown"
                } catch (e: Exception) {
                    Log.e(TAG, "geocoder failed", e)
                    "Unknown"
                }
            }
            synchronized(countryCache) {
                countryCache[key] = name
                pendingCountryRequests.remove(photoId)
            }
            _countryNames.update { it + (photoId to name) }
        }
    }

    private fun bucketKey(lat: Double, long: Double): String {
        // 0.01 degrees ~ 1km; coarse enough to share lookups for nearby photos.
        val l = (lat * 100).toInt()
        val lo = (long * 100).toInt()
        return "$l,$lo"
    }

    companion object {
        private const val TAG = "PhotoMapViewModel"

        /**
         * Greedy clustering: each photo is absorbed into the first existing
         * cluster within [threshold], otherwise it starts a new cluster.
         */
        fun clusterPhotos(
            items: List<Pair<DpOffset, Photo>>,
            threshold: Dp,
        ): List<MapCluster> {
            val result = ArrayList<MapCluster>()
            val thresholdVal = threshold.value
            for ((pos, photo) in items) {
                val existingIndex = result.indexOfFirst { cluster ->
                    calculateDistance(cluster.position, pos) < thresholdVal
                }
                if (existingIndex >= 0) {
                    val existing = result[existingIndex]
                    result[existingIndex] = existing.copy(
                        count = existing.count + 1,
                        allPhotos = existing.allPhotos + photo,
                    )
                } else {
                    result.add(MapCluster(pos, photo, listOf(photo), 1))
                }
            }
            return result.map { it.copy(allPhotos = it.allPhotos.sortedByDescending(Photo::date)) }
        }

        private fun calculateDistance(p1: DpOffset, p2: DpOffset): Float {
            val dx = p1.x.value - p2.x.value
            val dy = p1.y.value - p2.y.value
            return sqrt(dx.pow(2) + dy.pow(2))
        }
    }
}

@Suppress("FunctionName")
fun PhotoMapViewModelFactory(application: Application): ViewModelProvider.Factory =
    viewModelFactory {
        initializer { PhotoMapViewModel(application) }
    }
