package com.vayunmathur.photos.platform

import android.app.PendingIntent
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.domain.FavoriteRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * #912 toggle: the mixed-selection rule, request batching, the optimistic
 * local write and its revert. The toggler runs against a recording request
 * factory (the resolver seam); [MediaStoreFavoriteRequests] itself is checked
 * against a fake MediaStore provider that answers createFavoriteRequest the
 * way MediaProvider does, with a PendingIntent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FavoriteTogglerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Records each favourite request (URIs + value) and returns a PendingIntent for it. */
    private class FakeMediaProvider(private val context: Context) : ContentProvider() {
        val requests = mutableListOf<Pair<List<Uri>, Boolean>>()

        override fun onCreate(): Boolean = true

        override fun call(authority: String, method: String, arg: String?, extras: Bundle?): Bundle =
            call(method, arg, extras)

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
            val bundle = extras ?: Bundle()
            val clip = bundle.keySet().firstNotNullOf { bundle.get(it) as? ClipData }
            val values = bundle.keySet().firstNotNullOf { bundle.get(it) as? ContentValues }
            val uris = (0 until clip.itemCount).map { clip.getItemAt(it).uri }
            requests += uris to (values.getAsInteger(MediaStore.MediaColumns.IS_FAVORITE) == 1)
            val intent = PendingIntent.getActivity(
                context, requests.size, Intent("favorite-${requests.size}"), PendingIntent.FLAG_IMMUTABLE,
            )
            return Bundle().apply { putParcelable("result", intent) }
        }

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(
            uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
        ): Int = 0
    }

    private fun photo(id: Long, favorite: Boolean) = Photo(
        id = id, name = "p$id", uri = "content://media/external/images/media/$id",
        date = 1_000L, width = 100, height = 100, dateModified = 1L,
        exifSet = true, lat = null, long = null, videoData = null, panoData = null,
        isFavorite = favorite,
    )

    /** Records each request like the provider does, without going through MediaStore. */
    private class RecordingRequests(private val context: Context) : FavoriteRequestFactory {
        val requests = mutableListOf<Pair<List<Uri>, Boolean>>()
        var refuse = false
        override fun create(uris: List<Uri>, value: Boolean): PendingIntent {
            if (refuse) throw SecurityException("refused")
            requests += uris to value
            return PendingIntent.getActivity(
                context, requests.size, Intent("favorite-${requests.size}"), PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }

    private class Harness(scope: TestScope, val provider: RecordingRequests) {
        val writes = mutableListOf<Pair<List<Long>, Boolean>>()
        var applied = 0
        val toggler = FavoriteToggler(
            writeLocal = { ids, value -> writes += ids to value },
            requests = provider,
            scope = scope,
            onApplied = { applied++ },
        )
    }

    private fun register(): RecordingRequests = RecordingRequests(context)

    @Test
    fun mediaStoreRequestCarriesTheUrisAndTheValue() {
        // Attached under the media authority: ContentResolver.call validates it.
        val provider = FakeMediaProvider(context).apply {
            attachInfo(context, ProviderInfo().apply { authority = MediaStore.AUTHORITY })
        }
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)
        val uris = listOf(Uri.parse(photo(1, false).uri), Uri.parse(photo(2, false).uri))

        val result = runCatching { MediaStoreFavoriteRequests(context.contentResolver).create(uris, true) }
        val error = result.exceptionOrNull()
        assertNull(error, error?.stackTraceToString())
        runCatching { MediaStoreFavoriteRequests(context.contentResolver).create(uris.take(1), false) }

        assertEquals(listOf(uris to true, uris.take(1) to false), provider.requests)
    }

    @Test
    fun mixedSelectionFavoritesOnlyTheItemsThatAreNotYet() = runTest {
        val provider = register()
        val h = Harness(this, provider)

        val step = h.toggler.toggle(listOf(photo(1, true), photo(2, false), photo(3, false)))
        testScheduler.advanceUntilIdle()

        assertIs<FavoriteToggler.Step.Launch>(step)
        // One request, value true, for the two that change; photo 1 is untouched.
        assertEquals(
            listOf(listOf(Uri.parse(photo(2, false).uri), Uri.parse(photo(3, false).uri)) to true),
            provider.requests,
        )
        assertEquals(listOf(listOf(2L, 3L) to true), h.writes)

        assertIs<FavoriteToggler.Step.Done>(h.toggler.onResult(granted = true))
        assertEquals(1, h.applied)
    }

    @Test
    fun allFavoriteSelectionUnfavorites() = runTest {
        val provider = register()
        val h = Harness(this, provider)

        assertIs<FavoriteToggler.Step.Launch>(h.toggler.toggle(listOf(photo(1, true), photo(2, true))))
        testScheduler.advanceUntilIdle()

        assertEquals(false, provider.requests.single().second)
        assertEquals(listOf(listOf(1L, 2L) to false), h.writes)
    }

    @Test
    fun largeSelectionsGoOutInSequentialBatches() = runTest {
        val provider = register()
        val h = Harness(this, provider)
        val photos = (1L..(FavoriteRule.MAX_URIS_PER_REQUEST + 20L)).map { photo(it, false) }

        assertIs<FavoriteToggler.Step.Launch>(h.toggler.toggle(photos))
        assertEquals(1, provider.requests.size)
        assertEquals(FavoriteRule.MAX_URIS_PER_REQUEST, provider.requests[0].first.size)
        // A second toggle while the first request is out is refused.
        assertIs<FavoriteToggler.Step.Busy>(h.toggler.toggle(listOf(photo(9_999, false))))

        assertIs<FavoriteToggler.Step.Launch>(h.toggler.onResult(granted = true))
        assertEquals(2, provider.requests.size)
        assertEquals(20, provider.requests[1].first.size)
        assertEquals(0, h.applied)

        assertIs<FavoriteToggler.Step.Done>(h.toggler.onResult(granted = true))
        assertEquals(1, h.applied)
        testScheduler.advanceUntilIdle()
        // One optimistic write covering all of them, never reverted.
        assertEquals(photos.map { it.id }.toSet(), h.writes.flatMap { it.first }.toSet())
        assertTrue(h.writes.all { it.second })
    }

    @Test
    fun declinedRequestRevertsItAndEveryUnsentBatch() = runTest {
        val provider = register()
        val h = Harness(this, provider)
        val photos = (1L..(FavoriteRule.MAX_URIS_PER_REQUEST + 5L)).map { photo(it, false) }

        h.toggler.toggle(photos)
        assertIs<FavoriteToggler.Step.Cancelled>(h.toggler.onResult(granted = false))
        testScheduler.advanceUntilIdle()

        assertEquals(1, provider.requests.size)
        assertEquals(0, h.applied)
        // Optimistic true for all, then false for all of them (in that order).
        val allIds = photos.map { it.id }.toSet()
        val (optimistic, reverted) = h.writes.partition { it.second }
        assertEquals(allIds, optimistic.flatMap { it.first }.toSet())
        assertEquals(allIds, reverted.flatMap { it.first }.toSet())
        assertTrue(h.writes.indexOfFirst { !it.second } > h.writes.indexOfLast { it.second })
        // The toggler is free again.
        assertIs<FavoriteToggler.Step.Launch>(h.toggler.toggle(listOf(photo(1, false))))
    }

    @Test
    fun requestThatMediaStoreRefusesIsReverted() = runTest {
        val provider = register().apply { refuse = true }
        val h = Harness(this, provider)

        assertIs<FavoriteToggler.Step.Failed>(h.toggler.toggle(listOf(photo(1, false))))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(listOf(1L) to true, listOf(1L) to false), h.writes)
        assertEquals(0, h.applied)
    }

    @Test
    fun nothingToChangeIsDoneWithoutARequest() = runTest {
        val provider = register()
        val h = Harness(this, provider)

        assertIs<FavoriteToggler.Step.Done>(h.toggler.setFavorite(listOf(photo(1, true)), true))
        testScheduler.advanceUntilIdle()

        assertTrue(provider.requests.isEmpty())
        assertTrue(h.writes.isEmpty())
    }
}
