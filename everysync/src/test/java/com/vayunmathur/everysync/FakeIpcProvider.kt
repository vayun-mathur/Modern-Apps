package com.vayunmathur.everysync

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.robolectric.shadows.ShadowContentResolver

/**
 * In-memory ContentProvider standing in for the sibling apps' relay
 * providers (notes/passwords/todo). Implements exactly the contract the
 * everysync IPC sinks use: rows are envelope JSON in `payloadJson`, keyed by
 * the envelope's `uid`; base-table queries list full state (tombstones
 * included), `querySince` filters by `updatedAt`, a `syncId = ?` delete
 * tombstones one row, and `deleteByVault` purges the table.
 *
 * One instance backs every table under its authority; tables are keyed by
 * the URI's last-but-one path segment shape (`vault/<id>/<table>`).
 */
class FakeIpcProvider : ContentProvider() {
    data class Row(var syncId: String, var updatedAt: Long, var deleted: Boolean, var payloadJson: String)

    val tables = linkedMapOf<String, LinkedHashMap<String, Row>>()

    override fun onCreate(): Boolean = true

    private fun table(uri: Uri): LinkedHashMap<String, Row> {
        val key = uri.pathSegments.dropLastWhile { it in OPS }.joinToString("/")
        return tables.getOrPut(key) { linkedMapOf() }
    }

    private fun parseRow(payload: String): Row? {
        val obj = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val uid = runCatching { obj["uid"]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return Row(
            syncId = uid,
            updatedAt = obj["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            deleted = obj["deleted"]?.jsonPrimitive?.booleanOrNull ?: false,
            payloadJson = payload,
        )
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor {
        val cols = projection?.toList() ?: listOf(COL_PAYLOAD)
        var rows = table(uri).values.toList()
        val since = uri.getQueryParameter("since")?.toLongOrNull()
        if (since != null) rows = rows.filter { it.updatedAt > since }
        if (selection?.contains("deleted = 0") == true) rows = rows.filter { !it.deleted }
        if (selection?.contains("syncId = ?") == true && !selectionArgs.isNullOrEmpty()) {
            rows = rows.filter { it.syncId == selectionArgs[0] }
        }
        val cursor = MatrixCursor(cols.toTypedArray())
        for (row in rows) {
            cursor.addRow(cols.map { col ->
                val value: Any? = when (col) {
                    COL_SYNC_ID -> row.syncId
                    COL_UPDATED_AT -> row.updatedAt
                    COL_DELETED -> if (row.deleted) 1 else 0
                    COL_PAYLOAD -> row.payloadJson
                    COL_HASH -> "hash"
                    else -> null
                }
                value
            }.toTypedArray())
        }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val payload = values?.getAsString(COL_PAYLOAD) ?: return uri
        val row = parseRow(payload) ?: return uri
        table(uri)[row.syncId] = row
        return uri
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val table = table(uri)
        if (uri.lastPathSegment == OP_PURGE || selection == null && uri.pathSegments.lastOrNull() in OPS) {
            val n = table.size; table.clear(); return n
        }
        val id = selectionArgs?.firstOrNull() ?: return 0
        // Passwords hard-delete locally; notes/todos soft-delete (tombstone).
        if (uri.authority == "com.vayunmathur.passwords.relay") {
            return if (table.remove(id) != null) 1 else 0
        }
        val row = table[id] ?: return 0
        // Soft-delete: tombstone the row and flip its envelope's deleted flag
        row.deleted = true
        row.payloadJson = runCatching {
            val obj = Json.parseToJsonElement(row.payloadJson).jsonObject
            buildJsonObject {
                for ((k, v) in obj) put(k, v)
                put("deleted", JsonPrimitive(true))
            }.toString()
        }.getOrDefault(row.payloadJson)
        return 1
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        insert(uri, values); return 1
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.fake.relay"

    companion object {
        private const val COL_SYNC_ID = "syncId"
        private const val COL_UPDATED_AT = "updatedAt"
        private const val COL_DELETED = "deleted"
        private const val COL_PAYLOAD = "payloadJson"
        private const val COL_HASH = "contentHash"
        private const val OP_PURGE = "deleteByVault"
        private val OPS = setOf("querySince", "upsertRaw", "deleteByVault")

        /** Registers one fake provider per authority (idempotent per authority). */
        fun install(vararg authorities: String): Map<String, FakeIpcProvider> {
            val out = mutableMapOf<String, FakeIpcProvider>()
            for (authority in authorities) {
                val provider = FakeIpcProvider()
                ShadowContentResolver.registerProviderInternal(authority, provider)
                out[authority] = provider
            }
            return out
        }
    }
}
