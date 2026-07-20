package com.vayunmathur.everysync.remote

import android.content.Context
import com.vayunmathur.everysync.format.ICalendar
import com.vayunmathur.everysync.format.VCard
import com.vayunmathur.everysync.provider.SyncDirection
import com.vayunmathur.everysync.sink.CalendarSink
import com.vayunmathur.everysync.sink.ContactsSink
import java.util.UUID

/**
 * Shared two-way CalDAV/CardDAV sync routines against [ContactsSink] /
 * [CalendarSink]. Resource hrefs are used as the local SOURCE_ID / _SYNC_ID so
 * ETag comparison is direct; server deletions are detected by set difference.
 * Local edits are pushed back via PUT/DELETE with If-Match. Used by every DAV
 * caller regardless of auth (Basic for iCloud/generic, Bearer for Google).
 */
object DavSync {

    suspend fun syncContacts(context: Context, account: String, client: DavClient, baseUrl: String, direction: SyncDirection) {
        val collections = client.discoverCollections(baseUrl, isCalendar = false)
        val allRemoteHrefs = mutableSetOf<String>()

        if (direction != SyncDirection.PUSH) {
            val local = ContactsSink.localUidToEtag(context, account)
            for (col in collections) {
                val resources = client.listResources(col.url)
                allRemoteHrefs += resources.map { it.href }
                val changed = resources.filter { local[it.href] != it.etag }
                val fetched = client.multiget(col.url, changed.map { it.href }, isCalendar = false)
                for (res in fetched) {
                    val data = res.data ?: continue
                    val parsed = VCard.parse(data, fallbackUid = res.href)
                    ContactsSink.upsert(context, account, parsed.copy(uid = res.href, etag = res.etag, href = res.href))
                }
            }
            // Server-side deletions.
            (local.keys - allRemoteHrefs).forEach { ContactsSink.delete(context, account, it) }
        }

        if (direction != SyncDirection.PULL && collections.isNotEmpty()) {
            val collectionUrl = collections.first().url.trimEnd('/')
            for (change in ContactsSink.getLocalChanges(context, account)) {
                when {
                    change.deleted && change.sourceId != null -> client.delete(change.sourceId, change.etag)
                    !change.deleted && change.contact != null -> {
                        val href = change.sourceId ?: "$collectionUrl/${UUID.randomUUID()}.vcf"
                        val vcard = VCard.serialize(change.contact.copy(uid = change.contact.uid.ifBlank { href }))
                        val newEtag = client.put(href, "text/vcard; charset=utf-8", vcard, change.etag)
                        if (change.sourceId == null) {
                            ContactsSink.setSourceId(context, account, change.rawContactId, href, newEtag)
                        } else {
                            ContactsSink.clearDirty(context, account, change.rawContactId)
                        }
                    }
                }
            }
        }
    }

    suspend fun syncCalendars(context: Context, account: String, client: DavClient, baseUrl: String, direction: SyncDirection) {
        val collections = client.discoverCollections(baseUrl, isCalendar = true)
        for (col in collections) {
            val localCalId = CalendarSink.getOrCreateCalendarId(context, account, col.url, col.displayName, col.color)
            if (localCalId == -1L) continue

            if (direction != SyncDirection.PUSH) {
                val local = CalendarSink.localUidToEtag(context, account, localCalId)
                val resources = client.listResources(col.url)
                val remoteHrefs = resources.map { it.href }.toSet()
                val changed = resources.filter { local[it.href] != it.etag }
                val fetched = client.multiget(col.url, changed.map { it.href }, isCalendar = true)
                for (res in fetched) {
                    val data = res.data ?: continue
                    ICalendar.parse(data, calendarId = localCalId.toString(), fallbackUid = res.href).forEach { ev ->
                        CalendarSink.upsertEvent(context, account, localCalId, ev.copy(uid = res.href, etag = res.etag, href = res.href))
                    }
                }
                (local.keys - remoteHrefs).forEach { CalendarSink.deleteEvent(context, account, localCalId, it) }
            }

            if (direction != SyncDirection.PULL) {
                val collectionUrl = col.url.trimEnd('/')
                for (change in CalendarSink.getLocalChanges(context, account, localCalId)) {
                    when {
                        change.deleted && change.syncId != null -> client.delete(change.syncId, change.etag)
                        !change.deleted && change.event != null -> {
                            val href = change.syncId ?: "$collectionUrl/${UUID.randomUUID()}.ics"
                            val ics = ICalendar.serialize(change.event.copy(uid = change.event.uid.ifBlank { href }))
                            val newEtag = client.put(href, "text/calendar; charset=utf-8", ics, change.etag)
                            if (change.syncId == null) {
                                // Re-create under the server href so future syncs match.
                                CalendarSink.deleteEvent(context, account, localCalId, change.event.uid)
                                CalendarSink.upsertEvent(context, account, localCalId, change.event.copy(uid = href, etag = newEtag, href = href))
                            } else {
                                CalendarSink.clearDirty(context, account, change.eventId)
                            }
                        }
                    }
                }
            }
        }
    }
}
