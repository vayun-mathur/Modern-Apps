package com.vayunmathur.launcher.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.domain.toRaw
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DAO tests for the workspace table: CRUD round trips, container queries,
 * rank ordering, widget-id bookkeeping, and the folder-child lifecycle that
 * [LauncherRepository] builds on.
 *
 * Runs against a real in-memory Room database under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
class LauncherDaoTest {

    private lateinit var db: LauncherDatabase
    private lateinit var dao: LauncherItemDao

    @BeforeTest
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, LauncherDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        dao = db.itemDao()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun app(
        screen: Int = 0,
        cellX: Int = 0,
        cellY: Int = 0,
        packageName: String = "com.example.app",
        className: String = "com.example.app.Main",
    ) = LauncherItemEntity(
        itemType = LauncherItemType.APPLICATION,
        containerId = ContainerRef.Desktop.toRaw(),
        screen = screen,
        cellX = cellX,
        cellY = cellY,
        packageName = packageName,
        className = className,
    )

    @Test
    fun upsertAndGetRoundTrip() = runTest {
        assertNull(dao.get(999))
        val id = dao.upsert(app(cellX = 2, cellY = 3))
        val got = dao.get(id) ?: error("missing")
        assertEquals(2, got.cellX)
        assertEquals(3, got.cellY)
        assertEquals(LauncherItemType.APPLICATION, got.itemType)
        assertEquals("com.example.app", got.packageName)
        // Upsert replaces.
        dao.upsert(got.copy(id = id, cellX = 5))
        assertEquals(5, dao.get(id)?.cellX)
    }

    @Test
    fun countAndGetAll() = runTest {
        assertEquals(0, dao.count())
        dao.upsert(app(cellX = 0))
        dao.upsert(app(cellX = 1))
        assertEquals(2, dao.count())
        assertEquals(2, dao.getAll().size)
    }

    @Test
    fun desktopAndHotseatQueriesSplitByContainer() = runTest {
        val hotseatId = dao.upsert(
            app().copy(containerId = ContainerRef.Hotseat.toRaw(), rank = 0),
        )
        dao.upsert(app(cellX = 1))
        assertEquals(1, dao.getDesktopItems().size)
        val hotseat = dao.getHotseatItems()
        assertEquals(listOf(hotseatId), hotseat.map { it.id })
    }

    @Test
    fun hotseatOrderedByRank() = runTest {
        val first = dao.upsert(app().copy(containerId = ContainerRef.Hotseat.toRaw(), rank = 2))
        val second = dao.upsert(app().copy(containerId = ContainerRef.Hotseat.toRaw(), rank = 0))
        val third = dao.upsert(app().copy(containerId = ContainerRef.Hotseat.toRaw(), rank = 1))
        assertEquals(listOf(second, third, first), dao.getHotseatItems().map { it.id })
    }

    @Test
    fun folderChildrenOrderedByRank() = runTest {
        val folderId = dao.upsert(
            LauncherItemEntity(
                itemType = LauncherItemType.FOLDER,
                containerId = ContainerRef.Desktop.toRaw(),
            ),
        )
        val a = dao.upsert(app().copy(containerId = folderId, rank = 1))
        val b = dao.upsert(app().copy(containerId = folderId, rank = 0))
        val children = dao.getFolderChildren(folderId)
        assertEquals(listOf(b, a), children.map { it.id })
        assertEquals(2, dao.countFolderChildren(folderId))
    }

    @Test
    fun moveChangesContainerAndRank() = runTest {
        val id = dao.upsert(app(cellX = 0, cellY = 0))
        dao.move(id, ContainerRef.Hotseat.toRaw(), 0, 0, 0, 2)
        val got = dao.get(id) ?: error("missing")
        assertEquals(ContainerRef.Hotseat, got.container)
        assertEquals(2, got.rank)
        assertTrue(dao.getDesktopItems().none { it.id == id })
    }

    @Test
    fun resizeToChangesSpan() = runTest {
        val id = dao.upsert(app())
        dao.resizeTo(id, 1, 1, 2, 3)
        val got = dao.get(id) ?: error("missing")
        assertEquals(1, got.cellX)
        assertEquals(1, got.cellY)
        assertEquals(2, got.spanX)
        assertEquals(3, got.spanY)
    }

    @Test
    fun setTitleAndRank() = runTest {
        val id = dao.upsert(app())
        assertNull(dao.get(id)?.title)
        dao.setTitle(id, "My App")
        assertEquals("My App", dao.get(id)?.title)
        dao.setRank(id, 4)
        assertEquals(4, dao.get(id)?.rank)
    }

    @Test
    fun setHiddenTogglesVisibility() = runTest {
        val id = dao.upsert(app())
        dao.setHidden(id, true)
        assertEquals(true, dao.get(id)?.hidden)
        dao.setHidden(id, false)
        assertEquals(false, dao.get(id)?.hidden)
    }

    @Test
    fun usedWidgetIdsOnlyListsWidgets() = runTest {
        dao.upsert(app())
        dao.upsert(
            LauncherItemEntity(
                itemType = LauncherItemType.APPWIDGET,
                containerId = ContainerRef.Desktop.toRaw(),
                appWidgetId = 42,
                appWidgetProvider = "com.example/.Widget",
            ),
        )
        assertEquals(listOf(42), dao.getUsedWidgetIds())
    }

    @Test
    fun deleteByIdRemovesRow() = runTest {
        val id = dao.upsert(app())
        dao.deleteById(id)
        assertNull(dao.get(id))
        assertEquals(0, dao.count())
    }

    @Test
    fun containerRefRoundTripThroughRaw() = runTest {
        val folderId = dao.upsert(
            LauncherItemEntity(
                itemType = LauncherItemType.FOLDER,
                containerId = ContainerRef.Desktop.toRaw(),
            ),
        )
        val childId = dao.upsert(app().copy(containerId = folderId))
        val child = dao.get(childId) ?: error("missing")
        assertEquals(ContainerRef.Folder(folderId), child.container)
        assertNotNull(dao.get(folderId))
    }
}
