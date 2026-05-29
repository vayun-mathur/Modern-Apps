package com.vayunmathur.email.data

import android.content.Context
import android.util.Log
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

    fun runIfNeeded(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val dao = EmailDatabase.getInstance(context).emailDao()
            // Java's `Date.toString()` format, e.g. "Wed Nov 27 14:30:00 PST 2024".
            val fmt = SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.US)
            var batch = dao.getRowsWithZeroDateMillis()
            var fixed = 0
            while (batch.isNotEmpty()) {
                for (row in batch) {
                    val parsed = try { fmt.parse(row.date)?.time } catch (e: Exception) { null }
                    // Use 1 instead of 0 for unparseable dates so they don't keep getting
                    // re-processed forever (they'll sort to the bottom but won't loop).
                    val value = parsed?.takeIf { it > 0L } ?: 1L
                    dao.updateDateMillis(row.accountEmail, row.folderName, row.id, value)
                    fixed++
                }
                batch = dao.getRowsWithZeroDateMillis()
            }
            if (fixed > 0) Log.d("DateMillisBackfill", "Backfilled $fixed row(s)")
        }
    }
}
