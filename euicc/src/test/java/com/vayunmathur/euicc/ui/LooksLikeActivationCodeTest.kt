package com.vayunmathur.euicc.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LooksLikeActivationCodeTest {
    @Test fun `accepts canonical activation codes`() {
        assertTrue(looksLikeActivationCode("LPA:1\$smdp.example.com\$MATCH-123"))
        assertTrue(looksLikeActivationCode("LPA:1\$smdp.example.com\$MATCH123\$extra\$1"))
        assertTrue(looksLikeActivationCode("  LPA:1\$rsp.example.co.uk\$ABC-def-09  "))
    }

    @Test fun `accepts bare lpa deep link body`() {
        assertTrue(looksLikeActivationCode("lpa:something"))
        assertTrue(looksLikeActivationCode("LPA:"))
        assertTrue(looksLikeActivationCode("lpa:1\$smdp.example.com\$XYZ"))
    }

    @Test fun `accepts dollar structure without LPA prefix when valid`() {
        // hasActivationStructure: '$' in trimmed, then LPA prefix removal is a no-op;
        // parts are checked as version/host/matchingId.
        assertTrue(looksLikeActivationCode("1\$smdp.example.com\$ABC123"))
    }

    @Test fun `rejects empty and blank`() {
        assertFalse(looksLikeActivationCode(""))
        assertFalse(looksLikeActivationCode("   "))
    }

    @Test fun `any lpa prefix is handed to native parser`() {
        // The LPA: prefix short-circuits: the native core does the real
        // parsing there, so even structurally odd LPA: strings pass this gate.
        assertTrue(looksLikeActivationCode("LPA:2\$smdp.example.com\$ABC123"))
        assertTrue(looksLikeActivationCode("LPA:1\$smdp.example.com"))
        assertTrue(looksLikeActivationCode("LPA:1"))
    }

    @Test fun `rejects wrong version missing parts and bad host or matching id without prefix`() {
        assertFalse(looksLikeActivationCode("2\$smdp.example.com\$ABC123"))
        assertFalse(looksLikeActivationCode("1\$smdp.example.com"))
        assertFalse(looksLikeActivationCode("1\$localhost\$ABC123"))
        assertFalse(looksLikeActivationCode("1\$\$ABC123"))
        assertFalse(looksLikeActivationCode("1\$smdp.example.com\$"))
        assertFalse(looksLikeActivationCode("1\$smdp.example.com\$AB_C!"))
        assertFalse(looksLikeActivationCode("1\$smdp.example.com\$AB C"))
        assertFalse(looksLikeActivationCode("hello world"))
        assertFalse(looksLikeActivationCode("1smdp.example.comABC123"))
    }

    @Test fun `matching id allows hyphen and alphanumerics only`() {
        assertTrue(looksLikeActivationCode("1\$a.b\$-"))
        assertTrue(looksLikeActivationCode("1\$a.b\$aZ09-"))
        assertFalse(looksLikeActivationCode("1\$a.b\$a.b"))
    }
}
