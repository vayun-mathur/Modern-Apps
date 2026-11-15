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
class FhirRecordsParsingTest {

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
    fun `a provider's reported medication is read as a medication`() {
        val reported = """
            {
              "resourceType": "MedicationStatement",
              "id": "reported-1",
              "status": "completed",
              "medicationCodeableConcept": { "text": "Metformin" },
              "subject": { "reference": "Patient/other" },
              "effectivePeriod": { "start": "2024-06-01", "end": "2024-09-01" }
            }
        """.trimIndent()

        assertTrue(FhirRecords.isUnplannedMedicationStatement(reported))
        val parsed = FhirRecords.parseMedicationStatement(reported, "hospital")

        assertNotNull(parsed)
        assertEquals("Metformin", parsed.displayName)
        assertEquals(MedicationStatus.Completed, parsed.status)
        assertEquals(Instant.parse("2024-06-01T00:00:00Z"), parsed.startedAt)
        assertEquals(Instant.parse("2024-09-01T00:00:00Z"), parsed.endedAt)
    }

    @Test
    fun `a dose is never read as a reported medication`() {
        val event = DoseEvent("dose-1", amoxicillin.id, Instant.parse("2026-01-12T08:05:00Z"))
        val dose = FhirRecords.doseEventJson(event, amoxicillin)

        assertFalse(FhirRecords.isUnplannedMedicationStatement(dose))
        assertNull(FhirRecords.parseMedicationStatement(dose, "src"))
    }

    // --- Reading what other systems write ------------------------------------

    @Test
    fun `a provider resource with a date-only occurrence and no CVX still parses`() {
        val provider = """
            {
              "resourceType": "Immunization",
              "id": "provider-1",
              "status": "completed",
              "vaccineCode": {
                "coding": [
                  { "system": "http://snomed.info/sct", "code": "1119349007", "display": "COVID-19 vaccine" }
                ]
              },
              "patient": { "reference": "Patient/other" },
              "occurrenceDateTime": "2021-04-19"
            }
        """.trimIndent()

        val parsed = FhirRecords.parseImmunization(provider, "hospital")

        assertNotNull(parsed)
        assertNull(parsed.cvxCode)
        assertEquals("COVID-19 vaccine", parsed.displayName)
        assertEquals(Instant.parse("2021-04-19T00:00:00Z"), parsed.occurredAt)
    }

    @Test
    fun `unknown statuses are treated as still being taken`() {
        val provider = """
            {
              "resourceType": "MedicationRequest",
              "id": "provider-2",
              "status": "unknown",
              "intent": "order",
              "medicationCodeableConcept": { "text": "Metformin" },
              "subject": { "reference": "Patient/other" },
              "authoredOn": "2024-06"
            }
        """.trimIndent()

        val parsed = FhirRecords.parseMedicationRequest(provider, "hospital")?.first

        assertNotNull(parsed)
        assertEquals(MedicationStatus.Active, parsed.status)
        assertEquals("Metformin", parsed.displayName)
        assertEquals(Instant.parse("2024-06-01T00:00:00Z"), parsed.startedAt)
    }

    @Test
    fun `the wrong resource type and malformed json are refused, not thrown on`() {
        assertNull(FhirRecords.parseImmunization("{ not json", "src"))
        assertNull(FhirRecords.parseImmunization(FhirRecords.patientJson(), "src"))
        assertNull(FhirRecords.parseMedicationRequest(FhirRecords.immunizationJson(flu), "src"))
    }

    @Test
    fun `the patient resource is minimal and well formed`() {
        val root = parse(FhirRecords.patientJson())

        assertEquals("Patient", root.str("resourceType"))
        assertEquals(FhirRecords.PATIENT_RESOURCE_ID, root.str("id"))
    }

    // --- AllergyIntolerance --------------------------------------------------

    private val penicillin = AllergyEntry(
        id = "alg-1",
        rxcui = "7980",
        displayName = "penicillin G",
        category = AllergyCategory.Medication,
        criticality = AllergyCriticality.High,
        reaction = "anaphylaxis",
        onsetAt = Instant.parse("2019-06-01T00:00:00Z"),
        recordedAt = Instant.parse("2026-03-04T09:30:00Z"),
    )

    @Test
    fun `allergy carries the fields FHIR requires`() {
        val root = parse(FhirRecords.allergyIntoleranceJson(penicillin))

        assertEquals("AllergyIntolerance", root.str("resourceType"))
        assertEquals(
            "Patient/${FhirRecords.PATIENT_RESOURCE_ID}",
            root["patient"]!!.jsonObject.str("reference"),
        )
        assertEquals(
            "active",
            root["clinicalStatus"]!!.jsonObject["coding"]!!.jsonArray.single().jsonObject.str("code"),
        )
        assertEquals("high", root.str("criticality"))
        assertEquals("medication", (root["category"] as JsonArray).single().jsonPrimitive.content)

        val coding = root["code"]!!.jsonObject["coding"]!!.jsonArray.single().jsonObject
        assertEquals(FhirRecords.RXNORM_SYSTEM, coding.str("system"))
        assertEquals("7980", coding.str("code"))
    }

    @Test
    fun `a food allergen has no coding but still has text`() {
        val peanut = penicillin.copy(
            rxcui = null,
            displayName = "peanut",
            category = AllergyCategory.Food,
        )
        val code = parse(FhirRecords.allergyIntoleranceJson(peanut))["code"]!!.jsonObject

        assertNull(code["coding"])
        assertEquals("peanut", code.str("text"))
    }

    @Test
    fun `an unknown criticality is omitted rather than guessed`() {
        val unknown = penicillin.copy(criticality = AllergyCriticality.Unknown)
        assertNull(parse(FhirRecords.allergyIntoleranceJson(unknown))["criticality"])
    }

    @Test
    fun `an allergy round trips`() {
        val parsed = FhirRecords.parseAllergyIntolerance(
            FhirRecords.allergyIntoleranceJson(penicillin),
            "src-1",
        )

        assertNotNull(parsed)
        assertEquals(penicillin.rxcui, parsed.rxcui)
        assertEquals(penicillin.displayName, parsed.displayName)
        assertEquals(penicillin.category, parsed.category)
        assertEquals(penicillin.criticality, parsed.criticality)
        assertEquals(penicillin.reaction, parsed.reaction)
        assertEquals(penicillin.onsetAt, parsed.onsetAt)
        assertEquals(penicillin.recordedAt, parsed.recordedAt)
    }

    // --- Condition -----------------------------------------------------------

    private val diabetes = ConditionEntry(
        id = "cond-1",
        icd10Code = "E11.9",
        displayName = "Type 2 diabetes mellitus without complications",
        status = ConditionStatus.Active,
        onsetAt = Instant.parse("2022-01-15T00:00:00Z"),
    )

    @Test
    fun `condition carries the fields US Core requires`() {
        val root = parse(FhirRecords.conditionJson(diabetes))

        assertEquals("Condition", root.str("resourceType"))
        assertEquals(
            "Patient/${FhirRecords.PATIENT_RESOURCE_ID}",
            root["subject"]!!.jsonObject.str("reference"),
        )
        assertEquals(
            "active",
            root["clinicalStatus"]!!.jsonObject["coding"]!!.jsonArray.single().jsonObject.str("code"),
        )
        // US Core requires a category, and a user-maintained list is a problem list.
        val category = (root["category"] as JsonArray).single().jsonObject
        assertEquals(
            "problem-list-item",
            category["coding"]!!.jsonArray.single().jsonObject.str("code"),
        )
        assertEquals("2022-01-15", root.str("onsetDateTime"))

        val coding = root["code"]!!.jsonObject["coding"]!!.jsonArray.single().jsonObject
        assertEquals(FhirRecords.ICD10_SYSTEM, coding.str("system"))
        assertEquals("E11.9", coding.str("code"))
    }

    @Test
    fun `a resolved condition carries an abatement date`() {
        val resolved = diabetes.copy(
            status = ConditionStatus.Resolved,
            resolvedAt = Instant.parse("2024-08-01T00:00:00Z"),
        )
        val root = parse(FhirRecords.conditionJson(resolved))

        assertEquals(
            "resolved",
            root["clinicalStatus"]!!.jsonObject["coding"]!!.jsonArray.single().jsonObject.str("code"),
        )
        assertEquals("2024-08-01", root.str("abatementDateTime"))
    }

    @Test
    fun `a condition round trips`() {
        val parsed = FhirRecords.parseCondition(FhirRecords.conditionJson(diabetes), "src-1")

        assertNotNull(parsed)
        assertEquals(diabetes.icd10Code, parsed.icd10Code)
        assertEquals(diabetes.displayName, parsed.displayName)
        assertEquals(diabetes.status, parsed.status)
        assertEquals(diabetes.onsetAt, parsed.onsetAt)
    }

    @Test
    fun `a provider condition dated only by period still parses`() {
        val provider = """
            {
              "resourceType": "Condition",
              "id": "provider-3",
              "clinicalStatus": { "coding": [ { "code": "inactive" } ] },
              "code": { "text": "Asthma" },
              "subject": { "reference": "Patient/other" },
              "onsetPeriod": { "start": "2015-04-01" }
            }
        """.trimIndent()

        val parsed = FhirRecords.parseCondition(provider, "hospital")

        assertNotNull(parsed)
        assertEquals("Asthma", parsed.displayName)
        assertEquals(ConditionStatus.Resolved, parsed.status)
        assertEquals(Instant.parse("2015-04-01T00:00:00Z"), parsed.onsetAt)
    }

    @Test
    fun `the allergy and condition parsers refuse each other's resources`() {
        val allergy = FhirRecords.allergyIntoleranceJson(penicillin)
        val condition = FhirRecords.conditionJson(diabetes)

        assertNull(FhirRecords.parseCondition(allergy, "src"))
        assertNull(FhirRecords.parseAllergyIntolerance(condition, "src"))
    }

    // --- Observations --------------------------------------------------------

    private val hba1c = LabResultEntry(
        id = "lab-1",
        loincCode = "4548-4",
        displayName = "Hemoglobin A1c/Hemoglobin.total in Blood",
        value = 5.4,
        unit = "%",
        referenceLow = 4.0,
        referenceHigh = 5.6,
        takenAt = Instant.parse("2026-02-11T08:15:00Z"),
    )

    @Test
    fun `a numeric lab result carries a quantity and its reference range`() {
        val root = parse(FhirRecords.labResultJson(hba1c))

        assertEquals("Observation", root.str("resourceType"))
        assertEquals("final", root.str("status"))
        assertEquals(
            "laboratory",
            (root["category"] as JsonArray).single().jsonObject["coding"]!!
                .jsonArray.single().jsonObject.str("code"),
        )
        val coding = root["code"]!!.jsonObject["coding"]!!.jsonArray.single().jsonObject
        assertEquals(FhirRecords.LOINC_SYSTEM, coding.str("system"))
        assertEquals("4548-4", coding.str("code"))

        val quantity = root["valueQuantity"]!!.jsonObject
        assertEquals("5.4", quantity.str("value"))
        assertEquals("%", quantity.str("unit"))
        assertEquals(FhirRecords.UCUM_SYSTEM, quantity.str("system"))

        val range = (root["referenceRange"] as JsonArray).single().jsonObject
        assertEquals("4.0", range["low"]!!.jsonObject.str("value"))
        assertEquals("5.6", range["high"]!!.jsonObject.str("value"))
    }

    @Test
    fun `a qualitative result becomes a string, not a zero`() {
        val qualitative = hba1c.copy(
            value = null,
            valueText = "Positive",
            unit = null,
            referenceLow = null,
            referenceHigh = null,
        )
        val root = parse(FhirRecords.labResultJson(qualitative))

        assertNull(root["valueQuantity"])
        assertEquals("Positive", root.str("valueString"))
        assertNull(root["referenceRange"])
    }

    @Test
    fun `a lab result round trips`() {
        val parsed = FhirRecords.parseLabResult(FhirRecords.labResultJson(hba1c), "src-1")

        assertNotNull(parsed)
        assertEquals(hba1c.loincCode, parsed.loincCode)
        assertEquals(hba1c.value, parsed.value)
        assertEquals(hba1c.unit, parsed.unit)
        assertEquals(hba1c.referenceLow, parsed.referenceLow)
        assertEquals(hba1c.referenceHigh, parsed.referenceHigh)
        assertEquals(hba1c.takenAt, parsed.takenAt)
    }

    @Test
    fun `out of range is derived from the result's own range`() {
        assert(!hba1c.isOutOfRange)
        assert(hba1c.copy(value = 7.2).isOutOfRange)
        assert(hba1c.copy(value = 3.1).isOutOfRange)
        // No range means nothing to be outside of, rather than everything being abnormal.
        assert(!hba1c.copy(value = 99.0, referenceLow = null, referenceHigh = null).isOutOfRange)
    }

    @Test
    fun `pregnancy status is a coded answer with an optional delivery date`() {
        val json = FhirRecords.pregnancyStatusJson(
            "obs-p",
            PregnancyStatus.Pregnant,
            Instant.parse("2026-03-04T00:00:00Z"),
            Instant.parse("2026-11-01T00:00:00Z"),
        )
        val root = parse(json)

        assertEquals(
            FhirRecords.LOINC_PREGNANCY_STATUS,
            root["code"]!!.jsonObject["coding"]!!.jsonArray.single().jsonObject.str("code"),
        )
        val value = root["valueCodeableConcept"]!!.jsonObject["coding"]!!
            .jsonArray.single().jsonObject
        assertEquals(FhirRecords.SNOMED_SYSTEM, value.str("system"))
        assertEquals("77386006", value.str("code"))

        val component = (root["component"] as JsonArray).single().jsonObject
        assertEquals("2026-11-01", component.str("valueDateTime"))

        val (status, due) = FhirRecords.parsePregnancyStatus(json)!!
        assertEquals(PregnancyStatus.Pregnant, status)
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), due)
    }

    @Test
    fun `not pregnant carries no delivery date`() {
        val json = FhirRecords.pregnancyStatusJson(
            "obs-p", PregnancyStatus.NotPregnant, Instant.parse("2026-03-04T00:00:00Z"), null
        )
        assertNull(parse(json)["component"])
        assertEquals(PregnancyStatus.NotPregnant, FhirRecords.parsePregnancyStatus(json)!!.first)
    }

    @Test
    fun `smoking status round trips through its SNOMED coding`() {
        for (status in SmokingStatus.entries) {
            val json = FhirRecords.smokingStatusJson(
                "obs-s", status, Instant.parse("2026-03-04T00:00:00Z")
            )
            assertEquals(status, FhirRecords.parseSmokingStatus(json), "for $status")
        }
    }

    @Test
    fun `the two profile observations are told apart by their LOINC code`() {
        val pregnancy = FhirRecords.pregnancyStatusJson(
            "a", PregnancyStatus.Pregnant, Instant.EPOCH, null
        )
        val smoking = FhirRecords.smokingStatusJson("b", SmokingStatus.Never, Instant.EPOCH)

        assertEquals(FhirRecords.LOINC_PREGNANCY_STATUS, FhirObservations.observationLoincCode(pregnancy))
        assertEquals(FhirRecords.LOINC_SMOKING_STATUS, FhirObservations.observationLoincCode(smoking))
        assertNull(FhirObservations.observationLoincCode(FhirRecords.conditionJson(diabetes)))
    }

    @Test
    fun `a provider lab result with a coded value rather than a number still parses`() {
        val provider = """
            {
              "resourceType": "Observation",
              "id": "provider-lab",
              "status": "final",
              "code": {
                "coding": [ { "system": "http://loinc.org", "code": "5811-5", "display": "Specific gravity of Urine" } ]
              },
              "subject": { "reference": "Patient/other" },
              "effectiveDateTime": "2025-12-01T09:00:00Z",
              "valueCodeableConcept": { "text": "Trace" }
            }
        """.trimIndent()

        val parsed = FhirRecords.parseLabResult(provider, "hospital")

        assertNotNull(parsed)
        assertEquals("5811-5", parsed.loincCode)
        assertNull(parsed.value)
        assertEquals("Trace", parsed.valueText)
    }

    // --- Helpers -------------------------------------------------------------

    @Test
    fun `parseQuantity only accepts a number followed by a unit`() {
        assertEquals(0.5 to "mL", FhirRecords.parseQuantity("0.5 mL"))
        assertEquals(2.0 to "tablets", FhirRecords.parseQuantity("  2 tablets "))
        assertNull(FhirRecords.parseQuantity("one shot"))
        assertNull(FhirRecords.parseQuantity("0.5"))
    }

    @Test
    fun `parseDateTime accepts every precision FHIR allows`() {
        assertEquals(Instant.parse("2026-03-04T09:30:00Z"), FhirRecords.parseDateTime("2026-03-04T09:30:00Z"))
        assertEquals(Instant.parse("2026-03-04T00:00:00Z"), FhirRecords.parseDateTime("2026-03-04"))
        assertEquals(Instant.parse("2026-03-01T00:00:00Z"), FhirRecords.parseDateTime("2026-03"))
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), FhirRecords.parseDateTime("2026"))
        assertEquals(
            Instant.parse("2026-03-04T08:30:00Z"),
            FhirRecords.parseDateTime("2026-03-04T09:30:00+01:00"),
        )
        assertNull(FhirRecords.parseDateTime("not a date"))
        assertNull(FhirRecords.parseDateTime(null))
    }

    @Test
    fun `a date-only value lands on that day in the device's zone`() {
        val pacific = ZoneId.of("America/Los_Angeles")
        val parsed = FhirRecords.parseDateTime("2026-03-04", pacific)

        assertNotNull(parsed)
        assertEquals(java.time.LocalDate.of(2026, 3, 4), parsed.atZone(pacific).toLocalDate())
    }

    @Test
    fun `a picked date is written as a date in any zone`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val midnight = java.time.LocalDate.of(2026, 3, 4).atStartOfDay(tokyo).toInstant()

        assertEquals("2026-03-04", formatClinicalDate(midnight, tokyo))
        assertEquals("2026-03-04T09:30:00Z", formatClinicalDate(Instant.parse("2026-03-04T09:30:00Z"), tokyo))
    }
}
