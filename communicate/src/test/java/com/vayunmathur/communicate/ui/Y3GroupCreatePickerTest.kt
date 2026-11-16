package com.vayunmathur.communicate.ui

import android.Manifest
import android.app.Application
import android.app.role.RoleManager
import android.content.Context
import android.provider.ContactsContract
import android.util.Base64
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.FakeContactsProvider
import com.vayunmathur.communicate.data.FakeContactsState
import com.vayunmathur.communicate.data.FakeMmsProvider
import com.vayunmathur.communicate.data.FakeMmsSmsProvider
import com.vayunmathur.communicate.data.FakeSmsProvider
import com.vayunmathur.communicate.data.FakeTelephonyState
import com.vayunmathur.communicate.data.SmsThread
import com.vayunmathur.communicate.data.W1SmsManagerShadow
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceSession
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.RcsRegistrationState
import com.vayunmathur.communicate.data.rcs.RcsSessionManager
import com.vayunmathur.communicate.data.rcs.RcsSipTransport
import com.vayunmathur.communicate.data.rcs.e2e.RustMlsCrypto
import com.vayunmathur.communicate.data.rcs.focusMembers
import com.vayunmathur.communicate.data.rcs.hostedFocusFor
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.communicate.data.signal.SignalClient
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDatabase
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.decodeNode
import com.vayunmathur.communicate.data.whatsapp.encodeNode
import com.vayunmathur.communicate.data.whatsapp.e2e.RustWhatsAppCrypto
import com.vayunmathur.communicate.data.whatsapp.e2e.WhatsAppE2E
import com.vayunmathur.communicate.data.whatsapp.handleIncomingMessage
import com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.DataStoreUtils
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowRoleManager

/**
 * Y3: the `MessagesScreen` create-group lambda
 * (`MessagesScreenKt$MessagesScreen$3$1$1`, the `NewMessagePicker`
 * `onCreateGroup` handler) driven end-to-end through the real picker UI:
 * WhatsApp not-connected notice, WhatsApp connected create (fake-socket IQ
 * completion), Signal create success, and the RCS plaintext-focus /
 * encrypted-group branches. Threads opened and `AppMessages` posted are the
 * assertions — the lambda itself has no other observable surface.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [W1SmsManagerShadow::class])
class Y3GroupCreatePickerTest {

    @get:Rule val rule = createComposeRule()

    companion object {
        fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
        fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
        fun authFor(idSeed: Int, spkSeed: Int, wid: String, lid: String): WhatsAppAuthData {
            val idPriv = ByteArray(32) { (idSeed + it).toByte() }
            val idPub = RustWhatsAppCrypto.publicFromPrivate(idPriv)!!
            val spkPriv = ByteArray(32) { (spkSeed + it).toByte() }
            val spkPub = RustWhatsAppCrypto.publicFromPrivate(spkPriv)!!
            return WhatsAppAuthData(
                phoneNumber = "+15550001", pushName = "Tester", wid = wid,
                noisePrivateKey = b64(ByteArray(32) { 1 }), noisePublicKey = b64(ByteArray(32) { 2 }),
                identityPrivateKey = b64(idPriv), identityPublicKey = b64(idPub),
                registrationId = 14, signedPreKeyId = 5,
                signedPreKeyPublic = b64(spkPub), signedPreKeyPrivate = b64(spkPriv),
                signedPreKeySignature = b64(WhatsAppE2E.signSignedPreKey(idPriv, spkPub)), lid = lid,
            )
        }
        val WA_AUTH: WhatsAppAuthData by lazy { authFor(1, 41, "15550001@s.whatsapp.net", "12345@lid") }
        const val NEW_GROUP_JID = "120363777@g.us"
    }

    /** FakeSocket seam copied from Y2WaSocketSendTest. */
    private class FakeSocket(auth: WhatsAppAuthData) {
        val socket = WhatsAppSocket(auth)
        private val out = ByteArrayOutputStream()
        private val decryptor: Any
        init {
            val key = SecretKeySpec(ByteArray(32) { 42 }, "AES")
            val noiseClass = Class.forName(
                "com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket\$NoiseSocket")
            val ctor = noiseClass.getDeclaredConstructor(
                SecretKeySpec::class.java, SecretKeySpec::class.java).apply { isAccessible = true }
            val noise = ctor.newInstance(key, key)
            decryptor = ctor.newInstance(key, key)
            setField(socket, "noiseSocket", noise)
            setField(socket, "output", out)
            setField(socket, "isConnected", true)
            setField(socket, "isHandshakeComplete", true)
        }
        fun drainNodes(): List<WhatsAppProtocol.Node> {
            val bytes = synchronized(out) { val b = out.toByteArray(); out.reset(); b }
            val plain = mutableListOf<ByteArray>()
            var off = 0
            val decrypt = decryptor.javaClass.getDeclaredMethod("decrypt", ByteArray::class.java)
                .apply { isAccessible = true }
            while (off + 3 <= bytes.size) {
                val len = ((bytes[off].toInt() and 0xFF) shl 16) or
                    ((bytes[off + 1].toInt() and 0xFF) shl 8) or (bytes[off + 2].toInt() and 0xFF)
                plain.add(decrypt.invoke(decryptor, bytes.copyOfRange(off + 3, off + 3 + len)) as ByteArray)
                off += 3 + len
            }
            return plain.map { WhatsAppProtocol.decodeNode(it) }
        }
        companion object {
            fun setField(target: Any, name: String, value: Any?) {
                target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
            }
        }
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var rcsDb: RcsDatabase
    private lateinit var waDb: WhatsAppDatabase
    private lateinit var sigDb: SignalDatabase
    private lateinit var fake: FakeSocket
    private val opened = CopyOnWriteArrayList<SmsThread>()
    private val appMessages = CopyOnWriteArrayList<String>()
    private val referSent = CopyOnWriteArrayList<Triple<String, String, ByteArray>>()

    @BeforeTest
    fun setUp() {
        FakeTelephonyState.reset()
        FakeContactsState.reset()
        Robolectric.setupContentProvider(FakeSmsProvider::class.java, "sms")
        Robolectric.setupContentProvider(FakeMmsProvider::class.java, "mms")
        Robolectric.setupContentProvider(FakeMmsSmsProvider::class.java, "mms-sms")
        Robolectric.setupContentProvider(FakeContactsProvider::class.java, ContactsContract.AUTHORITY)
        FakeContactsState.phoneRows = listOf(
            mapOf(
                ContactsContract.CommonDataKinds.Phone._ID to 1L,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY to "Alice Y3",
                ContactsContract.CommonDataKinds.Phone.NUMBER to "+14155550102",
                ContactsContract.CommonDataKinds.Phone.TYPE to ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
                ContactsContract.CommonDataKinds.Phone.LABEL to null,
            ),
            mapOf(
                ContactsContract.CommonDataKinds.Phone._ID to 2L,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY to "Bob Y3",
                ContactsContract.CommonDataKinds.Phone.NUMBER to "+14155550103",
                ContactsContract.CommonDataKinds.Phone.TYPE to ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
                ContactsContract.CommonDataKinds.Phone.LABEL to null,
            ),
        )
        val app = ApplicationProvider.getApplicationContext<Application>()
        rcsDb = Room.inMemoryDatabaseBuilder(app, RcsDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        waDb = Room.inMemoryDatabaseBuilder(app, WhatsAppDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        sigDb = Room.inMemoryDatabaseBuilder(app, SignalDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        RcsDatabase.overrideForTest = rcsDb
        WhatsAppDatabase.overrideForTest = waDb
        SignalDatabase.overrideForTest = sigDb
        shadowOf(app).grantPermissions(
            Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE,
        )
        val shadow = Shadow.extract<ShadowRoleManager>(app.getSystemService(RoleManager::class.java))
        shadow.addHeldRole(RoleManager.ROLE_SMS)
        shadow.addHeldRole(RoleManager.ROLE_DIALER)
        shadow.addAvailableRole(RoleManager.ROLE_SMS)
        shadow.addAvailableRole(RoleManager.ROLE_DIALER)

        // WhatsApp client over the fake socket (Connected unless a test flips it).
        fake = FakeSocket(WA_AUTH)
        WhatsAppClient.appContext = app
        WhatsAppClient.authData = WA_AUTH
        WhatsAppClient.db = waDb
        WhatsAppClient.webSocket = fake.socket
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
        WhatsAppClient.suppressReconnect = false
        WhatsAppClient.pendingIqs.clear(); WhatsAppClient.knownGroups.clear()

        // Signal client with an in-memory DB + auth, so createGroup persists locally.
        SignalClient.db = sigDb
        SignalClient.e2e = null
        SignalClient.authData = SignalAuthData(
            phoneNumber = "+14155552671", aci = "aaaaaaaa-1111-2222-3333-444455556666", password = "secret")
        SignalClient.socket = null
        SignalClient.unauthSocket = null
        SignalClient.appContext = null

        // RCS line available + SIP sends captured (focus hosting can proceed).
        val stateField = RcsSipTransport.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (stateField.get(RcsSipTransport) as kotlinx.coroutines.flow.MutableStateFlow<RcsRegistrationState>).value =
            RcsRegistrationState.Available
        referSent.clear()
        RcsSipTransport.fakeSenderForTest = { s, h, b -> referSent += Triple(s, h, b.copyOf()); true }
        RcsSessionManager.sessionsMutable.value = emptyMap()

        opened.clear(); appMessages.clear()
        CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            AppMessages.messages.collect { appMessages += it.text }
        }
        runBlocking {
            DataStoreUtils.getInstance(app).setBoolean("wa_signed_in", true)
            DataStoreUtils.getInstance(app).setBoolean("sig_signed_in", true)
        }
    }

    @AfterTest
    fun tearDown() {
        runBlocking {
            DataStoreUtils.getInstance(context).setBoolean("wa_signed_in", false)
            DataStoreUtils.getInstance(context).setBoolean("sig_signed_in", false)
            GoogleVoiceSession.get(context).signOut()
        }
        val shadow = Shadow.extract<ShadowRoleManager>(context.getSystemService(RoleManager::class.java))
        shadow.removeHeldRole(RoleManager.ROLE_SMS)
        shadow.removeHeldRole(RoleManager.ROLE_DIALER)
        RcsSipTransport.fakeSenderForTest = null
        RcsDatabase.overrideForTest = null
        WhatsAppDatabase.overrideForTest = null
        SignalDatabase.overrideForTest = null
        SignalClient.db = null; SignalClient.authData = null; SignalClient.e2e = null
        rcsDb.close(); waDb.close(); sigDb.close()
    }

    private fun exists(text: String, timeoutMs: Long = 15_000) {
        rule.waitUntil(timeoutMs) {
            rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Open MessagesScreen, open the picker via the FAB, switch to group mode. */
    private fun openGroupPicker() {
        rule.setContent {
            MessagesScreen(onOpenThread = { opened += it }, onOpenAccounts = {})
        }
        // The FAB carries no text: click clickables until the picker dialog shows.
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (rule.onAllNodesWithText("New message").fetchSemanticsNodes().isNotEmpty()) break
            val clickables = rule.onAllNodes(hasClickAction())
            for (i in 0 until clickables.fetchSemanticsNodes().size) {
                if (rule.onAllNodesWithText("New message").fetchSemanticsNodes().isNotEmpty()) break
                runCatching { clickables[i].performClick() }
                rule.waitForIdle()
            }
            if (rule.onAllNodesWithText("New message").fetchSemanticsNodes().isEmpty()) Thread.sleep(50)
        }
        exists("New message")
        rule.onNode(isToggleable()).performClick() // "Group" switch
        rule.waitForIdle()
        exists("New group")
        exists("Alice Y3")
    }

    /** Pick [label] in the line selector (dropdown over the current selection). */
    private fun selectLine(label: String) {
        // The selector chip shows the currently selected line's label; open it.
        val current = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue(current.isNotEmpty())
        // Click the chip: it is the clickable whose text is a known line label.
        var openedDropdown = false
        for (candidate in listOf("SIM", "WhatsApp", "Signal", "RCS", "Google Voice")) {
            if (rule.onAllNodesWithText(candidate).fetchSemanticsNodes().isNotEmpty()) {
                runCatching { rule.onNodeWithText(candidate).performClick() }
                rule.waitForIdle()
                if (rule.onAllNodesWithText(label).fetchSemanticsNodes().size >= 1) {
                    openedDropdown = true
                    break
                }
            }
        }
        assertTrue(openedDropdown, "line dropdown never opened")
        // The last node with this text is the dropdown item (the chip sits behind it).
        val items = rule.onAllNodesWithText(label)
        items[items.fetchSemanticsNodes().size - 1].performClick()
        rule.waitForIdle()
        // The selector chip now reads the chosen line.
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Number of toggleables currently On (group switch + selected contact checkboxes). */
    private fun checkedToggleCount(): Int {
        val on = androidx.compose.ui.test.SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.ToggleableState,
            androidx.compose.ui.state.ToggleableState.On,
        )
        return rule.onAllNodes(on).fetchSemanticsNodes().size
    }

    /**
     * Click the [index]-th toggleable (Group switch is 0, then the contact
     * checkboxes in list order) until the checked-toggle count reaches
     * [target]. Row-text clicks proved unreliable against the merged
     * semantics of the picker rows, so the checkboxes are driven directly.
     */
    private fun dumpState(tag: String) {
        runCatching {
            val texts = rule.onAllNodesWithText("", substring = true).fetchSemanticsNodes()
                .mapNotNull { node ->
                    runCatching {
                        node.config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                            .joinToString("") { it.text }
                    }.getOrNull()
                }
            val toggles = rule.onAllNodes(isToggleable()).fetchSemanticsNodes().map { node ->
                val state = runCatching {
                    node.config[androidx.compose.ui.semantics.SemanticsProperties.ToggleableState]
                }.getOrNull()
                val txt = runCatching {
                    node.config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                        .joinToString("") { it.text }
                }.getOrNull()
                "state=$state text=$txt bounds=${node.boundsInWindow}"
            }
            java.io.File("/tmp/y3group-debug.txt").appendText(
                "[$tag] checked=${checkedToggleCount()} opened=$opened msgs=$appMessages " +
                    "texts=$texts toggles=$toggles\n")
        }
    }

    /** Invoke a semantics node's OnClick action directly (no coordinates). */
    private fun invokeOnClick(node: androidx.compose.ui.semantics.SemanticsNode): Boolean {
        val action = runCatching {
            node.config[androidx.compose.ui.semantics.SemanticsActions.OnClick]
        }.getOrNull() ?: return false
        return runCatching { action.action?.invoke() ?: false }.getOrDefault(false)
    }

    /**
     * Select a contact by invoking its row's OnClick semantics action until
     * the checked-toggle count reaches [target]. Coordinate clicks proved
     * unreliable here: the picker rows report zero-size bounds under
     * Robolectric, so injected clicks land at (0,0) instead of on the row.
     */
    private fun pickToggle(name: String, target: Int) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (checkedToggleCount() >= target) return
            val nodes = rule.onAllNodesWithText(name).fetchSemanticsNodes()
            // The contact row is the node whose merged text also carries the number.
            val row = nodes.firstOrNull { node ->
                runCatching {
                    node.config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                        .any { it.text.contains("1415555010") }
                }.getOrDefault(false)
            } ?: nodes.firstOrNull()
            if (row != null) invokeOnClick(row)
            rule.waitForIdle()
            Thread.sleep(100)
        }
        dumpState("pickToggle-$name")
        assertTrue(checkedToggleCount() >= target,
            "contact $name never selected; checked=${checkedToggleCount()}")
    }

    private fun pickBothContactsAndName() {
        // Group switch is already On, so Alice -> 2 checked, Bob -> 3 checked.
        pickToggle("Alice Y3", target = 2)
        pickToggle("Bob Y3", target = 3)
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("Y3 Crew")
        rule.waitForIdle()
    }

    private fun iqResult(req: WhatsAppProtocol.Node, content: List<WhatsAppProtocol.Node> = emptyList(), type: String = "result") =
        WhatsAppProtocol.Node(
            tag = "iq",
            attrs = mapOf("id" to (req.attrs["id"] ?: ""), "type" to type, "from" to "s.whatsapp.net"),
            content = content,
        )

    /** Answer fake-socket stanzas until [done] or the deadline passes. */
    private fun pumpWhatsApp(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!done() && System.currentTimeMillis() < deadline) {
            val nodes = fake.drainNodes()
            for (n in nodes) {
                if (n.tag != "iq") continue
                val resp = when {
                    n.attrs["xmlns"] == "w:g2" && n.getChildByTag("create") != null ->
                        iqResult(n, listOf(WhatsAppProtocol.Node(tag = "group", attrs = mapOf("id" to "120363777"))))
                    n.attrs["xmlns"] == "w:g2" ->
                        iqResult(n, listOf(WhatsAppProtocol.Node(
                            tag = "group", attrs = mapOf("subject" to "Y3 Crew", "id" to NEW_GROUP_JID),
                            content = listOf(
                                WhatsAppProtocol.Node(tag = "participant", attrs = mapOf("jid" to "14155550102@s.whatsapp.net")),
                                WhatsAppProtocol.Node(tag = "participant", attrs = mapOf("jid" to "14155550103@s.whatsapp.net")),
                            ))))
                    else -> iqResult(n)
                }
                WhatsAppClient.handleIncomingMessage(WhatsAppProtocol.encodeNode(resp))
            }
            runCatching { rule.waitForIdle() }
            Thread.sleep(20)
        }
        assertTrue(done(), "pump timed out; opened=$opened messages=$appMessages")
    }

    @Test
    fun `whatsApp not-connected create shows the notice and opens nothing`() {
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Idle
        openGroupPicker()
        selectLine("WhatsApp")
        pickBothContactsAndName()
        rule.onNodeWithText("Create").performClick()
        rule.waitUntil(15_000) { appMessages.any { it.contains("WhatsApp isn't connected") } }
        assertTrue(opened.isEmpty(), "opened=$opened")
    }

    @Test
    fun `whatsApp connected create opens the new group thread`() {
        openGroupPicker()
        selectLine("WhatsApp")
        pickBothContactsAndName()
        rule.onNodeWithText("Create").performClick()
        pumpWhatsApp { opened.isNotEmpty() }
        val thread = opened.single()
        assertEquals(CommunicateLine.WhatsApp, thread.line)
        assertEquals(NEW_GROUP_JID, thread.remoteId)
        assertTrue(thread.isGroup)
        assertEquals("Y3 Crew", thread.groupTitle)
        assertEquals(2, thread.participants.size)
        assertTrue(thread.participants.all { it.contains("1415555010") }, "${thread.participants}")
    }

    @Test
    fun `signal create opens the new group thread and persists the row`() {
        openGroupPicker()
        selectLine("Signal")
        pickBothContactsAndName()
        rule.onNodeWithText("Create").performClick()
        // Signal createGroup PUTs to the (unreachable) server best-effort and
        // still returns the local group id; the thread open is the contract.
        val deadline = System.currentTimeMillis() + 60_000
        while (opened.isEmpty() && System.currentTimeMillis() < deadline) {
            runCatching { rule.waitForIdle() }
            Thread.sleep(25)
        }
        assertTrue(opened.isNotEmpty(), "no thread opened; messages=$appMessages")
        val thread = opened.single()
        assertEquals(CommunicateLine.Signal, thread.line)
        assertTrue(thread.isGroup)
        assertTrue(thread.remoteId!!.startsWith("group:"), "remoteId=${thread.remoteId}")
        assertEquals("Y3 Crew", thread.groupTitle)
        assertEquals(2, thread.participants.size)
        val row = runBlocking { sigDb.conversationDao().getConversation(thread.remoteId!!) }
        assertNotNull(row)
        assertEquals("Y3 Crew", row.name)
        assertTrue(row.isGroup)
    }

    @Test
    fun `rcs plaintext create hosts the focus and opens the group thread`() {
        openGroupPicker()
        selectLine("RCS")
        pickBothContactsAndName()
        rule.onNodeWithText("Create").performClick()
        val deadline = System.currentTimeMillis() + 30_000
        while (opened.isEmpty() && System.currentTimeMillis() < deadline) {
            runCatching { rule.waitForIdle() }
            Thread.sleep(25)
        }
        assertTrue(opened.isNotEmpty(), "no thread opened; messages=$appMessages")
        val thread = opened.single()
        assertEquals(CommunicateLine.Rcs, thread.line)
        assertTrue(thread.isGroup)
        assertTrue(thread.address.startsWith("rcs-group:"), "address=${thread.address}")
        assertTrue(thread.address.contains("14155550102") && thread.address.contains("14155550103"),
            "address=${thread.address}")
        // The plaintext branch hosted a conference focus for the group and
        // REFERred both members to it.
        val focusUri = RcsSessionManager.hostedFocusFor(thread.address)
        assertNotNull(focusUri, "no hosted focus for ${thread.address}")
        assertEquals(setOf("+14155550102", "+14155550103"),
            RcsSessionManager.focusMembers(focusUri))
        assertTrue(referSent.any { it.first.startsWith("REFER") }, "sent=${referSent.map { it.first }}")
    }

    @Test
    fun `rcs encrypted create still opens the thread when MLS setup cannot start`() {
        openGroupPicker()
        selectLine("RCS")
        if (RustMlsCrypto.isAvailable) {
            // Toggleables: Group switch, encrypted switch, contact checkboxes.
            // The encrypted switch is the second one in composition order.
            val toggles = rule.onAllNodes(isToggleable())
            assertTrue(toggles.fetchSemanticsNodes().size >= 2)
            toggles[1].performClick()
            rule.waitForIdle()
        }
        pickBothContactsAndName()
        rule.onNodeWithText("Create").performClick()
        val deadline = System.currentTimeMillis() + 30_000
        while (opened.isEmpty() && System.currentTimeMillis() < deadline) {
            runCatching { rule.waitForIdle() }
            Thread.sleep(25)
        }
        assertTrue(opened.isNotEmpty(), "no thread opened; messages=$appMessages")
        val thread = opened.single()
        assertEquals(CommunicateLine.Rcs, thread.line)
        assertTrue(thread.isGroup)
        if (RustMlsCrypto.isAvailable) {
            // Encrypted branch ran: with no local E.164 on the host the setup
            // fails closed, the failure notice is posted, and no plaintext
            // focus is hosted as a silent fallback.
            rule.waitUntil(15_000) { appMessages.any { it.contains("Couldn't start encrypted chat") } }
            assertNull(RcsSessionManager.hostedFocusFor(thread.address))
        } else {
            // Without the MLS library the toggle is hidden and the lambda
            // takes the plaintext branch (documented dev-gate behaviour).
            assertNotNull(RcsSessionManager.hostedFocusFor(thread.address))
        }
    }
}
