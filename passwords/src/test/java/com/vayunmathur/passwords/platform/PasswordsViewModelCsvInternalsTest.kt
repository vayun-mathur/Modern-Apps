package com.vayunmathur.passwords.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.passwords.FakeAndroidKeyStore
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.data.PasswordRepository
import com.vayunmathur.passwords.domain.ImportSource
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The CSV-import internals of [PasswordsViewModel] that the public import
 * flow is built from. End-to-end row imports cannot run under Robolectric
 * (the upsert opens SQLCipher), so these pure stages — CSV parsing, column
 * mapping into a [Password], TOTP extraction, website splitting and the
 * type-prefix naming rule — are pinned directly at the private functions
 * the flow calls, with the same inputs the flow would feed them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PasswordsViewModelCsvInternalsTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() = FakeAndroidKeyStore.install()

    private fun vm() = PasswordsViewModel(app, PasswordRepository.get(app))

    @Suppress("UNCHECKED_CAST")
    private fun <T> call(name: String, vararg args: Any?): T {
        val method = PasswordsViewModel::class.java.declaredMethods
            .first { it.name == name && it.parameterCount == args.size }
        method.isAccessible = true
        return method.invoke(vm(), *args) as T
    }

    private fun columns(
        name: Int = 0, username: Int = 1, password: Int = 2, url: Int = 3,
        totp: Int = 4, email: Int = 5, note: Int = 6, type: Int = 7,
    ) = PasswordsViewModel.ImportColumns(name, username, password, url, totp, email, note, type)

    // ---- parseCsv ----

    @Test fun parseCsvHandlesQuotingEscapesAndLineEndings() {
        val text = "name,user,pass\r\n" +
            "\"Git, Hub\",\"octo\",\"p\"\"w\"\n" +
            "\"multi\nline\",u2,p2\n" +
            "trailing,u3,p3"
        val rows: List<List<String>> = call("parseCsv", text)
        assertEquals(4, rows.size)
        assertEquals(listOf("name", "user", "pass"), rows[0])
        assertEquals(listOf("Git, Hub", "octo", "p\"w"), rows[1])
        assertEquals(listOf("multi\nline", "u2", "p2"), rows[2])
        // A final row without a trailing newline is still flushed.
        assertEquals(listOf("trailing", "u3", "p3"), rows[3])
    }

    @Test fun parseCsvEmptyAndNewlineOnlyInputs() {
        assertEquals(emptyList(), call<List<List<String>>>("parseCsv", ""))
        assertEquals(listOf(listOf("a")), call<List<List<String>>>("parseCsv", "a\n"))
        assertEquals(listOf(listOf("a", "b")), call<List<List<String>>>("parseCsv", "a,b\r\n"))
    }

    // ---- buildPassword / its helpers ----

    @Test fun buildPasswordMapsColumnsAndExtractsTotpUri() {
        val row = listOf(
            "Mail", "alice", "secret",
            "https://mail.example, https://webmail.example",
            "otpauth://totp/Mail:alice?secret=JBSWY3DP&issuer=Mail",
            "alice@example.com", "a note", "login",
        )
        val built: Password = call("buildPassword", row, columns(), ImportSource.PROTON_PASS)
        assertEquals("Mail", built.name)
        assertEquals("alice", built.username)
        assertEquals("alice@example.com", built.email)
        assertEquals("secret", built.password)
        assertEquals("a note", built.note)
        assertEquals("JBSWY3DP", built.totpSecret)
        // Proton Pass is the source that splits websites on commas.
        assertEquals("https://mail.example|https://webmail.example", built.websites.joinToString("|"))
    }

    @Test fun buildPasswordToleratesShortRowsAndPrefixesNonLoginTypes() {
        val sparse: Password = call(
            "buildPassword", listOf("Only Name"), columns(), ImportSource.BITWARDEN,
        )
        assertEquals("Only Name", sparse.name)
        assertEquals("", sparse.username)
        assertEquals("", sparse.password)
        assertNull(sparse.totpSecret)
        assertEquals(emptyList(), sparse.websites)

        // Non-login rows are prefixed with their type; a missing name falls
        // back to the URL and is prefixed the same way.
        val ssh: Password = call(
            "buildPassword",
            listOf("Tyche", "", "key", "", "", "", "", "sshKey"),
            columns(), ImportSource.BITWARDEN,
        )
        assertEquals("sshKey: Tyche", ssh.name)
        val unnamed: Password = call(
            "buildPassword",
            listOf("", "", "", "https://host.example", "", "", "", "card"),
            columns(), ImportSource.BITWARDEN,
        )
        assertEquals("card: https://host.example", unnamed.name)
        // "login" (any case) is never prefixed.
        val login: Password = call(
            "buildPassword",
            listOf("Plain", "", "", "", "", "", "", "LOGIN"),
            columns(), ImportSource.BITWARDEN,
        )
        assertEquals("Plain", login.name)
    }

    @Test fun totpExtractionVariants() {
        assertNull(call<String?>("extractTotpSecret", ""))
        assertEquals("PLAINSEED", call<String?>("extractTotpSecret", "PLAINSEED"))
        assertEquals(
            "ABC123",
            call<String?>("extractTotpSecret", "otpauth://totp/x?issuer=Y&secret=ABC123&digits=6"),
        )
        // An otpauth URI without a secret parameter is kept verbatim.
        assertEquals(
            "otpauth://totp/x?issuer=Y",
            call<String?>("extractTotpSecret", "otpauth://totp/x?issuer=Y"),
        )
    }

    @Test fun websiteSplittingRespectsSourceSeparators() {
        val commaSource: List<String> = call(
            "splitWebsites", "a.com, b.com; c.com\nd.com", ImportSource.PROTON_PASS,
        )
        assertEquals("a.com|b.com|c.com|d.com", commaSource.joinToString("|"))
        // Other sources keep a comma inside one site, and blank fragments are dropped.
        val noComma: List<String> = call(
            "splitWebsites", "a.com, b.com; ; c.com", ImportSource.BITWARDEN,
        )
        assertEquals("a.com, b.com|c.com", noComma.joinToString("|"))
        assertEquals(emptyList(), call<List<String>>("splitWebsites", " ; ", ImportSource.CHROME))
        assertTrue(call<List<String>>("splitWebsites", "  spaced.com  ", ImportSource.FIREFOX)
            == listOf("spaced.com"))
    }
}
