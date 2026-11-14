package com.vayunmathur.openassistant.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelLoadFailureTest {

    private val required = listOf("text", "embed", "tok")
    private val expected = mapOf("text" to 100L, "embed" to 200L, "head" to 50L, "tok" to 5L, "vision" to 30L)
    private val full: Map<String, Long?> = expected

    @Test
    fun allFilesPresentIsAFailureToLoadCarryingTheRuntimeReason() {
        assertEquals(
            ModelLoadFailure.FailedToLoad("weights past maxMemoryAllocationSize"),
            classifyModelLoadFailure(full, required, expected, "weights past maxMemoryAllocationSize"),
        )
        assertEquals(ModelLoadFailure.FailedToLoad(null), classifyModelLoadFailure(full, required, expected, " "))
    }

    @Test
    fun optionalFilesAbsentAreNotMissing() {
        val noOptional = full - "head" - "vision"
        assertEquals(ModelLoadFailure.FailedToLoad(null), classifyModelLoadFailure(noOptional, required, expected, null))
    }

    @Test
    fun absentRequiredFilesAreMissingWhateverTheRuntimeSaid() {
        val sizes = full + ("text" to null) - "tok"
        assertEquals(
            ModelLoadFailure.Missing(listOf("text", "tok")),
            classifyModelLoadFailure(sizes, required, expected, "native said something"),
        )
    }

    @Test
    fun shortFilesAreIncompleteIncludingOptionalOnes() {
        val sizes = full + ("embed" to 199L) + ("vision" to 1L)
        assertEquals(
            ModelLoadFailure.Incomplete(listOf("embed 199/200", "vision 1/30")),
            classifyModelLoadFailure(sizes, required, expected, null),
        )
    }

    @Test
    fun messagesNameTheCauseAndOnlyMissingSaysMissing() {
        val dir = "/x/gemma4"
        val missing = ModelLoadFailure.Missing(listOf("gemma4_text.maml")).describe(dir)
        assertTrue(missing.contains("missing") && missing.contains("gemma4_text.maml") && missing.contains(dir))
        val short = ModelLoadFailure.Incomplete(listOf("embed 1/2")).describe(dir)
        assertTrue(short.contains("incomplete") && short.contains("embed 1/2"))
        val refused = ModelLoadFailure.FailedToLoad("GPU limit").describe(dir)
        assertTrue(refused.contains("present") && refused.endsWith("failed to load: GPU limit"))
        val silent = ModelLoadFailure.FailedToLoad(null).describe(dir)
        assertTrue(silent.contains("no reason"))
        for (text in listOf(short, refused, silent)) assertFalse(text.contains("missing", ignoreCase = true), text)
    }
}
