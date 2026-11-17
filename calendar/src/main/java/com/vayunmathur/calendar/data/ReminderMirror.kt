package com.vayunmathur.calendar.data

import android.content.Context
import com.vayunmathur.library.util.DataStoreUtils

/**
 * A scheduling-only mirror of upcoming reminders kept in device-protected storage,
 * so alarms can be re-armed at `ACTION_LOCKED_BOOT_COMPLETED`.
 *
 * The system CalendarProvider is credential-encrypted and simply unreadable before the
 * first unlock after a reboot, so the locked boot pass has no way to discover reminders
 * on its own. It reads this instead. Written by
 * [com.vayunmathur.calendar.platform.ReminderScheduler.reconcileAll] while unlocked.
 *
 * PRIVACY - device-protected storage is NOT encrypted with the user's credential, so
 * everything below is readable with the device merely powered on. Event titles are
 * therefore deliberately NOT mirrored; this holds only the numbers needed to compute a
 * trigger time and a PendingIntent request code. A reminder that fires before the first
 * unlock shows the generic "Event reminder" title.
 *
 * Mirroring titles was considered and rejected because it buys almost nothing: the
 * post-unlock reconcile re-schedules the same request codes with FLAG_UPDATE_CURRENT,
 * which replaces the intent extras, so every reminder that has not yet fired gets its
 * real title back on unlock for free. Titles would only change what is displayed in the
 * narrow window between a reboot and the first unlock - and would cost leaving every
 * upcoming event title sitting in the clear for as long as the mirror exists.
 *
 * Event ids and start times do still leak here. That is the irreducible minimum: without
 * a time there is no alarm to set, and without an id there is no stable request code.
 */
object ReminderMirror {
    /** Bumping this invalidates any previously written mirror rather than misparsing it. */
    private const val FORMAT_VERSION = "1"
    private const val KEY = "calendar_locked_boot_reminders"

    /** Entries serialize as 4 comma-separated longs: eventId, minutes, start, end. */
    private const val ENTRY_PART_COUNT = 4

    /** One mirrored reminder: event id, minutes before start, and occurrence start/end to re-arm its alarm. */
    data class Entry(
        val eventId: Long,
        val minutes: Int,
        val instanceStart: Long,
        val instanceEnd: Long,
    )

    suspend fun write(context: Context, entries: List<Entry>) {
        val serialized = entries.joinToString(separator = "\n", prefix = "$FORMAT_VERSION\n") {
            "${it.eventId},${it.minutes},${it.instanceStart},${it.instanceEnd}"
        }
        store(context).setString(KEY, serialized)
    }

    suspend fun read(context: Context): List<Entry> {
        val lines = store(context).getStringAwait(KEY)?.split('\n') ?: return emptyList()
        if (lines.firstOrNull() != FORMAT_VERSION) return emptyList()
        return lines.drop(1).mapNotNull { line ->
            val parts = line.split(',')
            if (parts.size != ENTRY_PART_COUNT) return@mapNotNull null
            Entry(
                eventId = parts[0].toLongOrNull() ?: return@mapNotNull null,
                minutes = parts[1].toIntOrNull() ?: return@mapNotNull null,
                instanceStart = parts[2].toLongOrNull() ?: return@mapNotNull null,
                instanceEnd = parts[3].toLongOrNull() ?: return@mapNotNull null,
            )
        }
    }

    private fun store(context: Context) =
        DataStoreUtils.getInstance(context, deviceProtected = true)
}
