package com.vayunmathur.contacts.data

import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull

/** Upper bound one contact source may take before it is abandoned as empty. */
internal const val CONTACT_SOURCE_TIMEOUT_MILLIS = 5_000L

/**
 * Loads every contact source independently so one bad source cannot wedge
 * the whole address book (issue 926).
 *
 * The system contacts provider and the ICC ADN provider
 * (`content://icc/adn/subId/<subId>`) are both synchronous Binder calls:
 * a provider that never answers cannot be interrupted by coroutine
 * cancellation. Each source therefore runs on this loader's own
 * supervisor scope, detached from the caller, and the caller only
 * *waits* for it with [withTimeoutOrNull]. Timing out abandons the
 * wait (the stuck Binder thread is left on the IO pool) and surfaces
 * that source as empty; every other source still publishes and the
 * caller's loading state still terminates.
 *
 * Throwing sources are isolated the same way: the failure is logged
 * and reads as an empty source, never as a failed whole load.
 */
internal class ContactSourceLoader(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    /** Releases the detached scope; in-flight stuck calls are abandoned. */
    fun close() {
        scope.cancel()
    }

    /**
     * Runs [block] as one isolated source, returning null when it throws
     * or does not answer within [timeoutMillis].
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun <T> load(
        sourceName: String,
        timeoutMillis: Long = CONTACT_SOURCE_TIMEOUT_MILLIS,
        block: suspend () -> T,
    ): T? {
        val deferred = scope.async {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.status(TAG, "Contact source $sourceName failed; treating it as empty", e)
                null
            }
        }
        val result = withTimeoutOrNull(timeoutMillis) { deferred.await() }
        if (result == null && !deferred.isCompleted) {
            Log.status(TAG, "Contact source $sourceName timed out after ${timeoutMillis}ms; treating it as empty")
            deferred.cancel()
        }
        return result
    }

    /**
     * Loads device and SIM contacts in parallel, publishing through
     * [publish] as soon as the device source lands and again with the
     * merged list once the SIM source lands (or times out / fails).
     * Returns the merged list; a failed or hanging source contributes
     * no contacts.
     */
    // Broad catches are deliberate: each source crosses an OEM provider
    // that throws undocumented RuntimeExceptions, and a failed source
    // must read as empty rather than fail the merged load.
    @Suppress("TooGenericExceptionCaught")
    suspend fun loadContacts(
        deviceLoader: suspend () -> List<Contact>,
        simLoader: suspend () -> List<Contact>,
        timeoutMillis: Long = CONTACT_SOURCE_TIMEOUT_MILLIS,
        publish: (List<Contact>) -> Unit = {},
    ): List<Contact> {
        val deviceDeferred = scope.async {
            try {
                deviceLoader()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.status(TAG, "Device contact source failed; treating it as empty", e)
                emptyList()
            }
        }
        val simDeferred = scope.async {
            try {
                simLoader()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.status(TAG, "SIM contact source failed; treating it as empty", e)
                emptyList()
            }
        }
        val device = withTimeoutOrNull(timeoutMillis) { deviceDeferred.await() }
            ?: emptyList<Contact>().also {
                Log.status(TAG, "Device contact source timed out after ${timeoutMillis}ms")
                deviceDeferred.cancel()
            }
        publish(device)
        val sim = withTimeoutOrNull(timeoutMillis) { simDeferred.await() }
            ?: emptyList<Contact>().also {
                Log.status(TAG, "SIM contact source timed out after ${timeoutMillis}ms")
                simDeferred.cancel()
            }
        val merged = device + sim
        publish(merged)
        return merged
    }

    private companion object {
        const val TAG = "ContactSourceLoader"
    }
}
