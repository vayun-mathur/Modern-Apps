package com.vayunmathur.email.data

import android.content.Context
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * One-time backfill that parses each row's `Date.toString()`-formatted `date`
 * string into epoch-millis and writes it into the new `dateMillis` column.
 * Called from `MainActivity.onCreate`; subsequent runs are a no-op once every
 * row has a non-zero `dateMillis`.
 */
object DateMillisBackfill {

    fun runIfNeeded(scope: CoroutineScope, context: Context) {
        scope.launch(Dispatchers.IO) {
            // Best-effort one-time migration: a database that will not open
            // on this device (#939) must not crash the process from here.
            try {
                runBackfill(context)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Log.status("DateMillisBackfill", "Backfill skipped; database unavailable", failure)
            }
        }
    }

    private suspend fun runBackfill(context: Context) {
        val dao = EmailRepository.get(context).getDatabase().queryDao()
        // Java's `Date.toString()` format, e.g. "Wed Nov 27 14:30:00 PST 2024".
        val fmt = SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.US)
        var batch = dao.getRowsWithZeroDateMillis()
        var fixed = 0
        while (batch.isNotEmpty()) {
            for (row in batch) {
                val parsed = try { fmt.parse(row.date)?.time } catch (_: Exception) { null }
                // Use 1 instead of 0 for unparseable dates so they don't keep getting
                // re-processed forever (they'll sort to the bottom but won't loop).
                val value = parsed?.takeIf { it > 0L } ?: 1L
                dao.updateDateMillis(row.accountEmail, row.folderName, row.id, value)
                fixed++
            }
            batch = dao.getRowsWithZeroDateMillis()
        }
        if (fixed > 0) Log.dev("DateMillisBackfill", "Backfilled $fixed row(s)")
    }
}
