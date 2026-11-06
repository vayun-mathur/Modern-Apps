package com.vayunmathur.health.util

import com.vayunmathur.health.data.AllergyCategory
import com.vayunmathur.health.data.AllergyCriticality
import com.vayunmathur.health.data.AllergyEntry
import com.vayunmathur.health.data.ConditionEntry
import com.vayunmathur.health.data.ConditionStatus
import com.vayunmathur.health.data.DoseEvent
import com.vayunmathur.health.data.LabResultEntry
import com.vayunmathur.health.data.MedicationEntry
import com.vayunmathur.health.data.MedicationSchedule
import com.vayunmathur.health.data.MedicationStatus
import com.vayunmathur.health.data.PregnancyStatus
import com.vayunmathur.health.data.RepeatUnit
import com.vayunmathur.health.data.SmokingStatus
import com.vayunmathur.health.data.VaccinationEntry
import com.vayunmathur.health.domain.FhirRecords
import com.vayunmathur.health.domain.SocialHistoryQuestions
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Closes the remaining gaps in the FHIR layer: builder branches the main
 * suite never takes (unit-less quantities, blank-field trimming, reference
 * edges), the forgiving reader's fallbacks, and the shared JSON helpers.
 */
class FhirRecordsGapsTest {

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

    private val t = Instant.parse("2026-03-04T09:30:00Z")
    private val day = Instant.parse("2026-03-04T00:00:00Z")

    // --- Immunization writer branches --------------------------------------

    @Test
    fun `blank optional fields are omitted rather than written empty`() {
        val entry = VaccinationEntry(
            id = "v1", displayName = "Flu", occurredAt = t,
            lotNumber = "  ", site = "", route = " ", performer = " ", note = "",
        )
        val json = FhirRecords.immunizationJson(entry)
        for (key in listOf("lotNumber", "site", "route", "performer", "note")) {
            assertTrue("\"$key\"" !in json, "$key leaked as blank")
        }
    }

    @Test
    fun `a whitespace only dose is not a dose and not a note`() {
        val entry = VaccinationEntry(id = "v1", displayName = "Flu", occurredAt = t, doseQuantity = "  ")
        val json = FhirRecords.immunizationJson(entry)
        assertTrue("\"doseQuantity\"" !in json)
        assertTrue("\"note\"" !in json)
    }

    @Test
    fun `site and route fall back to coded display text`() {
        val provider = """
            {
              "resourceType": "Immunization", "id": "p1",
              "vaccineCode": { "text": "Flu" },
              "patient": { "reference": "Patient/x" },
              "occurrenceDateTime": "2026-03-04",
              "site": { "coding": [ { "display": "Left deltoid" } ] },
              "route": { "coding": [ { "code": "IM", "display": "Intramuscular" } ] }
            }
        """.trimIndent()
        val parsed = FhirRecords.parseImmunization(provider, "h")
        assertNotNull(parsed)
        assertEquals("Left deltoid", parsed.site)
        assertEquals("Intramuscular", parsed.route)
        assertNull(parsed.performer)
        assertNull(parsed.note)
    }

    @Test
    fun `an immunization without an occurrence is refused`() {
        val missing = """
            {
              "resourceType": "Immunization", "id": "p1",
              "vaccineCode": { "text": "Flu" },
              "patient": { "reference": "Patient/x" }
            }
        """.trimIndent()
        assertNull(FhirRecords.parseImmunization(missing, "h"))
    }

    @Test
    fun `attachments only ride along when present`() {
        val entry = VaccinationEntry(id = "v1", displayName = "Flu", occurredAt = day)
        val bare = FhirRecords.immunizationJson(entry)
        assertTrue("\"extension\"" !in bare)
        val withAttachments = FhirRecords.immunizationJson(
            entry,
            listOf(
                FhirRecords.FhirAttachment("image/jpeg", "card.jpg", "file:///card.jpg", t),
                FhirRecords.FhirAttachment("application/pdf", "card.pdf", "file:///card.pdf", t),
            ),
        )
        val count = "\"valueAttachment\"".toRegex().findAll(withAttachments).count()
        assertEquals(2, count)
    }

    // --- Medication writer branches -----------------------------------------

    @Test
    fun `a free text medication has no coding but keeps its name`() {
        val entry = MedicationEntry(
            id = "m1", displayName = "Herbal tea", status = MedicationStatus.Active,
            startedAt = day,
        )
        val json = FhirRecords.medicationRequestJson(entry)
        assertTrue("\"coding\"" !in json)
        assertTrue("Herbal tea" in json)
        assertEquals("Herbal tea", FhirRecords.fullMedicationName(entry))
    }

    @Test
    fun `full medication name skips blank parts`() {
        val entry = MedicationEntry(
            id = "m1", displayName = "  ", strength = "", doseForm = "Tablet",
            status = MedicationStatus.Active, startedAt = day,
        )
        assertEquals("Tablet", FhirRecords.fullMedicationName(entry))
    }

    @Test
    fun `stopped and completed statuses map distinctly`() {
        val stopped = MedicationEntry(
            id = "m1", displayName = "X", status = MedicationStatus.Stopped, startedAt = day,
        )
        assertTrue("\"stopped\"" in FhirRecords.medicationRequestJson(stopped))
        val parsed = FhirRecords.parseMedicationRequest(FhirRecords.medicationRequestJson(stopped), "s")
        assertNotNull(parsed)
        assertEquals(MedicationStatus.Stopped, parsed.first.status)
    }

    @Test
    fun `a medication dated only by authoredOn still parses`() {
        val json = """
            {
              "resourceType": "MedicationRequest", "id": "rx-1",
              "status": "completed",
              "intent": "plan",
              "medicationCodeableConcept": { "text": "Aspirin" },
              "subject": { "reference": "Patient/x" },
              "authoredOn": "2024-05-01"
            }
        """.trimIndent()
        val parsed = FhirRecords.parseMedicationRequest(json, "h")
        assertNotNull(parsed)
        assertEquals(Instant.parse("2024-05-01T00:00:00Z"), parsed.first.startedAt)
        assertNull(parsed.second)
    }

    @Test
    fun `a request with no usable start is refused`() {
        val json = """
            {
              "resourceType": "MedicationRequest", "id": "rx-1",
              "status": "active",
              "medicationCodeableConcept": { "text": "Aspirin" },
              "subject": { "reference": "Patient/x" }
            }
        """.trimIndent()
        assertNull(FhirRecords.parseMedicationRequest(json, "h"))
    }

    @Test
    fun `a fractional or zero period yields no schedule`() {
        for (period in listOf("0", "1.5")) {
            val json = """
                {
                  "resourceType": "MedicationRequest", "id": "rx-1",
                  "status": "active",
                  "medicationCodeableConcept": { "text": "Aspirin" },
                  "subject": { "reference": "Patient/x" },
                  "authoredOn": "2024-05-01",
                  "dosageInstruction": [ { "timing": { "repeat": {
                    "boundsPeriod": { "start": "2024-05-01" },
                    "timeOfDay": ["08:00:00"], "period": $period, "periodUnit": "d"
                  } } } ]
                }
            """.trimIndent()
            assertNotNull(FhirRecords.parseMedicationRequest(json, "h"), "entry for $period")
            assertNull(
                FhirRecords.parseMedicationRequest(json, "h")?.second,
                "schedule for $period",
            )
        }
    }

    @Test
    fun `a non numeric period degrades to the daily default rather than refusing`() {
        val json = """
            {
              "resourceType": "MedicationRequest", "id": "rx-1",
              "status": "active",
              "medicationCodeableConcept": { "text": "Aspirin" },
              "subject": { "reference": "Patient/x" },
              "authoredOn": "2024-05-01",
              "dosageInstruction": [ { "timing": { "repeat": {
                "boundsPeriod": { "start": "2024-05-01" },
                "timeOfDay": ["08:00:00"], "period": "daily", "periodUnit": "d"
              } } } ]
            }
        """.trimIndent()
        val schedule = FhirRecords.parseMedicationRequest(json, "h")?.second
        assertNotNull(schedule)
        assertEquals(RepeatUnit.Daily, schedule.repeatUnit)
        assertEquals(1, schedule.interval)
    }

    @Test
    fun `a daily schedule with a multi day period and weekdays is refused`() {
        val json = """
            {
              "resourceType": "MedicationRequest", "id": "rx-1",
              "status": "active",
              "medicationCodeableConcept": { "text": "Aspirin" },
              "subject": { "reference": "Patient/x" },
              "authoredOn": "2024-05-01",
              "dosageInstruction": [ { "timing": { "repeat": {
                "boundsPeriod": { "start": "2024-05-01" },
                "timeOfDay": ["08:00:00"], "period": 2, "periodUnit": "d",
                "dayOfWeek": ["mon"]
              } } } ]
            }
        """.trimIndent()
        assertNull(FhirRecords.parseMedicationRequest(json, "h")?.second)
    }

    @Test
    fun `an unknown day name is ignored rather than failing the schedule`() {
        val json = """
            {
              "resourceType": "MedicationRequest", "id": "rx-1",
              "status": "active",
              "medicationCodeableConcept": { "text": "Aspirin" },
              "subject": { "reference": "Patient/x" },
              "authoredOn": "2024-05-01",
              "dosageInstruction": [ { "timing": { "repeat": {
                "boundsPeriod": { "start": "2024-05-01" },
                "timeOfDay": ["08:00"], "period": 1, "periodUnit": "d",
                "dayOfWeek": ["funday", "mon"]
              } } } ]
            }
        """.trimIndent()
        val schedule = FhirRecords.parseMedicationRequest(json, "h")?.second
        assertNotNull(schedule)
        assertEquals(RepeatUnit.Weekly, schedule.repeatUnit)
        assertEquals(1 shl 1, schedule.daysOfWeek)
        assertEquals(listOf(8 * 3600), schedule.times)
    }

    @Test
    fun `a weekly schedule with no days yields no schedule`() {
        val json = """
            {
              "resourceType": "MedicationRequest", "id": "rx-1",
              "status": "active",
              "medicationCodeableConcept": { "text": "Aspirin" },
              "subject": { "reference": "Patient/x" },
              "authoredOn": "2024-05-01",
              "dosageInstruction": [ { "timing": { "repeat": {
                "boundsPeriod": { "start": "2024-05-01" },
                "timeOfDay": ["08:00:00"], "period": 1, "periodUnit": "wk"
              } } } ]
            }
        """.trimIndent()
        assertNull(FhirRecords.parseMedicationRequest(json, "h")?.second)
    }

    @Test
    fun `a statement dated by assertion still parses`() {
        val json = """
            {
              "resourceType": "MedicationStatement", "id": "s-1",
              "status": "active",
              "medicationCodeableConcept": { "text": "Metformin" },
              "subject": { "reference": "Patient/x" },
              "dateAsserted": "2024-06-15"
            }
        """.trimIndent()
        val parsed = FhirRecords.parseMedicationStatement(json, "h")
        assertNotNull(parsed)
        assertEquals(Instant.parse("2024-06-15T00:00:00Z"), parsed.startedAt)
        assertEquals(MedicationStatus.Active, parsed.status)
    }

    @Test
    fun `a statement with an undated course is refused`() {
        val json = """
            {
              "resourceType": "MedicationStatement", "id": "s-1",
              "status": "active",
              "medicationCodeableConcept": { "text": "Metformin" },
              "subject": { "reference": "Patient/x" }
            }
        """.trimIndent()
        assertNull(FhirRecords.parseMedicationStatement(json, "h"))
    }

    @Test
    fun `isEnteredInError reads verificationStatus for allergy shaped resources`() {
        assertTrue(
            FhirRecords.isEnteredInError(
                """{"resourceType":"AllergyIntolerance","verificationStatus":{"coding":[{"code":"entered-in-error"}]}}"""
            )
        )
        assertTrue(
            FhirRecords.isEnteredInError(
                """{"resourceType":"Condition","verificationStatus":{"text":"entered-in-error"}}"""
            )
        )
    }

    // --- Allergy and condition branches --------------------------------------

    @Test
    fun `allergy without a recorded date falls back to epoch`() {
        val json = """
            {
              "resourceType": "AllergyIntolerance", "id": "a-1",
              "code": { "text": "Latex" },
              "patient": { "reference": "Patient/x" }
            }
        """.trimIndent()
        val parsed = FhirRecords.parseAllergyIntolerance(json, "h")
        assertNotNull(parsed)
        assertEquals(Instant.EPOCH, parsed.recordedAt)
        assertEquals(AllergyCategory.Medication, parsed.category)
        assertEquals(AllergyCriticality.Unknown, parsed.criticality)
        assertNull(parsed.onsetAt)
    }

    @Test
    fun `allergy categories round trip`() {
        for (category in AllergyCategory.entries) {
            val entry = AllergyEntry(
                id = "a1", displayName = "X", category = category,
                recordedAt = t,
            )
            val parsed = FhirRecords.parseAllergyIntolerance(
                FhirRecords.allergyIntoleranceJson(entry), "s"
            )
            assertNotNull(parsed, "for $category")
            assertEquals(category, parsed.category)
        }
    }

    @Test
    fun `allergy criticality low round trips`() {
        val entry = AllergyEntry(
            id = "a1", displayName = "X", criticality = AllergyCriticality.Low,
            recordedAt = t,
        )
        assertEquals(
            AllergyCriticality.Low,
            FhirRecords.parseAllergyIntolerance(FhirRecords.allergyIntoleranceJson(entry), "s")?.criticality,
        )
    }

    @Test
    fun `condition statuses round trip`() {
        for (status in ConditionStatus.entries) {
            val entry = ConditionEntry(
                id = "c1", displayName = "X", status = status, onsetAt = day,
            )
            val parsed = FhirRecords.parseCondition(FhirRecords.conditionJson(entry), "s")
            assertNotNull(parsed, "for $status")
            assertEquals(status, parsed.status)
        }
    }

    @Test
    fun `condition onset falls back to recorded date then epoch`() {
        val byRecorded = """
            {
              "resourceType": "Condition", "id": "c-1",
              "code": { "text": "Asthma" },
              "subject": { "reference": "Patient/x" },
              "recordedDate": "2020-01-05"
            }
        """.trimIndent()
        assertEquals(
            Instant.parse("2020-01-05T00:00:00Z"),
            FhirRecords.parseCondition(byRecorded, "h")?.onsetAt,
        )
        val undated = """
            {
              "resourceType": "Condition", "id": "c-1",
              "code": { "text": "Asthma" },
              "subject": { "reference": "Patient/x" }
            }
        """.trimIndent()
        assertEquals(Instant.EPOCH, FhirRecords.parseCondition(undated, "h")?.onsetAt)
    }

    @Test
    fun `condition abatement falls back to the period end`() {
        val json = """
            {
              "resourceType": "Condition", "id": "c-1",
              "clinicalStatus": { "text": "resolved" },
              "code": { "text": "Urticaria" },
              "subject": { "reference": "Patient/x" },
              "onsetDateTime": "2024-01-01",
              "abatementPeriod": { "start": "2024-01-02", "end": "2024-01-09" }
            }
        """.trimIndent()
        val parsed = FhirRecords.parseCondition(json, "h")
        assertNotNull(parsed)
        assertEquals(ConditionStatus.Resolved, parsed.status)
        assertEquals(Instant.parse("2024-01-09T00:00:00Z"), parsed.resolvedAt)
    }

    // --- Observation branches --------------------------------------------------

    @Test
    fun `lab without a LOINC code still round trips on text`() {
        val entry = LabResultEntry(id = "l1", displayName = "COVID rapid", valueText = "Negative", takenAt = t)
        val parsed = FhirRecords.parseLabResult(FhirRecords.labResultJson(entry), "s")
        assertNotNull(parsed)
        assertNull(parsed.loincCode)
        assertEquals("Negative", parsed.valueText)
        assertNull(parsed.unit)
    }

    @Test
    fun `lab dated by issued still parses`() {
        val json = """
            {
              "resourceType": "Observation", "id": "l-9",
              "status": "final",
              "code": { "text": "Panel" },
              "subject": { "reference": "Patient/x" },
              "issued": "2025-11-02T10:00:00Z",
              "valueString": "Done"
            }
        """.trimIndent()
        val parsed = FhirRecords.parseLabResult(json, "h")
        assertNotNull(parsed)
        assertEquals(Instant.parse("2025-11-02T10:00:00Z"), parsed.takenAt)
    }

    @Test
    fun `lab without any date is refused`() {
        val json = """
            {
              "resourceType": "Observation", "id": "l-9",
              "status": "final",
              "code": { "text": "Panel" },
              "subject": { "reference": "Patient/x" }
            }
        """.trimIndent()
        assertNull(FhirRecords.parseLabResult(json, "h"))
    }

    @Test
    fun `lab unit falls back to the unit code`() {
        val json = """
            {
              "resourceType": "Observation", "id": "l-9",
              "status": "final",
              "code": { "coding": [ { "system": "http://loinc.org", "code": "1234-5", "display": "X" } ] },
              "subject": { "reference": "Patient/x" },
              "effectiveDateTime": "2025-11-02T10:00:00Z",
              "valueQuantity": { "value": 3.0, "code": "mg/dL" }
            }
        """.trimIndent()
        assertEquals("mg/dL", FhirRecords.parseLabResult(json, "h")?.unit)
    }

    @Test
    fun `unknown pregnancy and smoking codes degrade to unknown`() {
        val pregnancy = """
            {
              "resourceType": "Observation", "id": "o-1",
              "code": { "coding": [ { "system": "http://loinc.org", "code": "82810-3" } ] },
              "valueCodeableConcept": { "coding": [ { "system": "http://snomed.info/sct", "code": "999" } ] }
            }
        """.trimIndent()
        assertEquals(PregnancyStatus.Unknown, FhirRecords.parsePregnancyStatus(pregnancy)?.first)
        assertNull(FhirRecords.parsePregnancyStatus(pregnancy)?.second)

        val smoking = """
            {
              "resourceType": "Observation", "id": "o-2",
              "code": { "coding": [ { "system": "http://loinc.org", "code": "72166-2" } ] },
              "valueCodeableConcept": { "coding": [ { "system": "http://snomed.info/sct", "code": "999" } ] }
            }
        """.trimIndent()
        assertEquals(SmokingStatus.Unknown, FhirRecords.parseSmokingStatus(smoking))
    }

    @Test
    fun `alternate smoking codes map to current`() {
        for (code in listOf("428041000124106", "77176002")) {
            val json = """
                {
                  "resourceType": "Observation", "id": "o-2",
                  "valueCodeableConcept": { "coding": [ { "system": "http://snomed.info/sct", "code": "$code" } ] }
                }
            """.trimIndent()
            assertEquals(SmokingStatus.Current, FhirRecords.parseSmokingStatus(json), "for $code")
        }
    }

    @Test
    fun `resource id and effective instant read through`() {
        assertEquals("x-1", FhirRecords.resourceId("""{"id":"x-1"}"""))
        assertNull(FhirRecords.resourceId("not json"))
        assertEquals(
            Instant.parse("2025-11-02T10:00:00Z"),
            FhirRecords.observationEffective("""{"effectiveDateTime":"2025-11-02T10:00:00Z"}"""),
        )
        assertEquals(
            Instant.parse("2025-11-02T00:00:00Z"),
            FhirRecords.observationEffective("""{"effectivePeriod":{"start":"2025-11-02"}}"""),
        )
        assertNull(FhirRecords.observationEffective("""{"resourceType":"Observation"}"""))
    }

    @Test
    fun `parseQuantity edge cases`() {
        assertEquals(-0.5 to "mg", FhirRecords.parseQuantity("-0.5 mg"))
        assertEquals(2.0 to "tablets", FhirRecords.parseQuantity("2tablets"))
        assertNull(FhirRecords.parseQuantity(""))
        assertNull(FhirRecords.parseQuantity("   "))
    }

    @Test
    fun `dose event without an effective date is refused`() {
        val json = """
            {
              "resourceType": "MedicationStatement", "id": "d-1",
              "status": "completed",
              "basedOn": [ { "reference": "MedicationRequest/rx-1" } ],
              "medicationCodeableConcept": { "text": "X" }
            }
        """.trimIndent()
        assertNull(FhirRecords.parseDoseEvent(json, "h"))
    }

    @Test
    fun `a dose event carries its fhir id`() {
        val event = DoseEvent(id = "d-1", medicationId = "m-1", takenAt = t, fhirResourceId = "fhir-d")
        val med = MedicationEntry(
            id = "m-1", displayName = "X", status = MedicationStatus.Active, startedAt = day,
        )
        val json = FhirRecords.doseEventJson(event, med)
        assertTrue("fhir-d" in json)
    }

    @Test
    fun `social history answer json uses the question wording`() {
        val question = SocialHistoryQuestions.HOUSING
        val answer = question.answers.first()
        val json = FhirRecords.socialHistoryAnswerJson("o", question, answer, t)
        assertTrue(question.loinc in json)
        assertTrue(answer.code in json)
        assertEquals("o", FhirRecords.resourceId(json))
    }
}
