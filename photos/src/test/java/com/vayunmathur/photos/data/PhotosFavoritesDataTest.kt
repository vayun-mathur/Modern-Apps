package com.vayunmathur.photos.data

import androidx.room3.Room
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #912 data layer: MIGRATION_19_20 against a populated v19 Photo table, and the
 * column-targeted favourite write the toggle and the sync both rely on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosFavoritesDataTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db: PhotoDatabase = Room.inMemoryDatabaseBuilder(context, PhotoDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    @AfterTest
    fun closeDb() {
        db.close()
    }

    @Test
    fun migration19To20AddsIsFavoriteAndKeepsExistingRows() = runBlocking<Unit> {
        val connection = androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(":memory:")
        try {
            // The v19 Photo table as Room generated it (all columns, no isFavorite),
            // holding rows with scan state that must survive the upgrade.
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `Photo` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, `uri` TEXT NOT NULL, `date` INTEGER NOT NULL, " +
                    "`width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `dateModified` INTEGER NOT NULL, " +
                    "`exifSet` INTEGER NOT NULL, `lat` REAL, `long` REAL, `duration` INTEGER, " +
                    "`fullWidth` INTEGER, `fullHeight` INTEGER, `croppedWidth` INTEGER, " +
                    "`croppedHeight` INTEGER, `croppedLeft` INTEGER, `croppedTop` INTEGER, " +
                    "`projectionType` TEXT, `isTrashed` INTEGER NOT NULL, `faceScanned` INTEGER NOT NULL, " +
                    "`ocrText` TEXT, `ocrScanned` INTEGER NOT NULL, `clipEmbedding` BLOB, " +
                    "`clipScanned` INTEGER NOT NULL, `mimeType` TEXT, `ocrBoxes` TEXT, `album` TEXT)",
            )
            connection.execSQL(
                "INSERT INTO Photo (id, name, uri, date, width, height, dateModified, exifSet, " +
                    "isTrashed, faceScanned, ocrScanned, clipScanned, ocrText, album) VALUES " +
                    "(1, 'a', 'content://media/1', 10, 1, 1, 1, 1, 0, 1, 1, 1, 'hello', 'Camera'), " +
                    "(2, 'b', 'content://media/2', 20, 1, 1, 1, 1, 1, 0, 0, 0, NULL, NULL)",
            )

            MIGRATION_19_20.migrate(connection)

            data class Column(val type: String, val notNull: Boolean, val default: String?)
            val columns = connection.prepare("PRAGMA table_info(Photo)").use { statement ->
                val byName = mutableMapOf<String, Column>()
                while (statement.step()) {
                    byName[statement.getText(1)] = Column(
                        type = statement.getText(2),
                        notNull = statement.getLong(3) == 1L,
                        default = if (statement.isNull(4)) null else statement.getText(4),
                    )
                }
                byName
            }
            // Room's entity column for `val isFavorite: Boolean = false`.
            assertEquals(Column("INTEGER", notNull = true, default = "0"), columns["isFavorite"])

            val rows = connection.prepare(
                "SELECT id, isFavorite, ocrText, album, faceScanned FROM Photo ORDER BY id",
            ).use { statement ->
                val out = mutableListOf<List<Any?>>()
                while (statement.step()) {
                    out += listOf(
                        statement.getLong(0),
                        statement.getLong(1),
                        if (statement.isNull(2)) null else statement.getText(2),
                        if (statement.isNull(3)) null else statement.getText(3),
                        statement.getLong(4),
                    )
                }
                out
            }
            // Both rows survive, start as not favourite, and keep their other columns.
            assertEquals<List<List<Any?>>>(
                listOf(
                    listOf(1L, 0L, "hello", "Camera", 1L),
                    listOf(2L, 0L, null, null, 0L),
                ),
                rows,
            )
        } finally {
            connection.close()
        }
    }

    @Test
    fun migrationListEndsAtVersion20() {
        val last = PhotoDatabase.migrations.last()
        assertEquals(19 to 20, last.startVersion to last.endVersion)
    }

    private fun photo(name: String, date: Long, trashed: Boolean = false) = Photo(
        name = name,
        uri = "content://media/$name",
        date = date,
        width = 100,
        height = 100,
        dateModified = date,
        exifSet = true,
        lat = null,
        long = null,
        videoData = null,
        panoData = null,
        isTrashed = trashed,
        clipEmbedding = byteArrayOf(1, 2, 3),
        clipScanned = true,
    )

    @Test
    fun setFavoriteFlipsOnlyThatColumnAndReachesTheGalleryFlow() = runTest {
        val dao = db.photoDao()
        dao.upsertAll(listOf(photo("a", 100L), photo("b", 200L)))
        val ids = dao.getAll().associate { it.name to it.id }

        dao.setFavorite(listOf(ids.getValue("a")), true)

        // getAllFlow lists its columns explicitly: isFavorite must be among them.
        val flow = dao.getAllFlow().first().associateBy { it.name }
        assertTrue(flow.getValue("a").isFavorite)
        assertFalse(flow.getValue("b").isFavorite)
        // Column-targeted: the embedding written above is untouched.
        val a = dao.getAll().single { it.name == "a" }
        assertContentEquals(byteArrayOf(1, 2, 3), a.clipEmbedding)
        assertTrue(a.clipScanned)

        dao.setFavorite(listOf(ids.getValue("a")), false)
        assertFalse(dao.getAllFlow().first().single { it.name == "a" }.isFavorite)
    }
}
