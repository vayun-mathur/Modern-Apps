package com.vayunmathur.health.domain

import com.vayunmathur.health.data.AllergyCategory
import com.vayunmathur.health.data.AllergyCriticality
import com.vayunmathur.health.data.ConditionStatus
import com.vayunmathur.health.data.MedicationStatus
import com.vayunmathur.health.data.RepeatUnit
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W6: provider-loose FHIR variants the round-trip tests never produce —
 * period/age dating, relapse/inactive status spellings, sub-daily repeats
 * that must be refused, and malformed timings that yield no schedule.
 */
class FhirLooseW6Test {

    private lateinit var zone: TimeZone
    @BeforeTest fun pin() { zone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }
    @AfterTest fun unpin() { TimeZone.setDefault(zone) }

    private fun allergy(category: String?, criticality: String?, extra: String = "") = """
        {"resourceType":"AllergyIntolerance","id":"a1","category":["$category"],"criticality":"$criticality",
         "code":{"coding":[{"system":"http://www.nlm.nih.gov/research/umls/rxnorm","code":"7980","display":"Penicillin"}],
         "text":"Penicillin G"},"recordedDate":"2025-01-02T00:00:00Z"$extra}
    """.trimIndent()

    @Test
    fun allergy_categoriesAndCriticalities() {
        assertEquals(AllergyCategory.Food, FhirRecords.parseAllergyIntolerance(allergy("food", "low"), "s")!!.category)
        assertEquals(AllergyCategory.Environment, FhirRecords.parseAllergyIntolerance(allergy("environment", "high"), "s")!!.category)
        assertEquals(AllergyCategory.Biologic, FhirRecords.parseAllergyIntolerance(allergy("biologic", "unable-to-assess"), "s")!!.category)
        assertEquals(AllergyCategory.Medication, FhirRecords.parseAllergyIntolerance(allergy("weird", null), "s")!!.category)
        assertEquals(AllergyCriticality.Low, FhirRecords.parseAllergyIntolerance(allergy("food", "low"), "s")!!.criticality)
        assertEquals(AllergyCriticality.Unknown, FhirRecords.parseAllergyIntolerance(allergy("food", "unable-to-assess"), "s")!!.criticality)
        // Reaction manifestation text and onset are read when present.
        val withReaction = FhirRecords.parseAllergyIntolerance(
            allergy("food", "high", ""","onsetDateTime":"2024-05-01T00:00:00Z","reaction":[{"manifestation":[{"text":"Hives"}]}]"""), "s",
        )!!
        assertEquals("Hives", withReaction.reaction)
        assertNull(FhirRecords.parseAllergyIntolerance("""{"resourceType":"Condition","id":"x"}""", "s"))
        assertNull(FhirRecords.parseAllergyIntolerance("""{"resourceType":"AllergyIntolerance"}""", "s"))
        assertNull(FhirRecords.parseAllergyIntolerance("not json", "s"))
    }

    @Test
    fun condition_statusSpellingsAndPeriodDating() {
        fun cond(status: String, extra: String = "") = """
            {"resourceType":"Condition","id":"c1",
             "clinicalStatus":{"coding":[{"code":"$status"}]},
             "code":{"text":"Diabetes"}$extra}
        """.trimIndent()
        assertEquals(ConditionStatus.Recurrence, FhirRecords.parseCondition(cond("relapse"), "s")!!.status)
        assertEquals(ConditionStatus.Resolved, FhirRecords.parseCondition(cond("inactive"), "s")!!.status)
        assertEquals(ConditionStatus.Remission, FhirRecords.parseCondition(cond("remission"), "s")!!.status)
        assertEquals(ConditionStatus.Active, FhirRecords.parseCondition(cond("bogus"), "s")!!.status)
        // onsetPeriod.start and abatementPeriod.end are honoured.
        val period = FhirRecords.parseCondition(
            cond("active", ""","onsetPeriod":{"start":"2023-03-01T00:00:00Z"},"abatementPeriod":{"end":"2024-03-01T00:00:00Z"}"""), "s",
        )!!
        assertEquals(2023, period.onsetAt.atZone(java.time.ZoneOffset.UTC).year)
        assertEquals(2024, period.resolvedAt!!.atZone(java.time.ZoneOffset.UTC).year)
        // No dates at all falls back to the epoch, not a crash.
        assertEquals(java.time.Instant.EPOCH, FhirRecords.parseCondition(cond("active"), "s")!!.onsetAt)
    }

    private fun request(repeat: String?, status: String = "active", extra: String = "") = """
        {"resourceType":"MedicationRequest","id":"m1","status":"$status",
         "medicationCodeableConcept":{"coding":[{"system":"http://www.nlm.nih.gov/research/umls/rxnorm","code":"123","display":"Amoxicillin"}],"text":"Amoxicillin"},
         "authoredOn":"2025-02-01T00:00:00Z",
         "dosageInstruction":[{"text":"1 tab","timing":{"repeat":$repeat}}]$extra}
    """.trimIndent()

    @Test
    fun medicationRequest_repeatVariants() {
        // Weekly on Mon/Wed at 08:00 and 20:30 (no seconds field tolerated).
        val weekly = FhirRecords.parseMedicationRequest(
            request("""{"frequency":1,"period":1,"periodUnit":"wk","dayOfWeek":["mon","wed"],"timeOfDay":["08:00","20:30:15"]}"""), "s",
        )!!
        assertEquals(MedicationStatus.Active, weekly.first.status)
        assertEquals(RepeatUnit.Weekly, weekly.second!!.repeatUnit)
        assertEquals(listOf(8 * 3600, 20 * 3600 + 30 * 60 + 15), weekly.second!!.times)
        // Daily with weekdays normalises to weekly; with a 2-day period it is refused.
        val dailyDays = FhirRecords.parseMedicationRequest(
            request("""{"period":1,"periodUnit":"d","dayOfWeek":["fri"],"timeOfDay":["09:00:00"]}"""), "s",
        )!!
        assertEquals(RepeatUnit.Weekly, dailyDays.second!!.repeatUnit)
        assertNull(
            FhirRecords.parseMedicationRequest(
                request("""{"period":2,"periodUnit":"d","dayOfWeek":["fri"],"timeOfDay":["09:00:00"]}"""), "s",
            )!!.second,
        )
        // Every-8-hours and fractional periods cannot be expressed: no schedule.
        assertNull(FhirRecords.parseMedicationRequest(request("""{"period":8,"periodUnit":"h","timeOfDay":["09:00:00"]}"""), "s")!!.second)
        assertNull(FhirRecords.parseMedicationRequest(request("""{"period":0.5,"periodUnit":"d","timeOfDay":["09:00:00"]}"""), "s")!!.second)
        // No clock times -> no schedule; unknown day names are ignored.
        assertNull(FhirRecords.parseMedicationRequest(request("""{"period":1,"periodUnit":"d"}"""), "s")!!.second)
        val badDay = FhirRecords.parseMedicationRequest(
            request("""{"period":1,"periodUnit":"wk","dayOfWeek":["noday","tue"],"timeOfDay":["07:00:00","bad","x:y"]}"""), "s",
        )!!
        assertEquals(listOf(7 * 3600), badDay.second!!.times)
        // Weekly without weekdays is refused.
        assertNull(FhirRecords.parseMedicationRequest(request("""{"period":1,"periodUnit":"wk","timeOfDay":["07:00:00"]}"""), "s")!!.second)
        // Stopped requests keep their planned end; active ones do not.
        val stopped = FhirRecords.parseMedicationRequest(
            request("""{"period":1,"periodUnit":"d","timeOfDay":["07:00:00"],"boundsPeriod":{"start":"2025-01-01T00:00:00Z","end":"2025-03-01T00:00:00Z"}}""", status = "stopped"), "s",
        )!!
        assertNotNull(stopped.first.endedAt)
        assertNull(FhirRecords.parseMedicationRequest(request(null), "s")!!.second)
        assertNull(FhirRecords.parseMedicationRequest(request("""{"timeOfDay":["07:00:00"]}""", status = "entered-in-error"), "s"))
    }

    @Test
    fun medicationStatement_andDoseEventRules() {
        val statement = """
            {"resourceType":"MedicationStatement","id":"s1","status":"active",
             "medicationCodeableConcept":{"text":"Ibuprofen"},
             "effectivePeriod":{"start":"2025-01-01T00:00:00Z","end":"2025-02-01T00:00:00Z"}}
        """.trimIndent()
        val parsed = FhirRecords.parseMedicationStatement(statement, "src")
        assertNotNull(parsed); assertNull(parsed.endedAt, "active statements keep no end")
        assertTrue(FhirRecords.isUnplannedMedicationStatement(statement))
        // A dose (basedOn a request) is not an unplanned statement, and parses as a dose only when completed.
        val dose = """
            {"resourceType":"MedicationStatement","id":"d1","status":"completed",
             "basedOn":[{"reference":"https://example.org/fhir/MedicationRequest/req-9"}],
             "medicationCodeableConcept":{"text":"Ibuprofen"},"effectiveDateTime":"2025-06-01T08:00:00Z"}
        """.trimIndent()
        assertEquals("req-9", FhirRecords.parseDoseEvent(dose, "src")!!.medicationId)
        assertNull(FhirRecords.parseMedicationStatement(dose, "src"))
        assertNull(FhirRecords.parseDoseEvent(dose.replace("completed", "not-taken"), "src"))
        assertNull(FhirRecords.parseDoseEvent(statement, "src"), "no basedOn -> not a dose")
    }
}
