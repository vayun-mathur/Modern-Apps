package com.vayunmathur.passwords.domain

import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.data.SyncSnapshot
import com.vayunmathur.passwords.platform.MenuUiState
import com.vayunmathur.passwords.platform.PasswordEditUiState
import com.vayunmathur.passwords.platform.PasswordUiState
import com.vayunmathur.passwords.sync.KdbxSyncResult
import com.vayunmathur.passwords.sync.PasswordPasskeyPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DataClassesExtraTest {

    @Test fun mergeResultOrderingAndPasskeysFor() {
        val host = Password(id = 1, name = "GitHub", username = "octo", websites = listOf("github.com"), syncId = "host")
        val late = Passkey(id = 2, rpId = "github.com", userName = "octo", creationTime = 200, syncId = "pk-b")
        val early = Passkey(id = 3, rpId = "github.com", userName = "octo", creationTime = 100, syncId = "pk-a")
        val sameTime = Passkey(id = 4, rpId = "github.com", userName = "octo", creationTime = 100, syncId = "pk-0")
        val result = mergeCredentials(listOf(host), listOf(late, sameTime, early))
        // Attached passkeys sort by creation time, then syncId as a stable tiebreak.
        assertEquals(listOf("pk-0", "pk-a", "pk-b"), result.passkeysByPasswordSyncId["host"]!!.map { it.syncId })
        assertEquals(result.passkeysByPasswordSyncId["host"], passkeysFor(host, listOf(host), listOf(late, early, sameTime)))
        // A password that does not exist in the list has no merged passkeys.
        assertEquals(emptyList(), passkeysFor(Password(syncId = "ghost"), listOf(host), listOf(early)))
        assertEquals(MergeResult(mapOf("host" to listOf(early)), emptyList()).hashCode(),
            MergeResult(mapOf("host" to listOf(early.copy())), emptyList()).hashCode())
    }

    @Test fun uiStateDataClassesBehaveAsValues() {
        val now = { 42L }
        val menu = MenuUiState(passwords = listOf(Password(name = "A")), loading = true, now = now)
        assertEquals(menu, menu.copy())
        assertEquals(menu.hashCode(), menu.copy().hashCode())
        assertNotEquals(menu, menu.copy(loading = false))
        assertTrue(menu.toString().contains("loading=true"))
        assertEquals(42L, menu.now())

        val detail = PasswordUiState(password = Password(name = "B"), now = now)
        assertEquals(detail, detail.copy())
        assertNotEquals(detail, detail.copy(password = Password(name = "C")))

        val edit = PasswordEditUiState(saved = Password(name = "S"), draft = null)
        assertEquals(edit, edit.copy())
        assertNotEquals(edit, edit.copy(draft = Password()))
        assertTrue(edit.toString().contains("draft=null"))
    }

    @Test fun syncResultAndSnapshotValues() {
        assertEquals(KdbxSyncResult.Success(1, 2, 3, 4), KdbxSyncResult.Success(1, 2, 3, 4).copy())
        assertNotEquals(KdbxSyncResult.Success(1, 2, 3, 4), KdbxSyncResult.Success(1, 2, 3, 5))
        assertEquals(KdbxSyncResult.Quarantined(3), KdbxSyncResult.Quarantined(3).copy(count = 3))
        assertEquals("Quarantined(count=3)", KdbxSyncResult.Quarantined(3).toString())
        assertEquals(KdbxSyncResult.Error("x"), KdbxSyncResult.Error("x").copy())

        val snap = SyncSnapshot("s", SyncSnapshot.KIND_PASSKEY, "h", 1, 2, 3)
        assertEquals(snap, snap.copy())
        assertEquals(snap.hashCode(), snap.copy().hashCode())
        assertNotEquals(snap, snap.copy(kind = SyncSnapshot.KIND_PASSWORD))
        assertEquals("s", snap.component1())
        assertEquals("passkey", snap.component2())
        assertEquals("h", snap.component3())
        assertEquals(1L, snap.component4())
        assertEquals(2L, snap.component5())
        assertEquals(3L, snap.component6())
    }

    @Test fun passkeyPayloadDataClassSemantics() {
        val payload = PasswordPasskeyPayload(
            id = 1, rpId = "example.com", credentialId = "cred", privateKeyBase64 = "AQID",
            signCount = 2, syncId = "pk-1", updatedAt = 9,
        )
        assertEquals(payload, payload.copy())
        assertEquals(payload.hashCode(), payload.copy().hashCode())
        assertNotEquals(payload, payload.copy(signCount = 3))
        assertTrue(payload.toString().contains("example.com"))
        val changed = payload.copy(rpId = "other.example", signCount = 7)
        assertEquals("other.example", changed.rpId)
        assertEquals(7, changed.signCount)
        assertEquals("cred", changed.credentialId) // untouched fields survive copy
    }

    @Test fun relayEnvelopeAndPayloadDestructuring() {
        val payload = PasswordPasskeyPayload(
            id = 1, rpId = "r", rpName = "R", credentialId = "c", userId = "u",
            userName = "n", userDisplayName = "d", privateKeyBase64 = "AQ==",
            creationTime = 1, lastUsedTime = 2, signCount = 3,
            linkedPasswordSyncId = "link", syncId = "s", updatedAt = 4,
        )
        assertEquals(1L, payload.component1())
        assertEquals("r", payload.component2())
        assertEquals("R", payload.component3())
        assertEquals("c", payload.component4())
        assertEquals("u", payload.component5())
        assertEquals("n", payload.component6())
        assertEquals("d", payload.component7())
        assertEquals("AQ==", payload.component8())
        assertEquals(1L, payload.component9())
        assertEquals(2L, payload.component10())
        assertEquals(3, payload.component11())
        assertEquals("link", payload.component12())
        assertEquals("s", payload.component13())
        assertEquals(4L, payload.component14())

        val relay = com.vayunmathur.passwords.sync.RelayPasswordJson(
            uid = "u1", isPasskey = true, name = "N", username = "un", email = "e",
            password = "p", note = "nt", totpSecret = "ts", websites = listOf("w"),
            rpId = "rp", rpName = "rpn", credentialId = "cid", userId = "uid",
            userName = "una", userDisplayName = "udn", privateKeyB64 = "a2V5",
            creationTime = 5, lastUsedTime = 6, signCount = 7,
            linkedPasswordSyncId = "lp", deleted = true, updatedAt = 8,
        )
        assertEquals("u1", relay.component1())
        assertEquals(true, relay.component2())
        assertEquals("N", relay.component3())
        assertEquals("un", relay.component4())
        assertEquals("e", relay.component5())
        assertEquals("p", relay.component6())
        assertEquals("nt", relay.component7())
        assertEquals("ts", relay.component8())
        assertEquals(listOf("w"), relay.component9())
        assertEquals("rp", relay.component10())
        assertEquals("rpn", relay.component11())
        assertEquals("cid", relay.component12())
        assertEquals("uid", relay.component13())
        assertEquals("una", relay.component14())
        assertEquals("udn", relay.component15())
        assertEquals("a2V5", relay.component16())
        assertEquals(5L, relay.component17())
        assertEquals(6L, relay.component18())
        assertEquals(7, relay.component19())
        assertEquals("lp", relay.component20())
        assertEquals(true, relay.component21())
        assertEquals(8L, relay.component22())
        assertEquals(relay, relay.copy())
        assertNotEquals(relay, relay.copy(uid = "other"))
    }
}
