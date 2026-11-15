package com.vayunmathur.health.domain

import com.vayunmathur.health.data.AllergyCategory
import com.vayunmathur.health.data.AllergyCriticality
import com.vayunmathur.health.data.AllergyEntry
import com.vayunmathur.health.data.ConditionEntry
import com.vayunmathur.health.data.ConditionStatus
import com.vayunmathur.health.data.DoseEvent
import com.vayunmathur.health.data.LabResultEntry
import com.vayunmathur.health.data.MedicationSchedule
import com.vayunmathur.health.data.PregnancyStatus
import com.vayunmathur.health.data.RepeatUnit
import com.vayunmathur.health.data.SmokingStatus
import kotlinx.datetime.LocalDate as KotlinLocalDate
import com.vayunmathur.health.data.MedicationEntry
import com.vayunmathur.health.data.MedicationStatus
import com.vayunmathur.health.data.VaccinationEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Health Connect validates FHIR resources on write, so the shape of what [FhirRecords] emits is not
 * cosmetic: a missing required field is a rejected record. These cover the required fields, the
 * round trip, and the reading of resources looser than anything this app writes.
 */
class FhirRecordsTest {

    private val json = Json

    // Date-precision fields are written and read in the device's zone, so pin it: the fixtures
    // below use UTC midnight to stand for a picked date.
    private lateinit var originalZone: TimeZone

    @BeforeTest
    fun pinZone() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @AfterTest
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    private fun parse(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

    private val flu = VaccinationEntry(
        id = "vac-1",
        cvxCode = "158",
        displayName = "influenza, injectable, quadrivalent",
        occurredAt = Instant.parse("2026-03-04T09:30:00Z"),
        lotNumber = "AB1234",
        site = "Left arm",
        route = "Intramuscular",
        doseQuantity = "0.5 mL",
        performer = "Boots Pharmacy",
        note = "Annual",
    )

    private val amoxicillin = MedicationEntry(
        id = "med-1",
        rxcui = "308182",
        displayName = "amoxicillin",
        strength = "250 MG",
        doseForm = "Oral Capsule",
        status = MedicationStatus.Completed,
        startedAt = Instant.parse("2026-01-10T00:00:00Z"),
        endedAt = Instant.parse("2026-01-17T00:00:00Z"),
        dosageText = "One capsule three times a day",
    )

    // --- Immunization --------------------------------------------------------

    @Test
    fun `immunization carries every field FHIR requires`() {
        val root = parse(FhirRecords.immunizationJson(flu))

        assertEquals("Immunization", root.str("resourceType"))
        assertEquals("vac-1", root.str("id"))
        assertEquals("completed", root.str("status"))
        assertEquals("2026-03-04T09:30:00Z", root.str("occurrenceDateTime"))

        val patient = root["patient"]!!.jsonObject
        assertEquals("Patient/${FhirRecords.PATIENT_RESOURCE_ID}", patient.str("reference"))

        val coding = root["vaccineCode"]!!.jsonObject["coding"]!!.jsonArray.single().jsonObject
        assertEquals(FhirRecords.CVX_SYSTEM, coding.str("system"))
        assertEquals("158", coding.str("code"))
    }

    @Test
    fun `immunization omits the coding when there is no CVX code`() {
        val root = parse(FhirRecords.immunizationJson(flu.copy(cvxCode = null)))
        val vaccineCode = root["vaccineCode"]!!.jsonObject

        assertNull(vaccineCode["coding"])
        assertEquals(flu.displayName, vaccineCode.str("text"))
    }

    @Test
    fun `a dose that splits into a number and a unit becomes a doseQuantity`() {
        val dose = parse(FhirRecords.immunizationJson(flu))["doseQuantity"]!!.jsonObject

        assertEquals("0.5", dose.str("value"))
        assertEquals("mL", dose.str("unit"))
    }

    @Test
    fun `a dose that is not a quantity falls through to the note rather than being dropped`() {
        val root = parse(FhirRecords.immunizationJson(flu.copy(doseQuantity = "one shot")))

        assertNull(root["doseQuantity"])
        val notes = (root["note"] as JsonArray).map { it.jsonObject.str("text") }
        assertTrue("one shot" in notes)
    }

    @Test
    fun `attachments become a valueAttachment extension`() {
        val attachment = FhirRecords.FhirAttachment(
            contentType = "application/pdf",
            title = "card.pdf",
            url = "file:///data/user/0/com.vayunmathur.health/files/medical_attachments/att.pdf",
            creation = Instant.parse("2026-03-04T10:00:00Z"),
        )
        val root = parse(FhirRecords.immunizationJson(flu, listOf(attachment)))

        val extension = (root["extension"] as JsonArray).single().jsonObject
        assertEquals(FhirRecords.ATTACHMENT_EXTENSION_URL, extension.str("url"))

        val value = extension["valueAttachment"]!!.jsonObject
        assertEquals(attachment.contentType, value.str("contentType"))
        assertEquals(attachment.title, value.str("title"))
        assertEquals(attachment.url, value.str("url"))
        assertEquals("2026-03-04T10:00:00Z", value.str("creation"))
    }

    @Test
    fun `an immunization with no attachments carries no extension`() {
        assertNull(parse(FhirRecords.immunizationJson(flu))["extension"])
    }

    @Test
    fun `an immunization round trips`() {
        val parsed = FhirRecords.parseImmunization(FhirRecords.immunizationJson(flu), "src-1")

        assertNotNull(parsed)
        assertEquals(flu.cvxCode, parsed.cvxCode)
        assertEquals(flu.displayName, parsed.displayName)
        assertEquals(flu.occurredAt, parsed.occurredAt)
        assertEquals(flu.lotNumber, parsed.lotNumber)
        assertEquals(flu.site, parsed.site)
        assertEquals(flu.route, parsed.route)
        assertEquals(flu.doseQuantity, parsed.doseQuantity)
        assertEquals(flu.performer, parsed.performer)
        assertEquals("src-1", parsed.dataSourceId)
        assertEquals(flu.id, parsed.fhirResourceId)
    }

    // --- MedicationRequest: the plan ------------------------------------------

    @Test
    fun `a medication is a plan, not a report that it was taken`() {
        val root = parse(FhirRecords.medicationRequestJson(amoxicillin))

        // MedicationRequest, because this is the intention to take something. A statement would
        // assert the whole course had already been consumed.
        assertEquals("MedicationRequest", root.str("resourceType"))
        assertEquals("completed", root.str("status"))
        assertEquals("plan", root.str("intent"))
        assertEquals("true", root.str("reportedBoolean"))
        assertEquals(
            "Patient/${FhirRecords.PATIENT_RESOURCE_ID}",
            root["subject"]!!.jsonObject.str("reference"),
        )

        val coding = root["medicationCodeableConcept"]!!.jsonObject["coding"]!!
            .jsonArray.single().jsonObject
        assertEquals(FhirRecords.RXNORM_SYSTEM, coding.str("system"))
        assertEquals("308182", coding.str("code"))
        assertEquals("amoxicillin 250 MG Oral Capsule", coding.str("display"))
        // A picked date travels as a date, not as a UTC instant that can land on another day.
        assertEquals("2026-01-10", root.str("authoredOn"))
    }

    @Test
    fun `an ongoing medication is active`() {
        val ongoing = amoxicillin.copy(status = MedicationStatus.Active, endedAt = null)
        assertEquals("active", parse(FhirRecords.medicationRequestJson(ongoing)).str("status"))
    }

    @Test
    fun `a medication round trips`() {
        val parsed = FhirRecords.parseMedicationRequest(
            FhirRecords.medicationRequestJson(amoxicillin),
            "src-1",
        )?.first

        assertNotNull(parsed)
        assertEquals(amoxicillin.rxcui, parsed.rxcui)
        assertEquals(amoxicillin.status, parsed.status)
        assertEquals(amoxicillin.startedAt, parsed.startedAt)
        assertEquals(amoxicillin.endedAt, parsed.endedAt)
        assertEquals(amoxicillin.dosageText, parsed.dosageText)
    }

    @Test
    fun `a finished course keeps its end date even without reminders`() {
        val root = parse(FhirRecords.medicationRequestJson(amoxicillin))
        val bounds = root["dosageInstruction"]!!.jsonArray.single().jsonObject["timing"]!!
            .jsonObject["repeat"]!!.jsonObject["boundsPeriod"]!!.jsonObject

        assertEquals("2026-01-10", bounds.str("start"))
        assertEquals("2026-01-17", bounds.str("end"))
    }

    @Test
    fun `an ongoing course does not carry a stale end date`() {
        val ongoing = amoxicillin.copy(status = MedicationStatus.Active)
        val root = parse(FhirRecords.medicationRequestJson(ongoing))
        val bounds = root["dosageInstruction"]!!.jsonArray.single().jsonObject["timing"]!!
            .jsonObject["repeat"]!!.jsonObject["boundsPeriod"]!!.jsonObject

        assertNull(bounds.str("end"))
    }

    @Test
    fun `the FHIR id is kept when the row already has one`() {
        val imported = amoxicillin.copy(id = "local-row", fhirResourceId = "fhir-7")
        assertEquals("fhir-7", parse(FhirRecords.medicationRequestJson(imported)).str("id"))
    }

    @Test
    fun `the dose schedule travels as timing repeat`() {
        val schedule = MedicationSchedule(
            id = "sched-1",
            medicationId = amoxicillin.id,
            times = listOf(8 * 3600, 20 * 3600),
            repeatUnit = RepeatUnit.Weekly,
            interval = 2,
            // Monday and Thursday. Bit 0 is Sunday.
            daysOfWeek = (1 shl 1) or (1 shl 4),
            anchorDate = KotlinLocalDate(2026, 1, 10),
            endDate = KotlinLocalDate(2026, 3, 1),
        )
        val ongoing = amoxicillin.copy(status = MedicationStatus.Active, endedAt = null)
        val dosage = parse(FhirRecords.medicationRequestJson(ongoing, schedule))[
            "dosageInstruction"
        ]!!.jsonArray.single().jsonObject
        val repeat = dosage["timing"]!!.jsonObject["repeat"]!!.jsonObject

        assertEquals(
            listOf("08:00:00", "20:00:00"),
            (repeat["timeOfDay"] as JsonArray).map { it.jsonPrimitive.content },
        )
        assertEquals("2", repeat.str("period"))
        assertEquals("wk", repeat.str("periodUnit"))
        // Doses per period: two times of day on each of two days.
        assertEquals("4", repeat.str("frequency"))
        assertEquals(
            listOf("mon", "thu"),
            (repeat["dayOfWeek"] as JsonArray).map { it.jsonPrimitive.content },
        )
        assertEquals("2026-03-01", repeat["boundsPeriod"]!!.jsonObject.str("end"))
    }

    @Test
    fun `a schedule round trips, but arrives switched off`() {
        val schedule = MedicationSchedule(
            id = amoxicillin.id,
            medicationId = amoxicillin.id,
            enabled = true,
            times = listOf(9 * 3600),
            repeatUnit = RepeatUnit.Daily,
            interval = 3,
            anchorDate = KotlinLocalDate(2026, 1, 10),
        )
        val parsed = FhirRecords.parseMedicationRequest(
            FhirRecords.medicationRequestJson(amoxicillin, schedule),
            "src-1",
        )?.second

        assertNotNull(parsed)
        assertEquals(listOf(9 * 3600), parsed.times)
        assertEquals(RepeatUnit.Daily, parsed.repeatUnit)
        assertEquals(3, parsed.interval)
        // Someone else's record must not start this phone ringing.
        assertEquals(false, parsed.enabled)
    }

    @Test
    fun `a medication with no schedule yields no schedule`() {
        val parsed = FhirRecords.parseMedicationRequest(
            FhirRecords.medicationRequestJson(amoxicillin),
            "src-1",
        )
        assertNotNull(parsed)
        assertNull(parsed.second)
    }

    private fun requestWithRepeat(repeat: String) = """
        {
          "resourceType": "MedicationRequest",
          "id": "rx-1",
          "status": "active",
          "intent": "order",
          "medicationCodeableConcept": { "text": "Ibuprofen" },
          "subject": { "reference": "Patient/other" },
          "dosageInstruction": [ { "timing": { "repeat": $repeat } } ]
        }
    """.trimIndent()

    @Test
    fun `an hourly regimen is not read as a daily one`() {
        val parsed = FhirRecords.parseMedicationRequest(
            requestWithRepeat(
                """{ "boundsPeriod": { "start": "2026-02-01" }, "timeOfDay": ["08:00:00"],
                    "period": 8, "periodUnit": "h" }"""
            ),
            "hospital",
        )

        assertNotNull(parsed)
        assertNull(parsed.second)
    }

    @Test
    fun `daily on chosen weekdays becomes weekly on those days`() {
        val schedule = FhirRecords.parseMedicationRequest(
            requestWithRepeat(
                """{ "boundsPeriod": { "start": "2026-02-01" }, "timeOfDay": ["08:00:00"],
                    "period": 1, "periodUnit": "d", "dayOfWeek": ["mon", "wed", "fri"] }"""
            ),
            "hospital",
        )?.second

        assertNotNull(schedule)
        assertEquals(RepeatUnit.Weekly, schedule.repeatUnit)
        assertEquals(1, schedule.interval)
        assertEquals((1 shl 1) or (1 shl 3) or (1 shl 5), schedule.daysOfWeek)
    }

    @Test
    fun `a course start comes from the dosing period rather than when it was written`() {
        val provider = """
            {
              "resourceType": "MedicationRequest",
              "id": "rx-2",
              "status": "active",
              "intent": "order",
              "medicationCodeableConcept": { "text": "Metformin" },
              "subject": { "reference": "Patient/other" },
              "authoredOn": "2026-01-20",
              "dosageInstruction": [ { "timing": { "repeat": { "boundsPeriod": { "start": "2026-02-01" } } } } ]
            }
        """.trimIndent()

        val parsed = FhirRecords.parseMedicationRequest(provider, "hospital")?.first

        assertNotNull(parsed)
        assertEquals(Instant.parse("2026-02-01T00:00:00Z"), parsed.startedAt)
    }

    @Test
    fun `a retracted request is not a medication`() {
        val retracted = FhirRecords.medicationRequestJson(amoxicillin)
            .replace("\"status\":\"completed\"", "\"status\":\"entered-in-error\"")

        assertTrue(FhirRecords.isEnteredInError(retracted))
        assertNull(FhirRecords.parseMedicationRequest(retracted, "src"))
        assertFalse(FhirRecords.isEnteredInError(FhirRecords.medicationRequestJson(amoxicillin)))
    }

    // --- MedicationStatement: the dose actually taken --------------------------

    @Test
    fun `a taken dose is a completed statement pointing at its plan`() {
        val event = DoseEvent(
            id = "dose-1",
            medicationId = amoxicillin.id,
            takenAt = Instant.parse("2026-01-12T08:05:00Z"),
        )
        val root = parse(FhirRecords.doseEventJson(event, amoxicillin))

        assertEquals("MedicationStatement", root.str("resourceType"))
        assertEquals("completed", root.str("status"))
        // A moment, not a period - one dose rather than a course.
        assertEquals("2026-01-12T08:05:00Z", root.str("effectiveDateTime"))
        assertNull(root["effectivePeriod"])
        assertEquals(
            "MedicationRequest/${amoxicillin.id}",
            (root["basedOn"] as JsonArray).single().jsonObject.str("reference"),
        )
    }

    @Test
    fun `a dose points at the plan's FHIR id, not its local row id`() {
        val imported = amoxicillin.copy(id = "local-row", fhirResourceId = "fhir-7")
        val event = DoseEvent("dose-1", imported.id, Instant.parse("2026-01-12T08:05:00Z"))
        val root = parse(FhirRecords.doseEventJson(event, imported))

        assertEquals(
            "MedicationRequest/fhir-7",
            (root["basedOn"] as JsonArray).single().jsonObject.str("reference"),
        )
    }

    @Test
    fun `only a completed statement is a dose taken`() {
        val event = DoseEvent("dose-1", amoxicillin.id, Instant.parse("2026-01-12T08:05:00Z"))
        val taken = FhirRecords.doseEventJson(event, amoxicillin)

        listOf("not-taken", "intended", "active", "entered-in-error").forEach { status ->
            val other = taken.replace("\"status\":\"completed\"", "\"status\":\"$status\"")
            assertNull(FhirRecords.parseDoseEvent(other, "src"), status)
        }
    }

    @Test
    fun `an absolute basedOn reference still finds its plan`() {
        val provider = """
            {
              "resourceType": "MedicationStatement",
              "id": "dose-9",
              "status": "completed",
              "basedOn": [ { "reference": "https://fhir.example.org/MedicationRequest/rx-4/_history/2" } ],
              "medicationCodeableConcept": { "text": "Metformin" },
              "subject": { "reference": "Patient/other" },
              "effectiveDateTime": "2026-01-12T08:05:00Z"
            }
        """.trimIndent()

        assertEquals("rx-4", FhirRecords.parseDoseEvent(provider, "hospital")?.medicationId)
    }

    @Test
    fun `a dose round trips`() {
        val event = DoseEvent(
            id = "dose-1",
            medicationId = amoxicillin.id,
            takenAt = Instant.parse("2026-01-12T08:05:00Z"),
        )
        val parsed = FhirRecords.parseDoseEvent(
            FhirRecords.doseEventJson(event, amoxicillin),
            "src-1",
        )

        assertNotNull(parsed)
        assertEquals(event.medicationId, parsed.medicationId)
        assertEquals(event.takenAt, parsed.takenAt)
    }

    @Test
    fun `the plan and the dose never parse as each other`() {
        val event = DoseEvent("dose-1", amoxicillin.id, Instant.parse("2026-01-12T08:05:00Z"))
        val plan = FhirRecords.medicationRequestJson(amoxicillin)
        val dose = FhirRecords.doseEventJson(event, amoxicillin)

        assertNull(FhirRecords.parseDoseEvent(plan, "src"))
        assertNull(FhirRecords.parseMedicationRequest(dose, "src"))
    }

    @Test
    fun `a bare statement with no basedOn is not treated as a dose`() {
        // What an earlier build of this app wrote for a medication, and what a provider writes for
        // a reported medication. Neither is a dose event, and guessing would invent history.
        val bare = """
            {
              "resourceType": "MedicationStatement",
              "id": "legacy-1",
              "status": "active",
              "medicationCodeableConcept": { "text": "Metformin" },
              "subject": { "reference": "Patient/other" },
              "effectiveDateTime": "2024-06-01"
            }
        """.trimIndent()

        assertNull(FhirRecords.parseDoseEvent(bare, "hospital"))
    }

}
