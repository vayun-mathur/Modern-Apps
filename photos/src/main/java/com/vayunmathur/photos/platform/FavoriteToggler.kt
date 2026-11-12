package com.vayunmathur.photos.platform

import android.app.PendingIntent
import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.domain.FavoriteRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Builds the MediaStore request that sets IS_FAVORITE on [uris]. An interface
 * so tests can count and inspect requests; production is
 * [MediaStoreFavoriteRequests].
 */
fun interface FavoriteRequestFactory {
    fun create(uris: List<Uri>, value: Boolean): PendingIntent
}

/** [FavoriteRequestFactory] over [MediaStore.createFavoriteRequest]. */
class MediaStoreFavoriteRequests(private val resolver: ContentResolver) : FavoriteRequestFactory {
    override fun create(uris: List<Uri>, value: Boolean): PendingIntent =
        MediaStore.createFavoriteRequest(resolver, uris, value)
}

/**
 * Favourites and unfavourites photos through MediaStore (#912), which owns
 * IS_FAVORITE; the local Photo rows only mirror it.
 *
 * A toggle updates the local rows at once (so the heart and the Favourites
 * album react immediately), then hands back the request's PendingIntent for
 * the caller to launch with an activity-result launcher, exactly like the
 * trash flow. With the app's "manage media" access granted, MediaStore applies
 * it without a dialog; without it, the system asks the user, and a refusal
 * reverts the local rows. Selections over [FavoriteRule.MAX_URIS_PER_REQUEST]
 * go out as consecutive requests, each launched when the previous one
 * succeeded. Once every request succeeded, [onApplied] runs (a resync), which
 * reconciles anything else that changed.
 *
 * Not thread-safe: call it from the main thread, as the composables do.
 */
class FavoriteToggler(
    private val writeLocal: suspend (ids: List<Long>, value: Boolean) -> Unit,
    private val requests: FavoriteRequestFactory,
    private val scope: CoroutineScope,
    private val onApplied: () -> Unit = {},
) {
    /** What the caller does next. */
    sealed interface Step {
        /** Launch [intent] and report its result to [onResult]. */
        data class Launch(val intent: PendingIntent) : Step

        /** Everything requested is applied (or there was nothing to change). */
        data object Done : Step

        /** The user declined; the local rows were reverted. */
        data object Cancelled : Step

        /** MediaStore refused to build the request; the local rows were reverted. */
        data object Failed : Step

        /** A previous toggle is still waiting for its result; nothing was done. */
        data object Busy : Step
    }

    private val pending = ArrayDeque<List<Photo>>()
    private var inFlight: List<Photo>? = null
    private var value = false

    // Local writes run in order: a revert must never land before the
    // optimistic write it undoes. Mutex is FIFO.
    private val writes = Mutex()

    /** Toggle [photos] together by [FavoriteRule.targetValue]: a mixed selection is favourited. */
    fun toggle(photos: List<Photo>): Step =
        setFavorite(photos, FavoriteRule.targetValue(photos.map { it.isFavorite }))

    /** Set IS_FAVORITE to [value] on every photo in [photos]. */
    fun setFavorite(photos: List<Photo>, value: Boolean): Step {
        if (inFlight != null) return Step.Busy
        val changing = photos.filter { it.isFavorite != value }
        if (changing.isEmpty()) return Step.Done
        this.value = value
        write(changing, value)
        pending.addAll(changing.chunked(FavoriteRule.MAX_URIS_PER_REQUEST))
        return next()
    }

    /** Report the launched request's result; returns the next step. */
    fun onResult(granted: Boolean): Step {
        val batch = inFlight ?: return Step.Done
        inFlight = null
        if (!granted) {
            abandon(batch)
            return Step.Cancelled
        }
        return next()
    }

    private fun next(): Step {
        val batch = pending.removeFirstOrNull()
        if (batch == null) {
            onApplied()
            return Step.Done
        }
        val intent = try {
            requests.create(batch.map { it.uri.toUri() }, value)
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        if (intent == null) {
            abandon(batch)
            return Step.Failed
        }
        inFlight = batch
        return Step.Launch(intent)
    }

    /** Revert [batch] and every batch not yet sent; already-applied batches stay. */
    private fun abandon(batch: List<Photo>) {
        val reverted = batch + pending.flatten()
        pending.clear()
        write(reverted, !value)
    }

    private fun write(photos: List<Photo>, value: Boolean) {
        val ids = photos.map { it.id }
        scope.launch {
            writes.withLock {
                ids.chunked(LOCAL_WRITE_CHUNK).forEach { writeLocal(it, value) }
            }
        }
    }

    private companion object {
        /** SQLite bind-variable ceiling for the `id IN (...)` update. */
        const val LOCAL_WRITE_CHUNK = 900
    }
}
