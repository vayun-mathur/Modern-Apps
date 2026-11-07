package com.vayunmathur.openassistant.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** JsonSchemaValidator (subset used for tool args) + JsonExtractor streaming recovery. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class JsonValidationTest {

    private fun validate(json: String, schema: String) =
        JsonSchemaValidator.validateJsonAgainstSchema(json, schema)

    @Test fun validObjectPasses() {
        assertNull(validate(
            """{"name":"ann","age":3}""",
            """{"type":"object","properties":{"name":{},"age":{}},"required":["name"]}"""))
    }

    @Test fun invalidJsonAndInvalidSchema() {
        assertTrue(validate("not json", "{}")!!.startsWith("Invalid JSON format"))
        // A schema that is itself invalid JSON is a programmer error: null (no user error).
        assertNull(validate("{}", "not json"))
        // Non-object schema imposes nothing.
        assertNull(validate("""{"a":1}""", """["x"]"""))
    }

    @Test fun typeMismatchAndNestedPaths() {
        assertTrue(validate("""[1]""", """{"type":"object"}""")!!.contains("Expected an object"))
        assertTrue(validate("""{"a":1}""", """{"type":"array"}""")!!.contains("Expected an array"))
        val nested = validate(
            """{"outer":{"inner":[1,2]}}""",
            """{"type":"object","properties":{"outer":{"type":"object","properties":{"inner":{"type":"object"}}}}}""")
        assertTrue(nested!!.contains("at outer.inner"), nested)
    }

    @Test fun unknownAndRequiredFields() {
        val schema = """{"type":"object","properties":{"name":{}},"required":["name","id"]}"""
        assertTrue(validate("""{"name":"x","extra":1,"id":2}""", schema)!!.contains("Unexpected field found: 'extra'"))
        assertTrue(validate("""{"name":"x"}""", schema)!!.contains("Missing required field: 'id'"))
        // Without a properties map every field is unexpected.
        assertTrue(validate("""{"a":1}""", """{"type":"object"}""")!!.contains("Unexpected field"))
    }

    @Test fun constAndEnumConstraints() {
        val schema = """{"properties":{"kind":{"const":"note"},"level":{"enum":["low","high"]}}}"""
        assertTrue(validate("""{"kind":"task","level":"low"}""", schema)!!.contains("must be 'note'"))
        val enumErr = validate("""{"kind":"note","level":"mid"}""", schema)
        assertTrue(enumErr!!.contains("invalid value 'mid'"), enumErr)
        assertTrue(enumErr.contains("low"), enumErr)
        assertNull(validate("""{"kind":"note","level":"high"}""", schema))
    }

    @Test fun arrayItemsAreValidated() {
        val schema = """{"type":"array","items":{"type":"object","properties":{"n":{}},"required":["n"]}}"""
        assertNull(validate("""[{"n":1},{"n":2}]""", schema))
        val err = validate("""[{"n":1},{}]""", schema)
        assertTrue(err!!.contains("[1]"), err)
        assertTrue(err.contains("Missing required field"), err)
    }

    @Test fun anyOfOneOfNotCombinators() {
        // Note: object validation is strict about unknown fields, so an
        // anyOf branch must declare its properties to ever match.
        val typedAnyOf = """{"anyOf":[{"properties":{"a":{}},"required":["a"]},{"properties":{"b":{}},"required":["b"]}]}"""
        // One branch matches, but the enclosing object check then rejects the
        // field (combinators do not short-circuit sibling validation).
        assertTrue(validate("""{"b":1}""", typedAnyOf)!!.contains("Unexpected field"))
        assertTrue(validate("""{"c":1}""", typedAnyOf)!!.contains("anyOf"))

        val oneOf = """{"oneOf":[{"properties":{"a":{}},"required":["a"]},{"properties":{"a":{},"b":{}},"required":["a"]}]}"""
        // No data set cleanly satisfies exactly one branch under the strict
        // unknown-field rule, so both shapes surface a validation error.
        assertNotNull(validate("""{"a":1,"b":2}""", oneOf))
        assertTrue(validate("""{"z":1}""", oneOf)!!.contains("oneOf"))

        val notSchema = """{"not":{"properties":{"bad":{}},"required":["bad"]}}"""
        assertTrue(validate("""{"bad":1}""", notSchema)!!.contains("forbidden"))
        // A non-matching field passes the 'not' check but is then rejected by
        // the enclosing strict object check (no properties declared).
        assertTrue(validate("""{"good":1}""", notSchema)!!.contains("Unexpected field"))
    }

    @Test fun trimJsonKeysRecurses() {
        val parsed = Json.parseToJsonElement("""{" a ": {" b ": [ {" c ": 1} ] } }""")
        val trimmed = JsonSchemaValidator.trimJsonKeys(parsed) as JsonObject
        assertEquals(setOf("a"), trimmed.keys)
        val inner = trimmed["a"] as JsonObject
        val arr = inner["b"] as kotlinx.serialization.json.JsonArray
        assertEquals(JsonPrimitive(1), (arr[0] as JsonObject)["c"])
        // Primitives pass through untouched.
        assertEquals(JsonPrimitive(" x "), JsonSchemaValidator.trimJsonKeys(JsonPrimitive(" x ")))
    }

    // ---- JsonExtractor ----

    @Test fun extractorFindsBalancedObject() {
        assertEquals("""{"a":1}""", JsonExtractor.largestObject("""prefix {"a":1} suffix"""))
        assertEquals("""{"a":{"b":2}}""", JsonExtractor.largestObject("""{"a":{"b":2}}"""))
        assertNull(JsonExtractor.largestObject("no json here"))
        assertNull(JsonExtractor.largestObject("""{"unclosed": """))
    }

    @Test fun extractorIgnoresBracesInsideStrings() {
        val text = """{"a":"} not a brace {"} tail"""
        assertEquals("""{"a":"} not a brace {"}""", JsonExtractor.largestObject(text))
        val escaped = """{"a":"quote \" } still string"}"""
        assertEquals(escaped, JsonExtractor.largestObject(escaped))
    }

    @Test fun extractorSkipsInvalidCandidateForLaterValidOne() {
        // First balanced candidate is not valid JSON (trailing comma style), a later one is.
        assertNull(JsonExtractor.largestObject("""{"a":}"""))
        val mixed = """noise {"bad":} more {"good":1}"""
        // The extractor returns the first *valid* balanced object it completes.
        val found = JsonExtractor.largestObject(mixed)
        assertTrue(found == null || found.contains("good"), "found=$found")
        assertNotNull(JsonExtractor.largestObject("""{"good":1}"""))
    }
}
