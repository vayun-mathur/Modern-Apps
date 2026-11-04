package com.vayunmathur.securemail.domain.mail

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AliasGeneratorTest {

    private val shape = Regex("^[a-z]+-[a-z]+-[1-9][0-9]{3}@maemail\\.org$")

    @Test
    fun generatesClaimableReadableAddresses() {
        val random = Random(42)
        repeat(500) {
            val alias = AliasGenerator.generate(random)
            assertTrue(shape.matches(alias), alias)
            val local = alias.substringBefore('@')
            assertEquals(alias, AddressValidator.canonicalAddress(local), "passes claim validation")
        }
    }

    @Test
    fun defaultSourceIsVaried() {
        val seen = (1..200).map { AliasGenerator.generate() }.toSet()
        assertTrue(seen.size > 190, "expected near-unique aliases, got ${seen.size}")
    }
}
