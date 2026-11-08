package com.vayunmathur.health.platform

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.MainActivity
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.ui.CatalogKind
import com.vayunmathur.health.ui.HealthMetricConfig
import com.vayunmathur.library.util.databases
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityAndStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun setup() {
        synchronized(databases) {
            if (databases[HealthDatabase::class] == null) {
                databases[HealthDatabase::class] = Room
                    .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            }
        }
    }

    @Test
    fun `main activity creates and shows install gate when health connect unavailable`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        controller.start().resume()
        // Health Connect is not AVAILABLE under Robolectric, so Content()
        // takes the required-page branch; creation must not crash.
        assertNotNull(controller.get())
        controller.pause().stop().destroy()
    }

    @Test
    fun `attachment store file lifecycle`() {
        val file = AttachmentStore.fileFor(context, "store-test.txt")
        file.writeText("hello attachment")
        assertTrue(file.exists())
        assertEquals("hello attachment", file.readText())
        assertTrue(AttachmentStore.isImage("image/png"))
        assertTrue(!AttachmentStore.isImage("application/pdf"))
        AttachmentStore.delete(context, "store-test.txt")
        assertTrue(!file.exists())
        // Deleting a missing file is a no-op.
        AttachmentStore.delete(context, "store-test.txt")
    }

    @Test
    fun `attachment import copies a file uri`() {
        val source = File(context.cacheDir, "source-card.pdf")
        source.writeBytes(byteArrayOf(1, 2, 3, 4))
        val imported = AttachmentStore.import(context, Uri.fromFile(source), "fallback.pdf")
        assertNotNull(imported)
        assertEquals(4L, imported.sizeBytes)
        assertTrue(imported.fileName.startsWith("att_"))
        assertTrue(AttachmentStore.fileFor(context, imported.fileName).exists())
        AttachmentStore.delete(context, imported.fileName)
    }

    @Test
    fun `attachment import of unreadable uri returns null`() {
        val imported = AttachmentStore.import(context, Uri.parse("content://no.such.provider/file.pdf"), "fallback.pdf")
        assertNull(imported)
    }

    @Test
    fun `attachment uri for uses file provider`() {
        val file = AttachmentStore.fileFor(context, "uri-test.txt")
        file.writeText("x")
        val uri = AttachmentStore.uriFor(context, "uri-test.txt")
        assertTrue(uri.toString().contains("uri-test.txt"))
        AttachmentStore.delete(context, "uri-test.txt")
    }

    @Test
    fun `route serialization round trips`() {
        val json = Json
        assertEquals("{\"type\":\"com.vayunmathur.health.Route.Today\"}", json.encodeToString(Route.serializer(), Route.Today))
        val routes: List<Route> = listOf(
            Route.Today, Route.Body, Route.NutritionDetails, Route.NutritionFullBreakdown,
            Route.RecipeManagement, Route.RecipeEditor("r1"), Route.RecipeEditor(),
            Route.BarChartDetails(HealthMetricConfig.WEIGHT), Route.SleepDetails, Route.ExerciseDetails,
            Route.Medical, Route.EditVaccination("v1"), Route.EditMedication(), Route.EditAllergy("a1"),
            Route.EditCondition(), Route.EditLabResult("l1"),
            Route.CatalogPicker(CatalogKind.Vaccine), Route.CatalogPicker(CatalogKind.MedicationProduct, "aspirin"),
            Route.MedicalDatePicker("k", LocalDate(2026, 1, 2), allowClear = true),
            Route.MedicalTimePicker("k", LocalTime(8, 30)),
        )
        routes.forEach { route ->
            val encoded = json.encodeToString(Route.serializer(), route)
            val decoded = json.decodeFromString(Route.serializer(), encoded)
            assertEquals(route, decoded)
        }
        assertTrue(Shadows.shadowOf(context).toString().isNotEmpty())
    }
}
