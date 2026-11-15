package com.vayunmathur.health.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the social history question table.
 *
 * These codes were read out of the LOINC 2.83 release rather than recalled, and the point of this
 * suite is that they stay that way — a wrong concept id in a medical record is not a cosmetic bug,
 * and nothing else in the build would notice one.
 */
class SocialHistoryQuestionsTest {

    private fun parse(json: String) = Json.parseToJsonElement(json).jsonObject

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content

    @Test
    fun `every question has a well formed LOINC code and at least two answers`() {
        val loincPattern = Regex("""^\d{3,6}-\d$""")
        for (question in SocialHistoryQuestions.ALL) {
            assertTrue(
                loincPattern.matches(question.loinc),
                "${question.loinc} is not shaped like a LOINC code",
            )
            assertTrue(
                question.answers.size >= 2,
                "${question.loinc} needs answers to be worth asking",
            )
            assertTrue(question.display.isNotBlank(), "${question.loinc} has no display name")
        }
    }

    @Test
    fun `every answer has a well formed LA code`() {
        val laPattern = Regex("""^LA\d+-\d$""")
        for (question in SocialHistoryQuestions.ALL) {
            for (answer in question.answers) {
                assertTrue(
                    laPattern.matches(answer.code),
                    "${answer.code} on ${question.loinc} is not shaped like a LOINC answer code",
                )
                assertTrue(answer.display.isNotBlank(), "${answer.code} has no display text")
            }
        }
    }

    @Test
    fun `answer codes are unique within a question`() {
        for (question in SocialHistoryQuestions.ALL) {
            val codes = question.answers.map { it.code }
            assertEquals(
                codes.size,
                codes.distinct().size,
                "${question.loinc} repeats an answer code",
            )
        }
    }

    @Test
    fun `questions are unique and findable by their code`() {
        val codes = SocialHistoryQuestions.ALL.map { it.loinc }
        assertEquals(codes.size, codes.distinct().size, "a question code is repeated")
        for (question in SocialHistoryQuestions.ALL) {
            assertEquals(question, SocialHistoryQuestions.byLoinc(question.loinc))
        }
        assertNull(SocialHistoryQuestions.byLoinc("00000-0"))
    }

    @Test
    fun `smoking and pregnancy are not in the table`() {
        // Both have their own US Core profiles bound to SNOMED rather than to a LOINC answer list,
        // so they are handled as their own fields. Adding them here would write them twice.
        assertNull(SocialHistoryQuestions.byLoinc(FhirRecords.LOINC_SMOKING_STATUS))
        assertNull(SocialHistoryQuestions.byLoinc(FhirRecords.LOINC_PREGNANCY_STATUS))
    }

    @Test
    fun `every answer round trips through its observation`() {
        val recordedAt = Instant.parse("2026-05-02T10:00:00Z")
        for (question in SocialHistoryQuestions.ALL) {
            for (answer in question.answers) {
                val json = FhirRecords.socialHistoryAnswerJson("obs", question, answer, recordedAt)

                assertEquals(
                    question.loinc,
                    FhirObservations.observationLoincCode(json),
                    "question code lost for ${question.loinc}",
                )
                assertEquals(
                    answer.code,
                    FhirObservations.parseSocialHistoryAnswer(json),
                    "answer code lost for ${answer.code}",
                )
            }
        }
    }

    @Test
    fun `the observation is categorised as social history and coded in LOINC`() {
        val question = SocialHistoryQuestions.ALCOHOL
        val root = parse(
            FhirRecords.socialHistoryAnswerJson(
                "obs", question, question.answers.first(), Instant.EPOCH
            )
        )

        assertEquals("Observation", root.str("resourceType"))
        assertEquals("final", root.str("status"))
        assertEquals(
            "social-history",
            root["category"]!!.jsonArray.single().jsonObject["coding"]!!
                .jsonArray.single().jsonObject.str("code"),
        )
        val code = root["code"]!!.jsonObject["coding"]!!.jsonArray.single().jsonObject
        assertEquals(FhirRecords.LOINC_SYSTEM, code.str("system"))
        assertEquals("68518-0", code.str("code"))

        // Both question and answer are LOINC, so no second terminology is involved.
        val value = root["valueCodeableConcept"]!!.jsonObject["coding"]!!
            .jsonArray.single().jsonObject
        assertEquals(FhirRecords.LOINC_SYSTEM, value.str("system"))
    }

    @Test
    fun `the two hunger vital sign questions share one answer list`() {
        // Same LOINC list, and now the same labels too: "Often true" reads correctly because the
        // statement it agrees with is the row title, in full, rather than a paraphrase of it.
        assertEquals(
            SocialHistoryQuestions.FOOD_WORRIED.answers,
            SocialHistoryQuestions.FOOD_RAN_OUT.answers,
        )
    }

    @Test
    fun `the label shown is separate from the wording written to the record`() {
        val question = SocialHistoryQuestions.FOOD_WORRIED
        val often = question.answers.first()

        // labelRes is ours and translatable; code and display belong to LOINC and are what travels
        // to Health Connect, so rewording a button can never change what the record says.
        assertEquals("LA28397-0", often.code)
        assertEquals("Often true", often.display)

        val json = FhirRecords.socialHistoryAnswerJson("obs", question, often, Instant.EPOCH)
        val value = parse(json)["valueCodeableConcept"]!!.jsonObject
        assertEquals(
            "Often true",
            value["coding"]!!.jsonArray.single().jsonObject.str("display"),
        )
        assertEquals("LA28397-0", FhirObservations.parseSocialHistoryAnswer(json))
    }

    @Test
    fun `a non observation is not mistaken for an answer`() {
        assertNull(FhirObservations.parseSocialHistoryAnswer("""{"resourceType":"Condition"}"""))
        assertNull(FhirObservations.parseSocialHistoryAnswer("not json at all"))
    }

    @Test
    fun `an observation with no coded value yields no answer`() {
        val bare = """
            {
              "resourceType": "Observation",
              "id": "x",
              "code": { "coding": [ { "system": "http://loinc.org", "code": "68518-0" } ] }
            }
        """.trimIndent()

        assertEquals("68518-0", FhirObservations.observationLoincCode(bare))
        assertNull(FhirObservations.parseSocialHistoryAnswer(bare))
    }

    @Test
    fun `the alcohol question uses the AUDIT-C scale rather than the shared one`() {
        // AUDIT-C asks how often you drink at all; the shared scale counts occasions of use. They
        // read similarly and are different LOINC answer lists.
        val alcohol = SocialHistoryQuestions.ALCOHOL.answers.map { it.code }
        val drugs = SocialHistoryQuestions.DRUGS.answers.map { it.code }

        assertNotNull(alcohol.firstOrNull { it == "LA18926-8" })
        assertTrue(alcohol != drugs)
    }
}
