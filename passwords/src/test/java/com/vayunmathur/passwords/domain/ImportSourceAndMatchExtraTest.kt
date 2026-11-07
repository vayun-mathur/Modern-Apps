package com.vayunmathur.passwords.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportSourceAndMatchExtraTest {

    @Test fun everySourceHasNameAndPasswordHeaders() {
        for (source in ImportSource.entries) {
            assertTrue(source.nameHeaders.isNotEmpty(), source.name)
            assertTrue(source.passwordHeaders.isNotEmpty(), source.name)
            assertTrue(source.urlHeaders.isNotEmpty(), source.name)
        }
        assertEquals(8, ImportSource.entries.size)
    }

    @Test fun protonPassSplitsUrlsAndHasTypeHeaders() {
        val proton = ImportSource.PROTON_PASS
        assertTrue(proton.splitUrlsOnComma)
        assertEquals(arrayOf("type").toList(), proton.typeHeaders.toList())
        assertEquals(arrayOf("email").toList(), proton.emailHeaders.toList())
        assertFalse(ImportSource.BITWARDEN.splitUrlsOnComma)
    }

    @Test fun firefoxUsesUrlAsNameAndHasNoTotp() {
        assertEquals(arrayOf("url").toList(), ImportSource.FIREFOX.nameHeaders.toList())
        assertTrue(ImportSource.FIREFOX.totpHeaders.isEmpty())
    }

    @Test fun dashlaneAcceptsMultipleUsernameColumns() {
        assertEquals(
            listOf("username", "username2", "username3"),
            ImportSource.DASHLANE.usernameHeaders.toList(),
        )
    }

    @Test fun normalizeSiteStripsSchemeCredentialsPortAndWww() {
        assertEquals("example.com", DomainMatch.normalizeSite("https://user:pw@www.Example.com:8443/path?q=1"))
        assertEquals("example.com", DomainMatch.normalizeSite("example.com."))
        assertEquals("", DomainMatch.normalizeSite("  "))
    }

    @Test fun androidPackageDetection() {
        assertTrue(DomainMatch.isAndroidPackageSite("android-app://com.example.app"))
        assertTrue(DomainMatch.isAndroidPackageSite("com.example.app"))
        assertFalse(DomainMatch.isAndroidPackageSite("example.com"))
        assertFalse(DomainMatch.isAndroidPackageSite("https://com.example.com/path"))
    }

    @Test fun subdomainMatchingIsDotAware() {
        assertTrue(DomainMatch.isSameSiteOrSubdomain("mail.example.com", "example.com"))
        assertTrue(DomainMatch.isSameSiteOrSubdomain("example.com", "example.com"))
        assertFalse(DomainMatch.isSameSiteOrSubdomain("notexample.com", "example.com"))
        assertFalse(DomainMatch.isSameSiteOrSubdomain("", "example.com"))
        assertFalse(DomainMatch.isSameSiteOrSubdomain("example.com", ""))
    }
}
