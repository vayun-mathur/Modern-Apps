package com.vayunmathur.launcher.search

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.appsearch.app.AppSearchSchema
import androidx.appsearch.app.GenericDocument
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.SearchSpec
import androidx.appsearch.app.SetSchemaRequest
import androidx.appsearch.localstorage.LocalStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

sealed class SearchResult {
    data class App(val name: String, val packageName: String) : SearchResult()
    data class Contact(val name: String, val contactId: String, val phones: String, val lookupKey: String) : SearchResult()
    data class CalendarEvent(val title: String, val eventId: Long, val date: String, val location: String) : SearchResult()
}

data class GroupedResults(
    val apps: List<SearchResult.App> = emptyList(),
    val contacts: List<SearchResult.Contact> = emptyList(),
    val events: List<SearchResult.CalendarEvent> = emptyList()
)

private const val SCHEMA_APP = "AppDocument"
private const val SCHEMA_CONTACT = "ContactDocument"
private const val SCHEMA_CALENDAR = "CalendarDocument"

class SearchManager(private val context: Context) {
    private var session: androidx.appsearch.app.AppSearchSession? = null

    private val appSchema = AppSearchSchema.Builder(SCHEMA_APP)
        .addProperty(AppSearchSchema.StringPropertyConfig.Builder("name")
            .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
            .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
            .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
            .build())
        .addProperty(AppSearchSchema.StringPropertyConfig.Builder("packageName")
            .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
            .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
            .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
            .build())
        .build()

    private val contactSchema = AppSearchSchema.Builder(SCHEMA_CONTACT)
        .addProperty(AppSearchSchema.StringPropertyConfig.Builder("name")
            .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
            .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
            .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
            .build())
        .addProperty(AppSearchSchema.StringPropertyConfig.Builder("phones")
            .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
            .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
            .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
            .build())
        .addProperty(AppSearchSchema.StringPropertyConfig.Builder("lookupKey")
            .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
            .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_NONE)
            .build())
        .build()

    private val calendarSchema = AppSearchSchema.Builder(SCHEMA_CALENDAR)
        .addProperty(AppSearchSchema.StringPropertyConfig.Builder("title")
            .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
            .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
            .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
            .build())
        .addProperty(AppSearchSchema.StringPropertyConfig.Builder("date")
            .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
            .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
            .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
            .build())
        .addProperty(AppSearchSchema.StringPropertyConfig.Builder("location")
            .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
            .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
            .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
            .build())
        .addProperty(AppSearchSchema.LongPropertyConfig.Builder("eventId")
            .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
            .build())
        .build()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        session = LocalStorage.createSearchSessionAsync(
            LocalStorage.SearchContext.Builder(context, "launcher_db").build()
        ).get()

        session!!.setSchemaAsync(
            SetSchemaRequest.Builder()
                .addSchemas(appSchema, contactSchema, calendarSchema)
                .setForceOverride(true)
                .build()
        ).get()
    }

    suspend fun indexApps() = withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        val docs = apps.mapNotNull { info ->
            val label = info.loadLabel(context.packageManager).toString()
            GenericDocument.Builder<GenericDocument.Builder<*>>(
                "apps", info.activityInfo.packageName, SCHEMA_APP
            )
                .setPropertyString("name", label)
                .setPropertyString("packageName", info.activityInfo.packageName)
                .build()
        }
        if (docs.isNotEmpty()) {
            session?.putAsync(
                PutDocumentsRequest.Builder().addGenericDocuments(docs).build()
            )?.get()
        }
    }

    suspend fun indexContacts() = withContext(Dispatchers.IO) {
        val docs = mutableListOf<GenericDocument>()
        try {
            val cursor: Cursor? = context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                    ContactsContract.Contacts.HAS_PHONE_NUMBER,
                    ContactsContract.Contacts.LOOKUP_KEY
                ),
                null, null, null
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getString(0)
                    val name = it.getString(1) ?: continue
                    val hasPhone = it.getInt(2) > 0
                    val lookupKey = it.getString(3) ?: ""
                    var phones = ""
                    if (hasPhone) {
                        val phoneCursor = context.contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                            arrayOf(id), null
                        )
                        phoneCursor?.use { pc ->
                            val numbers = mutableListOf<String>()
                            while (pc.moveToNext()) {
                                pc.getString(0)?.let { n -> numbers.add(n) }
                            }
                            phones = numbers.joinToString(", ")
                        }
                    }
                    docs.add(
                        GenericDocument.Builder<GenericDocument.Builder<*>>(
                            "contacts", "contact_$id", SCHEMA_CONTACT
                        )
                            .setPropertyString("name", name)
                            .setPropertyString("phones", phones)
                            .setPropertyString("lookupKey", lookupKey)
                            .build()
                    )
                }
            }
        } catch (_: SecurityException) { }

        if (docs.isNotEmpty()) {
            session?.putAsync(
                PutDocumentsRequest.Builder().addGenericDocuments(docs).build()
            )?.get()
        }
    }

    suspend fun indexCalendarEvents() = withContext(Dispatchers.IO) {
        val docs = mutableListOf<GenericDocument>()
        try {
            val cursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(
                    CalendarContract.Events._ID,
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.EVENT_LOCATION
                ),
                null, null, "${CalendarContract.Events.DTSTART} DESC LIMIT 200"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val title = it.getString(1) ?: continue
                    val dtStart = it.getLong(2)
                    val location = it.getString(3) ?: ""
                    val dateStr = kotlin.time.Instant.fromEpochMilliseconds(dtStart)
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                        .date.toString()
                    docs.add(
                        GenericDocument.Builder<GenericDocument.Builder<*>>(
                            "calendar", "event_$id", SCHEMA_CALENDAR
                        )
                            .setPropertyString("title", title)
                            .setPropertyString("date", dateStr)
                            .setPropertyString("location", location)
                            .setPropertyLong("eventId", id)
                            .build()
                    )
                }
            }
        } catch (_: SecurityException) { }

        if (docs.isNotEmpty()) {
            session?.putAsync(
                PutDocumentsRequest.Builder().addGenericDocuments(docs).build()
            )?.get()
        }
    }

    suspend fun search(query: String): GroupedResults = withContext(Dispatchers.IO) {
        if (query.isBlank() || session == null) return@withContext GroupedResults()

        val searchResults = session!!.search(
            query,
            SearchSpec.Builder()
                .setTermMatch(SearchSpec.TERM_MATCH_PREFIX)
                .setResultCountPerPage(50)
                .build()
        )

        val page = searchResults.nextPageAsync.get()
        val apps = mutableListOf<SearchResult.App>()
        val contacts = mutableListOf<SearchResult.Contact>()
        val events = mutableListOf<SearchResult.CalendarEvent>()

        for (result in page) {
            val doc = result.genericDocument
            when (doc.schemaType) {
                SCHEMA_APP -> apps.add(
                    SearchResult.App(
                        name = doc.getPropertyString("name") ?: "",
                        packageName = doc.getPropertyString("packageName") ?: ""
                    )
                )
                SCHEMA_CONTACT -> contacts.add(
                    SearchResult.Contact(
                        name = doc.getPropertyString("name") ?: "",
                        contactId = doc.id.removePrefix("contact_"),
                        phones = doc.getPropertyString("phones") ?: "",
                        lookupKey = doc.getPropertyString("lookupKey") ?: ""
                    )
                )
                SCHEMA_CALENDAR -> events.add(
                    SearchResult.CalendarEvent(
                        title = doc.getPropertyString("title") ?: "",
                        eventId = doc.getPropertyLong("eventId"),
                        date = doc.getPropertyString("date") ?: "",
                        location = doc.getPropertyString("location") ?: ""
                    )
                )
            }
        }

        GroupedResults(apps, contacts, events)
    }

    fun close() {
        session?.close()
    }
}
