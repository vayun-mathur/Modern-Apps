package com.vayunmathur.emergency.domain

import com.vayunmathur.emergency.data.AllergyCriticality
import com.vayunmathur.emergency.data.ImportedAllergy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FhirExtraTest {

    @Test
    fun medicationStatusesGateCurrency() {
        fun med(status: String, type: String = "MedicationRequest") = """
            { "resourceType": "$type", "status": "$status",
              "medicationCodeableConcept": { "text": "Aspirin" } }
        """.trimIndent()
        assertEquals("Aspirin", FhirMedicalParse.parseCurrentMedication(med("active"))?.displayName)
        assertEquals("Aspirin", FhirMedicalParse.parseCurrentMedication(med("intended"))?.displayName)
        assertEquals("Aspirin", FhirMedicalParse.parseCurrentMedication(med("on-hold"))?.displayName)
        assertNull(FhirMedicalParse.parseCurrentMedication(med("completed")))
        assertNull(FhirMedicalParse.parseCurrentMedication(med("stopped")))
        assertNull(FhirMedicalParse.parseCurrentMedication(med("cancelled")))
        assertEquals(
            "Aspirin",
            FhirMedicalParse.parseCurrentMedication(med("active", "MedicationStatement"))?.displayName,
        )
        assertNull(FhirMedicalParse.parseCurrentMedication(med("active", "Medication")))
        // missing status is not current
        assertNull(
            FhirMedicalParse.parseCurrentMedication(
                """{ "resourceType": "MedicationRequest",
                     "medicationCodeableConcept": { "text": "Aspirin" } }""",
            ),
        )
        // no name at all
        assertNull(
            FhirMedicalParse.parseCurrentMedication(
                """{ "resourceType": "MedicationRequest", "status": "active" }""",
            ),
        )
    }

    @Test
    fun medicationNamePrefersRxNormThenTextThenFirstCoding() {
        val rxnormSecond = """
            { "resourceType": "MedicationStatement", "status": "active",
              "medicationCodeableConcept": { "coding": [
                { "system": "http://snomed.info/sct", "display": "SNOMED name" },
                { "system": "http://www.nlm.nih.gov/research/umls/rxnorm", "display": "RxNorm name" }
              ], "text": "Text name" } }
        """.trimIndent()
        assertEquals("RxNorm name", FhirMedicalParse.parseCurrentMedication(rxnormSecond)?.displayName)
        val textOverCoding = """
            { "resourceType": "MedicationStatement", "status": "active",
              "medicationCodeableConcept": { "coding": [
                { "system": "http://snomed.info/sct", "display": "SNOMED name" }
              ], "text": "Text name" } }
        """.trimIndent()
        assertEquals("Text name", FhirMedicalParse.parseCurrentMedication(textOverCoding)?.displayName)
        val codingOnly = """
            { "resourceType": "MedicationStatement", "status": "active",
              "medicationCodeableConcept": { "coding": [
                { "system": "http://snomed.info/sct", "display": "SNOMED name" }
              ] } }
        """.trimIndent()
        assertEquals("SNOMED name", FhirMedicalParse.parseCurrentMedication(codingOnly)?.displayName)
    }

    @Test
    fun conditionGatingAndNaming() {
        fun cond(statusJson: String) = """
            { "resourceType": "Condition", $statusJson
              "code": { "text": "Asthma" } }
        """.trimIndent()
        assertEquals(
            "Asthma",
            FhirMedicalParse.parseCondition(cond(""))?.displayName,
        )
        assertEquals(
            "Asthma",
            FhirMedicalParse.parseCondition(
                cond(""""clinicalStatus": { "coding": [{ "code": "active" }] },"""),
            )?.displayName,
        )
        assertNull(
            FhirMedicalParse.parseCondition(
                cond(""""clinicalStatus": { "coding": [{ "code": "inactive" }] },"""),
            ),
        )
        assertNull(
            FhirMedicalParse.parseCondition(
                cond(""""clinicalStatus": { "coding": [{ "code": "resolved" }] },"""),
            ),
        )
        // clinicalStatus present but with no coding code counts as active
        assertEquals(
            "Asthma",
            FhirMedicalParse.parseCondition(
                cond(""""clinicalStatus": { "coding": [{ "system": "x" }] },"""),
            )?.displayName,
        )
        assertNull(
            FhirMedicalParse.parseCondition(
                """{ "resourceType": "Condition", "code": { } }""",
            ),
        )
        assertNull(
            FhirMedicalParse.parseCondition(
                """{ "resourceType": "Observation", "code": { "text": "Asthma" } }""",
            ),
        )
    }

    @Test
    fun allergyReactionFromCodingAndCriticalityVariants() {
        val viaCoding = """
            { "resourceType": "AllergyIntolerance", "criticality": "low",
              "code": { "text": "Dust" },
              "reaction": [{ "manifestation": [{ "coding": [{ "display": "Sneezing" }] }] }] }
        """.trimIndent()
        val allergy = FhirMedicalParse.parseAllergy(viaCoding)
        assertEquals(AllergyCriticality.Low, allergy?.criticality)
        assertEquals("Sneezing", allergy?.reaction)
        // reaction entries without a usable manifestation produce no reaction
        val noReaction = """
            { "resourceType": "AllergyIntolerance", "criticality": "unable-to-assess",
              "code": { "text": "Dust" },
              "reaction": [{ "substance": { "text": "x" } }, { "manifestation": [] }] }
        """.trimIndent()
        val second = FhirMedicalParse.parseAllergy(noReaction)
        assertEquals(AllergyCriticality.Unknown, second?.criticality)
        assertNull(second?.reaction)
        // inactive allergy drops like a resolved one
        val inactive = """
            { "resourceType": "AllergyIntolerance",
              "clinicalStatus": { "coding": [{ "code": "inactive" }] },
              "code": { "text": "Dust" } }
        """.trimIndent()
        assertNull(FhirMedicalParse.parseAllergy(inactive))
    }

    @Test
    fun malformedInputsAreDropped() {
        assertNull(FhirMedicalParse.parseAllergy("not json"))
        assertNull(FhirMedicalParse.parseAllergy("[1,2,3]"))
        assertNull(FhirMedicalParse.parseAllergy(""))
        assertNull(FhirMedicalParse.parseCondition("{"))
        assertNull(FhirMedicalParse.parseCurrentMedication("42"))
        // non-string resourceType does not match
        assertNull(
            FhirMedicalParse.parseAllergy(
                """{ "resourceType": 7, "code": { "text": "Dust" } }""",
            ),
        )
    }

    @Test
    fun sortAllergiesOrdersHighLowUnknownStable() {
        val input = listOf(
            ImportedAllergy("a-unknown", AllergyCriticality.Unknown, null),
            ImportedAllergy("b-low", AllergyCriticality.Low, null),
            ImportedAllergy("c-high", AllergyCriticality.High, null),
            ImportedAllergy("d-high", AllergyCriticality.High, null),
        )
        assertEquals(
            listOf("c-high", "d-high", "b-low", "a-unknown"),
            FhirMedicalParse.sortAllergies(input).map { it.displayName },
        )
        assertTrue(FhirMedicalParse.sortAllergies(emptyList()).isEmpty())
    }
}
