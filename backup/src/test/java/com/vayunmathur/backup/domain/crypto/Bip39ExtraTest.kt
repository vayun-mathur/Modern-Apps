package com.vayunmathur.backup.domain.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Bip39ExtraTest {
    @Test fun allEntropySizesRoundTrip() {
        for (size in listOf(16, 20, 24, 28, 32)) {
            val entropy = ByteArray(size) { (it * 13 + size).toByte() }
            val words = Bip39.entropyToMnemonic(entropy)
            assertContentEquals(entropy, Bip39.mnemonicToEntropy(words))
            assertTrue(Bip39.isValid(words))
        }
        assertEquals(12, Bip39.entropyToMnemonic(ByteArray(16)).size)
        assertEquals(15, Bip39.entropyToMnemonic(ByteArray(20)).size)
        assertEquals(18, Bip39.entropyToMnemonic(ByteArray(24)).size)
        assertEquals(21, Bip39.entropyToMnemonic(ByteArray(28)).size)
        assertEquals(24, Bip39.entropyToMnemonic(ByteArray(32)).size)
    }

    @Test fun invalidEntropyAndMnemonicShapes() {
        assertFailsWith<IllegalArgumentException> { Bip39.entropyToMnemonic(ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> { Bip39.entropyToMnemonic(ByteArray(17)) }
        assertFailsWith<IllegalArgumentException> { Bip39.entropyToMnemonic(ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { Bip39.mnemonicToEntropy(emptyList()) }
        assertFailsWith<IllegalArgumentException> { Bip39.mnemonicToEntropy(listOf("abandon")) }
        assertFailsWith<IllegalArgumentException> { Bip39.mnemonicToEntropy(listOf("abandon", "abandon")) }
        assertFalse(Bip39.isValid(emptyList()))
        assertFalse(Bip39.isValid(listOf("notaword") + List(11) { "abandon" }))
        // Case sensitive: uppercase word not in list.
        val valid = Bip39.generate()
        assertFalse(Bip39.isValid(listOf(valid[0].uppercase()) + valid.drop(1)))
    }

    @Test fun seedDependsOnPassphraseAndWords() {
        val words = Bip39.entropyToMnemonic(ByteArray(16))
        val a = Bip39.mnemonicToSeed(words)
        val b = Bip39.mnemonicToSeed(words, "pass")
        assertEquals(64, a.size)
        assertFalse(a.contentEquals(b))
        assertContentEquals(a, Bip39.mnemonicToSeed(words, ""))
        assertEquals(Bip39.ENTROPY_BYTES, 16)
        assertEquals(Bip39.WORD_COUNT, 12)
        assertTrue(Bip39.isValid(Bip39.generate()))
    }
}
