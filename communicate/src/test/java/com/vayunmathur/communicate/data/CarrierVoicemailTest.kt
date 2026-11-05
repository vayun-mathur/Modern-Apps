package com.vayunmathur.communicate.data

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.VoicemailContract
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Carrier visual-voicemail provider reads ([android.provider.VoicemailContract]).
 *
 * The stub android.jar answers `ContentResolver.query` with "not mocked", so a
 * fake provider serves the rows as [MatrixCursor]s. (Robolectric 4.17's
 * `ShadowContentResolver.setCursor(Uri, …)` only accepts the stub
 * `BaseCursor`, whose accessors all throw — a provider is the data-bearing
 * path.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoicemailEntryTest {

    class FakeVoicemailProvider : ContentProvider() {
        var rows: List<Array<Any?>> = emptyList()
        var updateResult: Int = 0
        var deleteResult: Int = 0

        private val columns = arrayOf(
            VoicemailContract.Voicemails._ID,
            VoicemailContract.Voicemails.NUMBER,
            VoicemailContract.Voicemails.DATE,
            VoicemailContract.Voicemails.DURATION,
            VoicemailContract.Voicemails.HAS_CONTENT,
            VoicemailContract.Voicemails.MIME_TYPE,
            VoicemailContract.Voicemails.TRANSCRIPTION,
            VoicemailContract.Voicemails.IS_READ,
            VoicemailContract.Voicemails.PHONE_ACCOUNT_ID,
        )

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?,
        ): Cursor {
            val cursor = MatrixCursor(columns, rows.size)
            rows.forEach { cursor.addRow(it) }
            return cursor
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int =
            deleteResult

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<String>?,
        ): Int = updateResult
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var provider: FakeVoicemailProvider

    private fun setUp(rows: List<Array<Any?>> = emptyList(), grantWrite: Boolean = true) {
        provider = Robolectric.setupContentProvider(
            FakeVoicemailProvider::class.java,
            VoicemailContract.AUTHORITY,
        )
        provider.rows = rows
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CALL_LOG)
        if (grantWrite) {
            shadowOf(context as Application).grantPermissions(Manifest.permission.WRITE_CALL_LOG)
        }
    }

    private fun row(
        id: Long = 1L,
        number: String? = "+14155550102",
        date: Long = 1_785_566_000_000L,
        duration: Long = 42L,
        hasContent: Int = 1,
        mimeType: String? = "audio/amr",
        transcription: String? = null,
        isRead: Int = 0,
        account: String? = null,
    ): Array<Any?> =
        arrayOf(id, number, date, duration, hasContent, mimeType, transcription, isRead, account)

    private fun entry(id: Long = 7L, read: Boolean = false) = VoicemailEntry(
        id = id,
        phoneNumber = "+14155550102",
        timestampMillis = 0,
        durationSeconds = 10,
        contentUri = Uri.withAppendedPath(
            VoicemailContract.Voicemails.CONTENT_URI,
            id.toString(),
        ).toString(),
        mimeType = "audio/amr",
        hasAudio = true,
        transcript = null,
        isRead = read,
    )

    @Test
    fun `maps provider row to model`() {
        setUp(listOf(row(transcription = "Call me back", isRead = 0)))

        val rows = CommunicateRepository.loadCarrierVoicemails(context)

        assertEquals(1, rows.size)
        val loaded = rows.single()
        assertEquals(1L, loaded.id)
        assertEquals("+14155550102", loaded.phoneNumber)
        assertEquals(1_785_566_000_000L, loaded.timestampMillis)
        assertEquals(42L, loaded.durationSeconds)
        assertEquals(
            Uri.withAppendedPath(VoicemailContract.Voicemails.CONTENT_URI, "1").toString(),
            loaded.contentUri,
        )
        assertEquals("audio/amr", loaded.mimeType)
        assertTrue(loaded.hasAudio)
        assertEquals("Call me back", loaded.transcript)
        assertFalse(loaded.isRead)
        assertNull(loaded.subscriptionId)
        assertEquals(CommunicateLine.Sim, loaded.line)
    }

    @Test
    fun `blank number falls back to Unknown and blank transcript to null`() {
        setUp(listOf(row(number = "  ", transcription = "  ", hasContent = 0)))

        val loaded = CommunicateRepository.loadCarrierVoicemails(context).single()

        assertEquals("Unknown", loaded.phoneNumber)
        assertNull(loaded.transcript)
        assertFalse(loaded.hasAudio)
    }

    @Test
    fun `returns empty without read permission`() {
        setUp(listOf(row()))
        shadowOf(context as Application).denyPermissions(Manifest.permission.READ_CALL_LOG)

        assertTrue(CommunicateRepository.loadCarrierVoicemails(context).isEmpty())
    }

    @Test
    fun `returns empty when provider has no rows`() {
        setUp()

        assertTrue(CommunicateRepository.loadCarrierVoicemails(context).isEmpty())
    }

    @Test
    fun `mark read updates provider and returns true`() {
        setUp()
        provider.updateResult = 1

        assertTrue(CommunicateRepository.markVoicemailRead(context, entry(), read = true))
    }

    @Test
    fun `mark read returns false without write permission`() {
        setUp(grantWrite = false)

        assertFalse(CommunicateRepository.markVoicemailRead(context, entry(), read = true))
    }

    @Test
    fun `mark read returns false when provider updates nothing`() {
        setUp()
        provider.updateResult = 0

        assertFalse(CommunicateRepository.markVoicemailRead(context, entry(), read = true))
    }

    @Test
    fun `delete removes row and returns true`() {
        setUp()
        provider.deleteResult = 1

        assertTrue(CommunicateRepository.deleteVoicemail(context, entry()))
    }

    @Test
    fun `delete returns false without write permission`() {
        setUp(grantWrite = false)

        assertFalse(CommunicateRepository.deleteVoicemail(context, entry()))
    }
}
