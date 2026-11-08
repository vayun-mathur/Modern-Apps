package com.vayunmathur.health.platform

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.MedicalAttachment
import com.vayunmathur.health.data.MedicalKind
import com.vayunmathur.health.data.VaccinationEntry
import com.vayunmathur.library.util.databases
import java.io.File
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaccinationSavePathsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val at: Instant = Instant.parse("2024-06-05T08:00:00Z")

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

    private fun repo(): HealthRepository = HealthRepository.get(context)
    private fun vm(): MedicalViewModel = MedicalViewModel(context, repo())

    private suspend fun settle(cond: suspend () -> Boolean) {
        repeat(200) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            kotlinx.coroutines.delay(5)
        }
    }

    @Test
    fun `editing a foreign vaccination detaches and clears fhir columns`() = runTest {
        val repo = repo()
        repo.upsertVaccination(VaccinationEntry(id = "vac-foreign-1", displayName = "Foreign Flu", occurredAt = at, fhirResourceId = "fhir-foreign-1", dataSourceId = "provider-app"))
        val vm = vm()
        vm.startVaccinationDraft("vac-foreign-1")
        settle { vm.vaccinationDraft.value.editingId == "vac-foreign-1" }
        vm.editVaccinationDraft { it.copy(displayName = "Foreign Flu Edited", lotNumber = "LOT-F") }
        vm.saveVaccinationDraft("fallback.pdf")
        settle { repo.getVaccination("vac-foreign-1")?.displayName == "Foreign Flu Edited" }
        val saved = repo.getVaccination("vac-foreign-1")
        assertNotNull(saved)
        assertNull(saved.fhirResourceId)
        assertNull(saved.dataSourceId)
        assertEquals("LOT-F", saved.lotNumber)
        assertTrue(repo.isTombstoned(MedicalKind.Vaccination, "provider-app", "fhir-foreign-1"))
    }

    @Test
    fun `saving with an attachment imports the file and links it`() = runTest {
        val repo = repo()
        val source = File(context.cacheDir, "vac-card-source.pdf")
        source.writeBytes(byteArrayOf(9, 8, 7))
        val vm = vm()
        vm.startVaccinationDraft()
        vm.editVaccinationDraft {
            it.copy(displayName = "Attachment Vaccine", attachmentUris = listOf(Uri.fromFile(source).toString()))
        }
        vm.saveVaccinationDraft("fallback.pdf")
        settle { repo.getVaccinationsFlow().first().any { it.displayName == "Attachment Vaccine" } }
        val entry = repo.getVaccinationsFlow().first().first { it.displayName == "Attachment Vaccine" }
        settle { repo.getAttachmentsFor(entry.id).isNotEmpty() }
        val attachments = repo.getAttachmentsFor(entry.id)
        assertEquals(1, attachments.size)
        assertTrue(AttachmentStore.fileFor(context, attachments.first().fileName).exists())
    }

    @Test
    fun `removing a saved attachment on edit deletes file and row`() = runTest {
        val repo = repo()
        repo.upsertVaccination(VaccinationEntry(id = "vac-att-1", displayName = "Card Vaccine", occurredAt = at))
        val stored = AttachmentStore.fileFor(context, "vac-att-card.txt")
        stored.writeText("card")
        val attachment = MedicalAttachment(id = "att-remove-1", vaccinationId = "vac-att-1", fileName = "vac-att-card.txt", displayName = "Card", mimeType = "text/plain", sizeBytes = 4L, importedAt = at)
        repo.insertAttachment(attachment)
        val vm = vm()
        vm.startVaccinationDraft("vac-att-1")
        settle { vm.vaccinationDraft.value.editingId == "vac-att-1" }
        // The draft loads saved attachments through the ViewModel flows;
        // mark the attachment removed directly if it was loaded.
        vm.editVaccinationDraft { it.copy(removedAttachmentIds = setOf("att-remove-1")) }
        vm.saveVaccinationDraft("fallback.pdf")
        settle { repo.getAttachmentsFor("vac-att-1").isEmpty() }
        assertTrue(repo.getAttachmentsFor("vac-att-1").isEmpty())
        assertTrue(!stored.exists())
    }

    @Test
    fun `deleting a vaccination deletes its attachments`() = runTest {
        val repo = repo()
        val entry = VaccinationEntry(id = "vac-del-att", displayName = "Delete Card Vaccine", occurredAt = at)
        repo.upsertVaccination(entry)
        val stored = AttachmentStore.fileFor(context, "vac-del-card.txt")
        stored.writeText("card")
        repo.insertAttachment(MedicalAttachment(id = "att-del-1", vaccinationId = "vac-del-att", fileName = "vac-del-card.txt", displayName = "Card", mimeType = "text/plain", sizeBytes = 4L, importedAt = at))
        val vm = vm()
        vm.deleteVaccination(entry)
        settle { repo.getVaccination("vac-del-att") == null }
        assertNull(repo.getVaccination("vac-del-att"))
        assertTrue(repo.getAttachmentsFor("vac-del-att").isEmpty())
        assertTrue(!stored.exists())
    }
}
