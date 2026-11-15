@file:OptIn(
    androidx.health.connect.client.feature.ExperimentalPersonalHealthRecordApi::class,
    androidx.health.connect.client.ExperimentalMatchmakingApi::class,
)

package com.vayunmathur.health.platform

import android.app.Application
import android.os.Looper
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.aggregate.AggregationResultGroupedByDuration
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.records.FhirResource
import androidx.health.connect.client.records.FhirVersion
import androidx.health.connect.client.records.MedicalDataSource
import androidx.health.connect.client.records.MedicalResource
import androidx.health.connect.client.records.MedicalResourceId
import androidx.health.connect.client.records.Record as HcRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.CreateMedicalDataSourceRequest
import androidx.health.connect.client.request.GetMedicalDataSourcesRequest
import androidx.health.connect.client.request.ReadMedicalResourcesInitialRequest
import androidx.health.connect.client.request.ReadMedicalResourcesPageRequest
import androidx.health.connect.client.request.ReadMedicalResourcesRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.request.UpsertMedicalResourceRequest
import androidx.health.connect.client.response.ChangesResponse
import androidx.health.connect.client.response.InsertRecordsResponse
import androidx.health.connect.client.response.ReadMedicalResourcesResponse
import androidx.health.connect.client.response.ReadRecordResponse
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.data.AllergyEntry
import com.vayunmathur.health.data.ConditionEntry
import com.vayunmathur.health.data.DeletedMedicalResource
import com.vayunmathur.health.data.DoseEvent
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.LabResultEntry
import com.vayunmathur.health.data.MedicalKind
import com.vayunmathur.health.data.MedicationEntry
import com.vayunmathur.health.data.SmokingStatus
import com.vayunmathur.health.data.VaccinationEntry
import com.vayunmathur.health.domain.FhirRecords
import com.vayunmathur.health.domain.SocialHistoryQuestions
import com.vayunmathur.library.util.databases
import java.time.Instant
import kotlin.reflect.KClass
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * The Health Connect → Room import ([MedicalImportOps][importFromHealthConnect])
 * against a fake PHR-capable client (see [PhrShadows] for the platform
 * gates). Provider resources land in Room under derived row ids, our own
 * stored resources are never duplicated, retractions remove rows and
 * tombstones stop them coming back, and pending mirrors plus remote
 * deletes are pushed before anything is read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    shadows = [
        C5ShadowSdkExtensions::class,
        C5ShadowPlatformFhirVersion::class,
        C5ShadowPlatformFhirResource::class,
        C5ShadowPlatformFhirResourceBuilder::class,
        C5ShadowPlatformMedicalDataSource::class,
        C5ShadowPlatformMedicalDataSourceBuilder::class,
        C5ShadowPlatformMedicalResource::class,
        C5ShadowPlatformMedicalResourceBuilder::class,
        C5ShadowPlatformMedicalResourceId::class,
        C5ShadowReadInitialBuilder::class, C5ShadowReadInitialRequest::class,
        C5ShadowReadPageBuilder::class, C5ShadowReadPageRequest::class,
        C5ShadowUpsertBuilder::class, C5ShadowUpsertRequest::class,
        C5ShadowCreateDataSourceBuilder::class, C5ShadowCreateDataSourceRequest::class,
        C5ShadowGetDataSourcesBuilder::class, C5ShadowGetDataSourcesRequest::class,
        C5ShadowDeleteResourcesBuilder::class, C5ShadowDeleteResourcesRequest::class,
    ],
)
class MedicalImportC5Test {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val at: Instant = Instant.parse("2026-03-04T09:30:00Z")
    private val ownSource = "own-source"
    private val hospital = "hospital-source"

    private class FakePhrClient(val ownSource: String) : HealthConnectClient {
        val byType = mutableMapOf<Int, MutableList<MedicalResource>>()
        val upserts = mutableListOf<UpsertMedicalResourceRequest>()
        val deletes = mutableListOf<MedicalResourceId>()
        var failReads = false
        private val fhirVersion = FhirVersion(4, 0, 1)

        override val permissionController: PermissionController
            get() = throw UnsupportedOperationException("not used")

        override val features: HealthConnectFeatures = object : HealthConnectFeatures {
            override fun getFeatureStatus(feature: Int): Int =
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        }

        fun add(type: Int, resourceType: Int, source: String, id: String, json: String) {
            byType.getOrPut(type) { mutableListOf() } += MedicalResource(
                type,
                MedicalResourceId(source, resourceType, id),
                source,
                fhirVersion,
                FhirResource(resourceType, id, json),
            )
        }

        override suspend fun readMedicalResources(
            request: ReadMedicalResourcesRequest,
        ): ReadMedicalResourcesResponse {
            if (failReads) throw IllegalStateException("read exploded")
            return when (request) {
                is ReadMedicalResourcesInitialRequest -> {
                    val all = byType[request.medicalResourceType].orEmpty()
                    val page = all.take(2)
                    ReadMedicalResourcesResponse(
                        page,
                        if (all.size > 2) "page-2" else null,
                        (all.size - page.size).coerceAtLeast(0),
                    )
                }
                is ReadMedicalResourcesPageRequest -> {
                    // The fake keeps one page token: it resumes the most
                    // recent initial read, which is how the app pages.
                    val all = byType.values.firstOrNull { it.size > 2 }.orEmpty()
                    ReadMedicalResourcesResponse(all.drop(2), null, 0)
                }
                else -> ReadMedicalResourcesResponse(emptyList(), null, 0)
            }
        }

        override suspend fun readMedicalResources(ids: List<MedicalResourceId>): List<MedicalResource> =
            byType.values.flatten().filter { res -> ids.any { it == res.id } }

        override suspend fun upsertMedicalResources(
            requests: List<UpsertMedicalResourceRequest>,
        ): List<MedicalResource> {
            upserts += requests
            return requests.mapIndexed { i, req ->
                val id = "written-$i"
                MedicalResource(
                    MedicalResource.MEDICAL_RESOURCE_TYPE_VACCINES,
                    MedicalResourceId(req.dataSourceId, FhirResource.FHIR_RESOURCE_TYPE_PATIENT, id),
                    req.dataSourceId,
                    fhirVersion,
                    FhirResource(FhirResource.FHIR_RESOURCE_TYPE_PATIENT, id, req.data),
                )
            }
        }

        override suspend fun deleteMedicalResources(ids: List<MedicalResourceId>) {
            deletes += ids
        }

        override suspend fun deleteMedicalResources(
            request: androidx.health.connect.client.request.DeleteMedicalResourcesRequest,
        ) = Unit

        override suspend fun getMedicalDataSources(
            request: GetMedicalDataSourcesRequest,
        ): List<MedicalDataSource> = listOf(ownDataSource())

        override suspend fun getMedicalDataSources(packageNames: List<String>): List<MedicalDataSource> =
            listOf(ownDataSource())

        override suspend fun createMedicalDataSource(
            request: CreateMedicalDataSourceRequest,
        ): MedicalDataSource = ownDataSource()

        override suspend fun deleteMedicalDataSourceWithData(id: String) = Unit

        private fun ownDataSource(): MedicalDataSource = MedicalDataSource(
            ownSource,
            contextPackage,
            android.net.Uri.parse("https://maproj.org/fhir/ma-health"),
            "MA Health",
            fhirVersion,
            Instant.parse("2026-01-01T00:00:00Z"),
        )

        override suspend fun insertRecords(records: List<HcRecord>): InsertRecordsResponse =
            throw UnsupportedOperationException("not used")

        override suspend fun updateRecords(records: List<HcRecord>) =
            throw UnsupportedOperationException("not used")

        override suspend fun deleteRecords(
            recordType: KClass<out HcRecord>,
            recordIdsList: List<String>,
            clientRecordIdsList: List<String>,
        ) = throw UnsupportedOperationException("not used")

        override suspend fun deleteRecords(
            recordType: KClass<out HcRecord>,
            timeRangeFilter: TimeRangeFilter,
        ) = throw UnsupportedOperationException("not used")

        override suspend fun <T : HcRecord> readRecord(
            recordType: KClass<T>,
            recordId: String,
        ): ReadRecordResponse<T> = throw UnsupportedOperationException("not used")

        override suspend fun <T : HcRecord> readRecords(
            request: ReadRecordsRequest<T>,
        ): ReadRecordsResponse<T> = throw UnsupportedOperationException("not used")

        override suspend fun aggregate(request: AggregateRequest): AggregationResult =
            throw UnsupportedOperationException("not used")

        override suspend fun aggregateGroupByDuration(
            request: AggregateGroupByDurationRequest,
        ): List<AggregationResultGroupedByDuration> = throw UnsupportedOperationException("not used")

        override suspend fun aggregateGroupByPeriod(
            request: AggregateGroupByPeriodRequest,
        ): List<AggregationResultGroupedByPeriod> = throw UnsupportedOperationException("not used")

        override suspend fun getChangesToken(request: ChangesTokenRequest): String =
            throw UnsupportedOperationException("not used")

        override suspend fun getChanges(changesToken: String): ChangesResponse =
            throw UnsupportedOperationException("not used")

        override suspend fun getChanges(changesToken: String, maxChanges: Int): ChangesResponse =
            throw UnsupportedOperationException("not used")

        companion object {
            var contextPackage: String = "com.vayunmathur.health"
        }
    }

    private lateinit var client: FakePhrClient

    @kotlin.test.AfterTest
    fun tearDown() {
        // PersonalHealthRecords is a process singleton: leaving the fake
        // client installed would make "Health Connect unavailable" tests
        // in other classes see a working provider.
        val clientField = PersonalHealthRecords::class.java.getDeclaredField("client")
        clientField.isAccessible = true
        clientField.set(PersonalHealthRecords, null)
        val contextField = PersonalHealthRecords::class.java.getDeclaredField("appContext")
        contextField.isAccessible = true
        contextField.set(PersonalHealthRecords, null)
    }

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
        FakePhrClient.contextPackage = context.packageName
        client = FakePhrClient(ownSource)
        PersonalHealthRecords.init(context, client)
    }

    private fun repository(): HealthRepository = HealthRepository.get(context)
    private fun viewModel(): MedicalViewModel = MedicalViewModel(context, repository())

    private suspend fun settle(cond: suspend () -> Boolean) {
        repeat(1000) { i ->


            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            // Room hops run on real executor threads; give them real time
            // (runTest's delay is virtual and would spin past them).
            Thread.sleep(5)
            delay(5)
        }
        // Final check outside the loop so the failure message is the
        // assertion's, not a silent fall-through.
        assertTrue(cond(), "import did not settle")
    }

    private fun vaccine(id: String, name: String = "C5 Flu Shot") = VaccinationEntry(
        id = id, displayName = name, occurredAt = at,
    )

    private fun import() {
        val vm = viewModel()
        vm.importFromHealthConnect()
    }

    @Test
    fun `provider vaccine medication allergy condition and lab are imported`() = runTest {
        client.add(
            PersonalHealthRecords.vaccinesType, PersonalHealthRecords.immunizationResourceType,
            hospital, "vac-1", FhirRecords.immunizationJson(vaccine("vac-1")),
        )
        client.add(
            PersonalHealthRecords.medicationsType, PersonalHealthRecords.medicationRequestResourceType,
            hospital, "med-1",
            FhirRecords.medicationRequestJson(
                MedicationEntry(id = "med-1", displayName = "C5 Amoxicillin", startedAt = at),
            ),
        )
        client.add(
            PersonalHealthRecords.allergiesType, PersonalHealthRecords.allergyResourceType,
            hospital, "alg-1",
            FhirRecords.allergyIntoleranceJson(
                AllergyEntry(id = "alg-1", displayName = "C5 Peanuts", recordedAt = at),
            ),
        )
        client.add(
            PersonalHealthRecords.conditionsType, PersonalHealthRecords.conditionResourceType,
            hospital, "con-1",
            FhirRecords.conditionJson(
                ConditionEntry(id = "con-1", displayName = "C5 Asthma", onsetAt = at),
            ),
        )
        client.add(
            PersonalHealthRecords.labsType, PersonalHealthRecords.observationResourceType,
            hospital, "lab-1",
            FhirRecords.labResultJson(
                LabResultEntry(id = "lab-1", displayName = "C5 Glucose", value = 96.0, unit = "mg/dL", takenAt = at),
            ),
        )
        val repo = repository()
        import()
        settle {
            repo.getVaccinationsFlow().first().any { it.displayName == "C5 Flu Shot" } &&
                repo.getMedicationsFlow().first().any { it.displayName.contains("Amoxicillin") } &&
                repo.getAllergiesFlow().first().any { it.displayName == "C5 Peanuts" } &&
                repo.getConditionsFlow().first().any { it.displayName == "C5 Asthma" } &&
                repo.getLabResultsFlow().first().any { it.displayName == "C5 Glucose" }
        }
        val vaccines = repo.getVaccinationsFlow().first().filter { it.displayName == "C5 Flu Shot" }
        assertEquals(1, vaccines.size)
        // Provider rows never keep the bare FHIR id (ids collide across sources).
        assertNotEquals("vac-1", vaccines[0].id)
        assertEquals(hospital, vaccines[0].dataSourceId)
        assertTrue(repo.getMedicationsFlow().first().any { it.displayName.contains("Amoxicillin") })
        assertTrue(repo.getAllergiesFlow().first().any { it.displayName == "C5 Peanuts" })
        assertTrue(repo.getConditionsFlow().first().any { it.displayName == "C5 Asthma" })
        val labs = repo.getLabResultsFlow().first().filter { it.displayName == "C5 Glucose" }
        assertEquals(1, labs.size)
        assertEquals(96.0, labs[0].value)

        // A second import updates the provider row in place, not a duplicate.
        import()
        settle { !viewModel().syncing.value }
        assertEquals(1, repo.getVaccinationsFlow().first().count { it.displayName == "C5 Flu Shot" })
    }

    @Test
    fun `entered in error retracts and tombstone blocks resurrection`() = runTest {
        val repo = repository()
        client.add(
            PersonalHealthRecords.allergiesType, PersonalHealthRecords.allergyResourceType,
            hospital, "alg-err",
            FhirRecords.allergyIntoleranceJson(
                AllergyEntry(id = "alg-err", displayName = "C5 Dust", recordedAt = at),
            ),
        )
        import()
        settle { repo.getAllergiesFlow().first().any { it.displayName == "C5 Dust" } }
        // The author retracts it: same id, entered-in-error status.
        client.byType[PersonalHealthRecords.allergiesType]!!.clear()
        client.add(
            PersonalHealthRecords.allergiesType, PersonalHealthRecords.allergyResourceType,
            hospital, "alg-err",
            FhirRecords.allergyIntoleranceJson(
                AllergyEntry(id = "alg-err", displayName = "C5 Dust", recordedAt = at),
            ).replace(
                "\"id\":\"alg-err\"",
                "\"id\":\"alg-err\",\"verificationStatus\":{\"coding\":[{\"code\":\"entered-in-error\"}]}",
            ),
        )
        import()
        settle { repo.getAllergiesFlow().first().none { it.displayName == "C5 Dust" } }

        // A locally deleted provider record stays deleted on re-import.
        client.add(
            PersonalHealthRecords.allergiesType, PersonalHealthRecords.allergyResourceType,
            hospital, "alg-gone",
            FhirRecords.allergyIntoleranceJson(
                AllergyEntry(id = "alg-gone", displayName = "C5 Mold", recordedAt = at),
            ),
        )
        repo.upsertTombstone(
            DeletedMedicalResource(
                MedicalKind.Allergy, hospital, "alg-gone",
                PersonalHealthRecords.allergyResourceType, retryDelete = false,
            ),
        )
        import()
        settle { !viewModel().syncing.value }
        assertTrue(repo.getAllergiesFlow().first().none { it.displayName == "C5 Mold" })
    }

    @Test
    fun `profile observations import newest answers only`() = runTest {
        client.add(
            PersonalHealthRecords.pregnancyType, PersonalHealthRecords.observationResourceType,
            hospital, "preg-1",
            FhirRecords.pregnancyStatusJson(
                "preg-1", com.vayunmathur.health.data.PregnancyStatus.Pregnant, at, null,
            ),
        )
        client.add(
            PersonalHealthRecords.socialHistoryType, PersonalHealthRecords.observationResourceType,
            hospital, "smoke-1",
            FhirRecords.smokingStatusJson("smoke-1", SmokingStatus.Former, at),
        )
        val question = SocialHistoryQuestions.ALL.first()
        val answer = question.answers.first()
        val future = Instant.parse("2030-03-04T09:30:00Z")
        client.add(
            PersonalHealthRecords.socialHistoryType, PersonalHealthRecords.observationResourceType,
            hospital, "soc-1",
            FhirRecords.socialHistoryAnswerJson("soc-1", question, answer, future),
        )
        // An answer code the question never offers must be refused.
        client.add(
            PersonalHealthRecords.socialHistoryType, PersonalHealthRecords.observationResourceType,
            hospital, "soc-bad",
            FhirRecords.socialHistoryAnswerJson("soc-bad", question, answer, future)
                .replace(answer.code, "bogus-code-zz"),
        )
        val repo = repository()
        import()
        settle { repo.getProfile()?.smokingStatus == SmokingStatus.Former }
        assertEquals(
            com.vayunmathur.health.data.PregnancyStatus.Pregnant,
            repo.getProfile()?.pregnancyStatus,
        )
        val stored = repo.getProfileAnswer(question.loinc)
        assertNotNull(stored)
        assertEquals(answer.code, stored.answerCode)
    }

    @Test
    fun `pending mirrors and remote deletes are pushed before import`() = runTest {
        val repo = repository()
        // A local vaccination queued for mirroring.
        repo.upsertVaccination(vaccine("vac-pending", "C5 Pending Shot"))
        repo.markPending(MedicalKind.Vaccination, "vac-pending")
        // A remote delete that never landed.
        repo.upsertTombstone(
            DeletedMedicalResource(
                MedicalKind.Allergy, ownSource, "alg-gone",
                PersonalHealthRecords.allergyResourceType, retryDelete = true,
            ),
        )
        import()
        settle { client.upserts.isNotEmpty() }
        assertTrue(client.upserts.isNotEmpty(), "pending mirror is written out")
        assertTrue(
            client.deletes.any { it.fhirResourceId == "alg-gone" },
            "remote delete retried, deletes=${client.deletes}",
        )
        // The mirror writes back our own data source id to the row.
        settle { repo.getVaccinationsFlow().first().firstOrNull { it.id == "vac-pending" }?.dataSourceId == ownSource }
    }

    @Test
    fun `own resources already stored are not duplicated and malformed are skipped`() = runTest {
        val repo = repository()
        repo.upsertVaccination(vaccine("vac-own", "C5 Own Shot").copy(dataSourceId = ownSource))
        client.add(
            PersonalHealthRecords.vaccinesType, PersonalHealthRecords.immunizationResourceType,
            ownSource, "vac-own", FhirRecords.immunizationJson(vaccine("vac-own", "C5 Own Shot")),
        )
        client.add(
            PersonalHealthRecords.vaccinesType, PersonalHealthRecords.immunizationResourceType,
            hospital, "vac-bad", "{ not json",
        )
        client.add(
            PersonalHealthRecords.vaccinesType, PersonalHealthRecords.immunizationResourceType,
            hospital, "patient-1", FhirRecords.patientJson(),
        )
        import()
        settle { !viewModel().syncing.value }
        assertEquals(1, repo.getVaccinationsFlow().first().count { it.displayName == "C5 Own Shot" })
    }

    @Test
    fun `dose events resolve their medication in the same source`() = runTest {
        val repo = repository()
        client.add(
            PersonalHealthRecords.medicationsType, PersonalHealthRecords.medicationRequestResourceType,
            hospital, "med-dose",
            FhirRecords.medicationRequestJson(
                MedicationEntry(id = "med-dose", displayName = "C5 Dose Med", startedAt = at),
            ),
        )
        // A dose (MedicationStatement) against that request, same source.
        val medRow = MedicationEntry(id = "med-dose", displayName = "C5 Dose Med", startedAt = at)
        client.add(
            PersonalHealthRecords.medicationsType, PersonalHealthRecords.medicationStatementResourceType,
            hospital, "dose-1",
            FhirRecords.doseEventJson(DoseEvent(id = "dose-1", medicationId = "med-dose", takenAt = at), medRow),
        )
        import()
        settle {
            repo.getDoseEventsFlow().first().any { dose ->
                repo.getMedicationsFlow().first().any { it.id == dose.medicationId && it.fhirResourceId == "med-dose" }
            }
        }
        val medication = repo.getMedicationsFlow().first().first { it.fhirResourceId == "med-dose" }
        val dose = repo.getDoseEventsFlow().first().first { it.medicationId == medication.id }
        assertEquals(medication.id, dose.medicationId)
    }

    @Test
    fun `read failure is swallowed`() = runTest {
        client.failReads = true
        val vm = viewModel()
        vm.importFromHealthConnect()
        settle { !vm.syncing.value }
        // PersonalHealthRecords degrades each verb on failure.
        assertTrue(PersonalHealthRecords.readAll(PersonalHealthRecords.vaccinesType).isEmpty())
        // Deletes and writes do not read, so they still land on the fake.
        assertEquals(true, PersonalHealthRecords.delete(ownSource, PersonalHealthRecords.allergyResourceType, "x"))
        assertTrue(client.deletes.any { it.fhirResourceId == "x" })
        assertNotNull(PersonalHealthRecords.dataSourceId())
        assertTrue(PersonalHealthRecords.isOwnDataSource(ownSource))
    }

    @Test
    fun `legacy own medication statement is migrated to a request`() = runTest {
        val repo = repository()
        val statement = FhirRecords.doseEventJson(
            DoseEvent(id = "legacy-1", medicationId = "legacy-med", takenAt = at),
            MedicationEntry(id = "legacy-med", displayName = "C5 Legacy Med", startedAt = at),
        ).replace("\"basedOn\":[{\"reference\":\"MedicationRequest/legacy-med\"}],", "")
        client.add(
            PersonalHealthRecords.medicationsType, PersonalHealthRecords.medicationStatementResourceType,
            ownSource, "legacy-1", statement,
        )
        import()
        // Migration writes the request out (an upsert containing a
        // MedicationRequest) and deletes the old statement.
        settle {
            client.upserts.any { it.data.contains("\"MedicationRequest\"") } &&
                client.deletes.any { it.fhirResourceId == "legacy-1" }
        }
        val migrated = repo.getMedicationsFlow().first().firstOrNull { it.displayName.contains("Legacy Med") }
        assertNotNull(migrated)
    }

    @Test
    fun `pending mirrors of every kind are pushed`() = runTest {
        val repo = repository()
        repo.upsertAllergy(AllergyEntry(id = "alg-pend", displayName = "C5 Pend Allergy", recordedAt = at))
        repo.markPending(MedicalKind.Allergy, "alg-pend")
        repo.upsertCondition(ConditionEntry(id = "con-pend", displayName = "C5 Pend Condition", onsetAt = at))
        repo.markPending(MedicalKind.Condition, "con-pend")
        repo.upsertLabResult(LabResultEntry(id = "lab-pend", displayName = "C5 Pend Lab", value = 5.0, takenAt = at))
        repo.markPending(MedicalKind.LabResult, "lab-pend")
        repo.upsertMedication(MedicationEntry(id = "med-pend", displayName = "C5 Pend Med", startedAt = at))
        repo.markPending(MedicalKind.Medication, "med-pend")
        import()
        settle {
            val kinds = client.upserts.map { it.data }
            kinds.any { it.contains("AllergyIntolerance") } &&
                kinds.any { it.contains("\"Condition\"") } &&
                kinds.any { it.contains("\"Observation\"") } &&
                kinds.any { it.contains("MedicationRequest") }
        }
        // Successful mirrors clear their queue entries and stamp the source.
        settle { repo.getPendingMirrors().none { it.rowId in setOf("alg-pend", "con-pend", "lab-pend", "med-pend") } }
        assertEquals(
            ownSource,
            repo.getAllergiesFlow().first().first { it.id == "alg-pend" }.dataSourceId,
        )
    }

    @Test
    fun `profile writes mirror to health connect`() = runTest {
        val repo = repository()
        val vm = viewModel()
        vm.setSmokingStatus(SmokingStatus.Current)
        settle { client.upserts.any { it.data.contains("72166-2") } }
        assertEquals(SmokingStatus.Current, repo.getProfile()?.smokingStatus)
        assertEquals(ownSource, repo.getProfile()?.dataSourceId)

        vm.setPregnancyStatus(
            com.vayunmathur.health.data.PregnancyStatus.NotPregnant, null,
        )
        settle { client.upserts.count { it.data.contains("82810-3") } >= 1 }
        assertEquals(
            com.vayunmathur.health.data.PregnancyStatus.NotPregnant,
            repo.getProfile()?.pregnancyStatus,
        )

        val question = SocialHistoryQuestions.ALL.first()
        val answer = question.answers.first()
        vm.setSocialHistoryAnswer(question, answer)
        settle {
            repo.getProfileAnswer(question.loinc)?.dataSourceId == ownSource
        }
        assertEquals(answer.code, repo.getProfileAnswer(question.loinc)?.answerCode)
        assertTrue(client.upserts.any { it.data.contains(question.loinc) })

        vm.clearSocialHistoryAnswer(question)
        settle {
            repo.getProfileAnswer(question.loinc) == null && client.deletes.isNotEmpty()
        }
    }

    @Test
    fun `draft saves mirror immediately and editing a provider row detaches it`() = runTest {
        val repo = repository()
        val vm = viewModel()
        // New vaccination: saved locally and written out in the same pass.
        vm.startVaccinationDraft()
        vm.editVaccinationDraft { it.copy(displayName = "C5 Saved Shot", lotNumber = "LOT-C5") }
        vm.saveVaccinationDraft("")
        settle {
            repo.getVaccinationsFlow().first()
                .any { it.displayName == "C5 Saved Shot" && it.dataSourceId == ownSource }
        }
        assertTrue(client.upserts.any { it.data.contains("Immunization") })

        // Editing a provider's vaccination detaches it: the provider
        // original is tombstoned and the row becomes ours.
        repo.upsertVaccination(
            vaccine("vac-foreign", "C5 Foreign Shot").copy(
                fhirResourceId = "vac-foreign", dataSourceId = hospital,
            ),
        )
        vm.startVaccinationDraft("vac-foreign")
        // The draft loads asynchronously; edit only once it has landed.
        settle { vm.vaccinationDraft.value.editingId == "vac-foreign" }
        vm.editVaccinationDraft { it.copy(displayName = "C5 Foreign Shot Edited") }
        vm.saveVaccinationDraft("")
        settle {
            repo.getVaccinationsFlow().first()
                .any { it.id == "vac-foreign" && it.displayName == "C5 Foreign Shot Edited" && it.dataSourceId == ownSource }
        }
        assertTrue(
            repo.isTombstoned(MedicalKind.Vaccination, hospital, "vac-foreign"),
            "provider original tombstoned",
        )

        // Medication, allergy, condition, and lab drafts follow the same path.
        vm.startMedicationDraft()
        vm.editMedicationDraft { it.copy(ingredient = "C5 Saved Med", strength = "10 MG") }
        vm.saveMedicationDraft()
        settle {
            repo.getMedicationsFlow().first()
                .any { it.displayName.contains("C5 Saved Med") && it.dataSourceId == ownSource }
        }
        vm.startAllergyDraft()
        vm.editAllergyDraft { it.copy(displayName = "C5 Saved Allergy") }
        vm.saveAllergyDraft()
        settle {
            repo.getAllergiesFlow().first()
                .any { it.displayName == "C5 Saved Allergy" && it.dataSourceId == ownSource }
        }
        vm.startConditionDraft()
        vm.editConditionDraft { it.copy(displayName = "C5 Saved Condition") }
        vm.saveConditionDraft()
        settle {
            repo.getConditionsFlow().first()
                .any { it.displayName == "C5 Saved Condition" && it.dataSourceId == ownSource }
        }
        vm.startLabDraft()
        vm.editLabDraft { it.copy(displayName = "C5 Saved Lab", value = "42") }
        vm.saveLabDraft()
        settle {
            repo.getLabResultsFlow().first()
                .any { it.displayName == "C5 Saved Lab" && it.dataSourceId == ownSource }
        }
    }

    @Test
    fun `syncing flag clears after import`() = runTest {
        val vm = viewModel()
        vm.importFromHealthConnect()
        settle { !vm.syncing.value }
        assertEquals(false, vm.syncing.value)
    }
}
