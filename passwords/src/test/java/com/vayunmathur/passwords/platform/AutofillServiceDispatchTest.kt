package com.vayunmathur.passwords.platform

import android.app.assist.AssistStructure
import android.content.ComponentName
import android.os.Bundle
import android.os.CancellationSignal
import android.service.autofill.FillCallback
import android.service.autofill.FillContext
import android.service.autofill.FillRequest
import android.service.autofill.Presentations
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import com.vayunmathur.library.util.DatabaseHelper
import com.vayunmathur.passwords.FakeAndroidKeyStore
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PasswordAutofillService] and its structure parser.
 *
 * The autofill framework classes have no public constructors, so requests,
 * contexts and view nodes are assembled reflectively from the framework's
 * own classes (the same technique the credential-provider guard tests use):
 * a [AssistStructure] tree carrying a username node, a password node and a
 * web domain, driven through the real parser and service entry points.
 * Callbacks are the framework callbacks backed by a recording AIDL proxy,
 * so "the service answered with no datasets" is asserted, not assumed.
 * Matching itself needs the SQLCipher vault (unavailable under
 * Robolectric), so the fill/save paths that reach the vault surface its
 * [UnsatisfiedLinkError] after the parser has run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutofillServiceDispatchTest {

    @Before fun setUp() = FakeAndroidKeyStore.install()

    private val app get() = RuntimeEnvironment.getApplication()

    // ---- framework object assembly ----

    private fun <T> construct(className: String, types: List<Class<*>>, args: List<Any?>): T {
        val ctor = Class.forName(className).getDeclaredConstructor(*types.toTypedArray())
        ctor.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return ctor.newInstance(*args.toTypedArray()) as T
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun autofillId(id: Int): AutofillId = construct(
        "android.view.autofill.AutofillId", listOf(Integer.TYPE), listOf(id),
    )

    private fun viewNode(
        id: Int,
        hints: Array<String>? = null,
        idEntry: String? = null,
        inputType: Int = 0,
        text: String? = null,
        webDomain: String? = null,
        children: List<Any> = emptyList(),
    ): Any {
        val node: Any = construct("android.app.assist.AssistStructure\$ViewNode", emptyList(), emptyList())
        setField(node, "mAutofillId", autofillId(id))
        if (hints != null) setField(node, "mAutofillHints", hints)
        if (idEntry != null) setField(node, "mIdEntry", idEntry)
        setField(node, "mInputType", inputType)
        // The parser reads the autofill value first, so that alone carries the text.
        if (text != null) setField(node, "mAutofillValue", AutofillValue.forText(text))
        if (webDomain != null) setField(node, "mWebDomain", webDomain)
        val nodeArray = java.lang.reflect.Array.newInstance(node.javaClass, children.size)
        children.forEachIndexed { index, child -> java.lang.reflect.Array.set(nodeArray, index, child) }
        setField(node, "mChildren", nodeArray)
        return node
    }

    private fun fillContext(root: Any): FillContext {
        val window: Any = construct(
            "android.app.assist.AssistStructure\$WindowNode", emptyList(), emptyList(),
        )
        setField(window, "mRoot", root)
        val structure: AssistStructure = construct(
            "android.app.assist.AssistStructure", emptyList(), emptyList(),
        )
        setField(structure, "mWindowNodes", arrayListOf(window))
        setField(structure, "mActivityComponent", ComponentName("com.example.app", "LoginActivity"))
        return construct(
            "android.service.autofill.FillContext",
            listOf(Integer.TYPE, AssistStructure::class.java, AutofillId::class.java),
            listOf(1, structure, autofillId(10)),
        )
    }

    private fun loginContext(): FillContext {
        val username = viewNode(11, hints = arrayOf("username"), text = "alice")
        val password = viewNode(12, hints = arrayOf("password"), text = "s3cret", inputType = 0x81)
        val root = viewNode(10, webDomain = "login.example.com", children = listOf(username, password))
        return fillContext(root)
    }

    private class CallbackLog {
        val calls = mutableListOf<Pair<String, Any?>>()
        fun proxy(interfaceName: String): Any {
            val iface = Class.forName(interfaceName)
            return Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, args ->
                calls += method.name to args?.firstOrNull()
                null
            }
        }
    }

    private fun fillCallback(log: CallbackLog): FillCallback = construct(
        "android.service.autofill.FillCallback",
        listOf(Class.forName("android.service.autofill.IFillCallback"), Integer.TYPE),
        listOf(log.proxy("android.service.autofill.IFillCallback"), 1),
    )

    private fun saveCallback(log: CallbackLog): SaveCallback = construct(
        "android.service.autofill.SaveCallback",
        listOf(Class.forName("android.service.autofill.ISaveCallback")),
        listOf(log.proxy("android.service.autofill.ISaveCallback")),
    )

    private fun fillRequest(contexts: List<FillContext>): FillRequest = construct(
        "android.service.autofill.FillRequest",
        listOf(
            Integer.TYPE, List::class.java, List::class.java, Bundle::class.java,
            Integer.TYPE, Class.forName("android.view.inputmethod.InlineSuggestionsRequest"),
            android.content.IntentSender::class.java,
        ),
        listOf(1, contexts, emptyList<String>(), Bundle(), 0, null, null),
    )

    private fun saveRequest(contexts: List<FillContext>): SaveRequest = construct(
        "android.service.autofill.SaveRequest",
        listOf(ArrayList::class.java, Bundle::class.java, ArrayList::class.java),
        listOf(ArrayList(contexts), null, null),
    )

    private fun service() =
        Robolectric.buildService(PasswordAutofillService::class.java).create().get()

    // ---- StructureParser (private; invoked on the real framework tree) ----

    @Test fun structureParserExtractsCredentialsAndDomain() {
        val parserClass = Class.forName(
            "com.vayunmathur.passwords.platform.PasswordAutofillService\$StructureParser",
        )
        val ctor = parserClass.getDeclaredConstructor(List::class.java)
        ctor.isAccessible = true
        val parser = ctor.newInstance(listOf(loginContext()))

        fun field(name: String): Any? {
            val f = parserClass.getDeclaredField(name)
            f.isAccessible = true
            return f.get(parser)
        }
        assertEquals(autofillId(11), field("usernameId"))
        assertEquals(autofillId(12), field("passwordId"))
        assertEquals("alice", field("usernameText"))
        assertEquals("s3cret", field("passwordText"))
        assertEquals("login.example.com", field("webDomain"))
    }

    @Test fun structureParserEmptyAndPasswordOnlyTrees() {
        val parserClass = Class.forName(
            "com.vayunmathur.passwords.platform.PasswordAutofillService\$StructureParser",
        )
        val ctor = parserClass.getDeclaredConstructor(List::class.java)
        ctor.isAccessible = true

        val empty = ctor.newInstance(emptyList<FillContext>())
        fun field(target: Any, name: String): Any? {
            val f = parserClass.getDeclaredField(name)
            f.isAccessible = true
            return f.get(target)
        }
        assertNull(field(empty, "usernameId"))
        assertNull(field(empty, "passwordId"))
        assertNull(field(empty, "webDomain"))

        // Password detection by input type alone (no hints), nested one level down,
        // and a username detected by its idEntry rather than hints.
        val password = viewNode(22, text = "pw", inputType = 0x81)
        val username = viewNode(21, idEntry = "login_email", text = "bob@example.com")
        val mid = viewNode(20, children = listOf(username, password))
        val root = viewNode(19, children = listOf(mid))
        val parsed = ctor.newInstance(listOf(fillContext(root)))
        assertEquals(autofillId(21), field(parsed, "usernameId"))
        assertEquals("bob@example.com", field(parsed, "usernameText"))
        assertEquals(autofillId(22), field(parsed, "passwordId"))
        assertEquals("pw", field(parsed, "passwordText"))
    }

    // ---- Service entry points ----

    @Test fun fillRequestWithoutVaultKeyAnswersNull() {
        val log = CallbackLog()
        service().onFillRequest(fillRequest(listOf(loginContext())), CancellationSignal(), fillCallback(log))
        assertEquals(listOf("onSuccess" to null), log.calls.map { it.first to it.second })
    }

    @Test fun fillRequestWithVaultKeyButNoPasswordFieldAnswersNull() {
        DatabaseHelper(app).storePassphrase("vault-pw")
        val log = CallbackLog()
        // A tree with only a username field: nothing to fill into.
        val root = viewNode(30, children = listOf(viewNode(31, hints = arrayOf("username"), text = "alice")))
        service().onFillRequest(fillRequest(listOf(fillContext(root))), CancellationSignal(), fillCallback(log))
        assertEquals(1, log.calls.size)
        assertEquals("onSuccess", log.calls.single().first)
        assertNull(log.calls.single().second)
    }

    @Test fun fillRequestWithPasswordFieldReachesVault() {
        DatabaseHelper(app).storePassphrase("vault-pw")
        val log = CallbackLog()
        assertFailsWith<UnsatisfiedLinkError> {
            service().onFillRequest(fillRequest(listOf(loginContext())), CancellationSignal(), fillCallback(log))
        }
    }

    @Test fun saveRequestWithBlankCredentialsAnswersWithoutVault() {
        val log = CallbackLog()
        service().onSaveRequest(saveRequest(emptyList()), saveCallback(log))
        assertEquals(listOf("onSuccess"), log.calls.map { it.first })
    }

    @Test fun saveRequestWithCredentialsReachesVault() {
        assertFailsWith<UnsatisfiedLinkError> {
            service().onSaveRequest(saveRequest(listOf(loginContext())), saveCallback(CallbackLog()))
        }
    }

    // ---- matchesContext / createPresentations (private helpers) ----

    @Test fun matchesContextPackageAndWebRules() {
        val svc = service()
        val method = PasswordAutofillService::class.java.getDeclaredMethod(
            "matchesContext", String::class.java, String::class.java, String::class.java,
        )
        method.isAccessible = true
        fun matches(site: String, pkg: String?, web: String?): Boolean =
            method.invoke(svc, site, pkg, web) as Boolean

        // Package matching: exact, android-app:// spelling, case/space tolerant.
        assertTrue(matches("com.example.app", "com.example.app", null))
        assertTrue(matches("android-app://com.example.app", "COM.EXAMPLE.APP", null))
        assertTrue(matches(" com.example.app/ ", "com.example.app", null))
        // Web matching: a stored parent fills on subdomains, not vice versa.
        assertTrue(matches("example.com", null, "login.example.com"))
        assertTrue(matches("example.com", null, "https://example.com/login"))
        assertEquals(false, matches("login.example.com", null, "example.com"))
        assertEquals(false, matches("other.com", null, "example.com"))
        assertEquals(false, matches("example.com", "com.other.app", null))
        assertEquals(false, matches("example.com", null, null))
    }

    @Test fun createPresentationsWithoutInlineSpecBuildsMenuPresentation() {
        val svc = service()
        val method = PasswordAutofillService::class.java.getDeclaredMethod(
            "createPresentations", String::class.java,
            Class.forName("android.widget.inline.InlinePresentationSpec"),
        )
        method.isAccessible = true
        val presentations = method.invoke(svc, "GitHub", null) as Presentations
        assertNotNull(presentations)
        assertNotNull(presentations.menuPresentation)
    }
}
