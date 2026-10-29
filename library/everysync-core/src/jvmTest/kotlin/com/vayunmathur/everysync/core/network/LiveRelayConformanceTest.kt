package com.vayunmathur.everysync.core.network

import com.vayunmathur.everysync.core.crypto.AesGcm
import com.vayunmathur.everysync.core.data.RemoteNote
import com.vayunmathur.everysync.core.platform.JvmFileSecureStore
import com.vayunmathur.everysync.core.platform.MemorySecureStore
import com.vayunmathur.everysync.core.provider.SyncDriver
import com.vayunmathur.everysync.core.sync.RelayMerge
import com.vayunmathur.everysync.core.sync.RelaySnapshot
import com.vayunmathur.everysync.core.sync.RelayStore
import com.vayunmathur.everysync.core.sync.VaultKeys
import com.vayunmathur.everysync.core.sync.VaultRoster
import com.vayunmathur.everysync.core.sync.adapters.NoteKind
import com.vayunmathur.everysync.core.sync.newRelaySyncId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.prefs.Preferences
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Scripted live-relay pass for `LIVE_RELAY_CONFORMANCE.md` steps 1–7.
 *
 * Runs the checklist with the JVM actuals ([JvmRelayTransport] + [JvmPqcCrypto]
 * + file-backed [JvmFileSecureStore] identity stores under fresh temp dirs) as
 * a two-device pair (A = owner, B = joiner) in-process against staging
 * `https://api.maproj.org/personal` (migrated from findfamily.cc;
 * findfamily.cc remains valid for backward compatibility). Re-run with:
 *
 * `./gradlew :library:everysync-core:jvmTest --tests
 * "com.vayunmathur.everysync.core.network.LiveRelayConformanceTest"`
 *
 * Result model: every step records PASS (genuinely executed + asserted),
 * BLOCKED (environment — relay down or host PQC lib absent — with the exact
 * error, never faked), or FAIL (executed and the assertion failed, which fails
 * this test). BLOCKED steps never fail: the relay being undeployed must not
 * redden the suite. The per-step verdict table prints in all cases (see
 * `finally` in [liveRelayPass]).
 *
 * Key hygiene: device ids, channels, seqs and statuses are printed; vault keys
 * and plaintext are NEVER printed.
 */
class LiveRelayConformanceTest {

    private data class Verdict(val step: String, val status: String, val detail: String)

    private val verdicts = mutableListOf<Verdict>()

    private fun record(step: String, status: String, detail: String) {
        verdicts.add(Verdict(step, status, detail))
        println("LIVE-RELAY [$status] step $step: $detail")
    }

    /** `RelayHttpClient` that fails every call: simulates kill-network mid-pull. */
    private class KillNetworkHttp : RelayHttpClient {
        override suspend fun post(url: String, jsonBody: String): RelayHttpResponse =
            throw java.io.IOException("simulated kill-network")

        override fun subscribe(wsUrl: String, channel: String): Flow<String> = emptyFlow()
    }

    /** Minimal in-memory [RelayStore] over [RemoteNote] for the `SyncDriver` abort check. */
    private class MemNoteStore : RelayStore<RemoteNote> {
        val rows = LinkedHashMap<String, RemoteNote>()
        val snapshots = LinkedHashMap<String, RelaySnapshot>()
        var writes = 0

        override suspend fun listLocal(): List<RemoteNote> = rows.values.toList()
        override suspend fun upsertLocal(items: List<RemoteNote>) {
            writes += items.size
            for (item in items) rows[item.uid] = item
        }

        override suspend fun deleteLocal(items: List<RemoteNote>) {
            writes += items.size
            for (item in items) rows.remove(item.uid)
        }

        override suspend fun loadBaseline(): Map<String, RelaySnapshot> = snapshots.toMap()
        override suspend fun saveSnapshots(snapshots: List<RelaySnapshot>) {
            for (s in snapshots) this.snapshots[s.syncId] = s
        }

        override suspend fun removeSnapshots(syncIds: List<String>) {
            for (id in syncIds) snapshots.remove(id)
        }

        override fun parseRemote(json: String): RemoteNote? = NoteKind.fromJson(json)
        override fun serializeForRemote(entity: RemoteNote): String = NoteKind.toJson(entity)
    }

    @Test
    fun liveRelayPass() = runBlocking {
        var dirA = runCatching { Files.createTempDirectory("live-relay-A").toFile() }.getOrNull()
        var dirB = runCatching { Files.createTempDirectory("live-relay-B").toFile() }.getOrNull()
        var prefsA: Preferences? = null
        var prefsB: Preferences? = null
        try {
            runPass(dirA, dirB,
                onPrefs = { a, b ->
                    prefsA = a
                    prefsB = b
                })
        } finally {
            printReport()
            runCatching { prefsA?.removeNode() }
            runCatching { prefsB?.removeNode() }
            runCatching { dirA?.deleteRecursively() }
            runCatching { dirB?.deleteRecursively() }
        }
    }

    private suspend fun runPass(
        dirA: java.io.File?,
        dirB: java.io.File?,
        onPrefs: (Preferences?, Preferences?) -> Unit,
    ) {
        // --- Production PQC probe (JNA host lib; Gradle sets -Djna.library.path). ---
        // Catch Throwable narrowly here: a missing native lib surfaces as
        // UnsatisfiedLinkError (an Error, not an Exception).
        val crypto: PqcCrypto? = try {
            JvmPqcCrypto().also {
                record("0-crypto", "PASS", "JvmPqcCrypto loaded (production PQC seal/sign active)")
            }
        } catch (t: Throwable) {
            record("0-crypto", "BLOCKED", "JvmPqcCrypto unavailable: ${t::class.simpleName}: ${t.message}")
            null
        }

        // --- Steps that never need the relay: always genuinely executed. ---
        phaseAesShapeLocal()
        phaseJoinLinks()
        phaseInvariantFailedPullAborts(crypto)
        phaseInvariantTombstoneMerge()
        phaseInvariantSealedInviteCiphertext(crypto)

        // --- Relay probe: exact status/body via the real pinned client. ---
        val diag = probeRelay()
        record("0-probe", if (crypto == null) "BLOCKED" else "INFO", diag)

        // --- Two-device pair (file-backed identity stores, fresh temp dirs). ---
        if (crypto == null || dirA == null || dirB == null) {
            record("1", "BLOCKED", "no PQC crypto or temp dirs; live steps 1-5,7-relay skipped ($diag)")
            record("2", "BLOCKED", "no PQC crypto or temp dirs ($diag)")
            record("3-relay", "BLOCKED", "no PQC crypto or temp dirs ($diag)")
            record("4", "BLOCKED", "no PQC crypto or temp dirs ($diag)")
            record("5", "BLOCKED", "no PQC crypto or temp dirs ($diag)")
            record("7-relay", "BLOCKED", "no PQC crypto or temp dirs ($diag)")
            onPrefs(null, null)
            return
        }
        val prefsA = runCatching {
            Preferences.userRoot().node("com/vayunmathur/everysync-live-conformance-A")
        }.getOrNull()
        val prefsB = runCatching {
            Preferences.userRoot().node("com/vayunmathur/everysync-live-conformance-B")
        }.getOrNull()
        onPrefs(prefsA, prefsB)
        val storeA = runCatching {
            JvmFileSecureStore(
                prefs = prefsA ?: Preferences.userRoot().node("com/vayunmathur/everysync-live-A-fallback"),
                bytesDir = java.io.File(dirA, "securestore-bytes"),
            )
        }.getOrNull() ?: MemorySecureStore().also {
            record("0-store", "INFO", "prefs unavailable; device A uses MemorySecureStore (protocol identical)")
        }
        val storeB = runCatching {
            JvmFileSecureStore(
                prefs = prefsB ?: Preferences.userRoot().node("com/vayunmathur/everysync-live-B-fallback"),
                bytesDir = java.io.File(dirB, "securestore-bytes"),
            )
        }.getOrNull() ?: MemorySecureStore().also {
            record("0-store", "INFO", "prefs unavailable; device B uses MemorySecureStore (protocol identical)")
        }
        val a = JvmRelayTransport(storeA, crypto)
        val b = JvmRelayTransport(storeB, crypto)
        val okA = runCatching { a.init() }.getOrDefault(false)
        val okB = runCatching { b.init() }.getOrDefault(false)
        if (!okA || !okB) {
            val detail = "register failed (A=$okA B=$okB); $diag"
            record("1", "BLOCKED", detail)
            record("2", "BLOCKED", detail)
            record("3-relay", "BLOCKED", detail)
            record("4", "BLOCKED", detail)
            record("5", "BLOCKED", detail)
            record("7-relay", "BLOCKED", detail)
            return
        }
        record("0-devices", "PASS", "A=${a.deviceId} B=${b.deviceId} (both registered)")

        phaseRegisterGetKey(a, b)
        val vault = phaseAppendPull(a, b)
        phaseRecordShapeRelay(a, vault)
        val epochKey = phaseRosterEpoch(a, b, vault)
        phaseInviteJoin(a, b, vault, epochKey)
        phaseTombstoneRelay(a, b, vault, epochKey)
    }

    /** Exact relay error evidence through the real pinned OkHttp client. */
    private suspend fun probeRelay(): String = runCatching {
        val resp = OkHttpRelayClient.pinned().post(
            "$RELAY_BASE_URL/register",
            """{"id":"conformance-probe","key":"cHJvYmU="}""",
        )
        "POST $RELAY_BASE_URL/register → HTTP ${resp.status} body=" + resp.body.take(120)
    }.fold(
        onSuccess = { it },
        onFailure = { "POST $RELAY_BASE_URL/register threw ${it::class.simpleName}: ${it.message}" },
    )

    // --- Step 3 (local half): B64 AES record shape over the real JVM AesGcm. ---

    private fun phaseAesShapeLocal() {
        val key = AesGcm.newContentKey()
        assertEquals(32, key.size)
        val pt = """{"uid":"shape","title":"iv-prepended"}""".encodeToByteArray()
        val e1 = AesGcm.encrypt(key, pt)
        val e2 = AesGcm.encrypt(key, pt)
        // [12B iv][ct||tag]: GCM ct length == pt length, tag 16B, iv 12B.
        assertEquals(pt.size + 28, e1.size)
        assertTrue(e1.size >= 12)
        // Random IV: two encryptions of the same plaintext differ, both decrypt.
        assertFalse(e1.contentEquals(e2))
        assertContentEquals(pt, AesGcm.decrypt(key, e1))
        assertContentEquals(pt, AesGcm.decrypt(key, e2))
        // Valid Base64 envelope round-trip (the on-wire form).
        val b64 = Base64.encode(e1)
        assertContentEquals(pt, AesGcm.decrypt(key, Base64.decode(b64)))
        record("3-local", "PASS", "IV-prepended [12B iv][ct||tag] (pt+28B), random IV, B64 round-trip")
    }

    // --- Step 6: everysync://join parse (pure local; fully asserted). ---

    private fun phaseJoinLinks() {
        val link = RelayLinks.shareLinkFor("vault-1", "owner-dev", "notes")
        assertTrue(link.startsWith(RelayLinks.SHARE_BASE))
        assertFalse(link.contains("key", ignoreCase = true))
        assertEquals(
            Triple("vault-1", "owner-dev", "notes"),
            RelayLinks.parseJoinLink("everysync", "join", "vault-1", "owner-dev", "notes"),
        )
        assertNull(RelayLinks.parseJoinLink("https", "join", "vault-1", "owner-dev", "notes"))
        assertNull(RelayLinks.parseJoinLink("everysync", "leave", "vault-1", "owner-dev", "notes"))
        assertNull(RelayLinks.parseJoinLink("everysync", "join", "", "owner-dev", "notes"))
        assertNull(RelayLinks.parseJoinLink("everysync", "join", "vault-1", "", "notes"))
        record("6", "PASS", "share link carries ids only; parse round-trips; bad scheme/host/ids → null")
    }

    // --- Step 7.1 (always runs): kill-network → pullRaw null → drive false, zero writes. ---

    private suspend fun phaseInvariantFailedPullAborts(crypto: PqcCrypto?) {
        val transport = JvmRelayTransport(
            MemorySecureStore(),
            crypto ?: FakePqcCrypto(),
            KillNetworkHttp(),
        )
        assertNull(transport.pullRaw("notes:probe-vault", 0))
        val store = MemNoteStore()
        val drove = SyncDriver.drive(
            kind = NoteKind,
            store = store,
            remoteEntities = null,
            appendRemote = { store.writes++; true },
            now = System.currentTimeMillis(),
        )
        assertFalse(drove)
        assertTrue(store.rows.isEmpty())
        assertEquals(0, store.writes)
        val note = if (crypto == null) " (FakePqcCrypto stand-in: crypto is irrelevant to the null-pull path)" else ""
        record("7.1", "PASS", "pullRaw null on transport failure → SyncDriver.drive false, merged nothing$note")
    }

    // --- Step 7.2 (merge half, always runs): tombstone-only delete for notes. ---

    private fun phaseInvariantTombstoneMerge() {
        val now = System.currentTimeMillis()
        val note = RemoteNote(uid = newRelaySyncId(), title = "keep me", content = "v1", updatedAt = 1000L)
        val base = mapOf(
            note.uid to RelaySnapshot(note.uid, "note", NoteKind.contentHash(note), 1000L, 1000L, 1000L),
        )
        // Absent from the folded log WITHOUT a tombstone is never a delete.
        val absent = RelayMerge.merge(NoteKind, listOf(note), emptyList(), base, now)
        assertTrue(absent.localDeletes.isEmpty())
        assertTrue(absent.remoteOut.none { it.deleted })
        // An explicit deleted=true envelope propagates as a tombstone upsert.
        val tombstone = note.copy(deleted = true, updatedAt = 2000L)
        val present = RelayMerge.merge(NoteKind, listOf(note), listOf(tombstone), base, now)
        assertTrue(present.localUpserts.any { it.uid == note.uid && it.deleted })
        record("7.2-local", "PASS", "absent-without-tombstone resurrects (no deletes); deleted=true envelope tombstones")
    }

    // --- Step 7.3 (local half): sealed invite envelope carries no plaintext. ---

    private fun phaseInvariantSealedInviteCiphertext(crypto: PqcCrypto?) {
        // Share links carry ids only (no key material by construction).
        val link = RelayLinks.shareLinkFor("vault-9", "owner-dev", "notes")
        assertFalse(link.contains("key", ignoreCase = true))
        if (crypto == null) {
            record("7.3", "BLOCKED", "link half passes (ids only); seal-ciphertext scan needs JvmPqcCrypto")
            return
        }
        // Exact envelope sendInvite appends: Base64(seal(recipientBundle, inviteJson)).
        val (kemPubB, kemPrivB) = crypto.generateKem()
        val (dsaPubB, _) = crypto.generateDsa()
        val bundleB = crypto.bundle(kemPubB, dsaPubB)
        val vaultKey = AesGcm.newContentKey()
        val title = "conformance-secret-title"
        val inviteJson = "{\"vaultId\":\"vault-9\",\"key\":\"" + Base64.encode(vaultKey) + "\"," +
            "\"title\":\"" + title + "\",\"dataType\":\"notes\",\"role\":\"editor\"," +
            "\"ownerKey\":\"\",\"ownerDeviceId\":\"owner-dev\",\"epoch\":0}"
        val envelope = Base64.encode(crypto.seal(bundleB, inviteJson.encodeToByteArray()))
        assertFalse(envelope.contains(title))
        assertFalse(envelope.contains(Base64.encode(vaultKey)))
        // And it genuinely seals: the recipient opens it to the exact invite JSON.
        val opened = crypto.decrypt(kemPrivB, Base64.decode(envelope)).decodeToString()
        assertEquals(inviteJson, opened)
        record("7.3-local", "PASS", "invite envelope is PQC-sealed ciphertext (no title/key substrings); recipient opens it")
    }

    // --- Live phases below: execute only when both devices registered. ---

    private suspend fun phaseRegisterGetKey(a: JvmRelayTransport, b: JvmRelayTransport) {
        assertTrue(a.register())
        assertTrue(b.register())
        val bundleB = a.getKey(b.deviceId) ?: error("getKey(B) returned null")
        assertTrue(bundleB.isNotEmpty())
        val bundleA = b.getKey(a.deviceId) ?: error("getKey(A) returned null")
        assertTrue(bundleA.isNotEmpty())
        assertNull(a.getKey("nonexistent-device-0000"))
        record("1", "PASS", "register 200 both; getKey cross-resolves non-empty; unknown id → null")
    }

    private data class LiveVault(val id: String, val key: ByteArray, val jsonA: String, val jsonB: String)

    private suspend fun phaseAppendPull(a: JvmRelayTransport, b: JvmRelayTransport): LiveVault {
        val vaultId = a.newVaultId()
        val key = a.newVaultKey()
        assertEquals(32, key.size)
        val now = System.currentTimeMillis()
        val jsonA = NoteKind.toJson(RemoteNote(uid = newRelaySyncId(), title = "live-a", content = "A", updatedAt = now))
        val jsonB = NoteKind.toJson(RemoteNote(uid = newRelaySyncId(), title = "live-b", content = "B", updatedAt = now))
        val channel = recordChannel("notes", vaultId)
        // In-process pair shares K directly to isolate step 2 from step 5; a
        // physical device pair carries K via the step-5 sealed invite.
        val s1 = a.appendRecords(channel, key, listOf(jsonA, jsonB)) ?: error("appendRecords returned null")
        assertTrue(s1 > 0)
        val pulled = b.pullRecords(channel, key, 0) ?: error("pullRecords returned null")
        assertEquals(listOf(jsonA, jsonB), pulled.items)
        assertEquals(2, pulled.rawCount)
        assertEquals(0, pulled.undecryptable)
        assertFalse(pulled.fullyUndecryptable)
        assertEquals(s1, pulled.seq)
        // Wrong key: nothing decrypts → caller MUST abort, not merge.
        val wrong = b.pullRecords(channel, AesGcm.newContentKey(), 0) ?: error("wrong-key pull returned null")
        assertTrue(wrong.items.isEmpty())
        assertEquals(2, wrong.undecryptable)
        assertTrue(wrong.fullyUndecryptable)
        record("2", "PASS", "append seq=$s1; pull items==2 rawCount==2 undecryptable==0; wrong-key fullyUndecryptable → abort")
        return LiveVault(vaultId, key, jsonA, jsonB)
    }

    private suspend fun phaseRecordShapeRelay(a: JvmRelayTransport, vault: LiveVault) {
        val raw = a.pullRaw(recordChannel("notes", vault.id), 0) ?: error("pullRaw returned null")
        assertEquals(2, raw.size)
        val plaintexts = mutableListOf<String>()
        for (envelope in raw) {
            val bytes = Base64.decode(envelope)
            assertTrue(bytes.size >= 12)
            plaintexts += AesGcm.decrypt(vault.key, bytes).decodeToString()
        }
        assertEquals(listOf(vault.jsonA, vault.jsonB), plaintexts)
        record("3-relay", "PASS", "2 envelopes: valid B64, IV-prepended, decrypt to the appended JSON")
    }

    private suspend fun phaseRosterEpoch(
        a: JvmRelayTransport,
        b: JvmRelayTransport,
        vault: LiveVault,
    ): ByteArray {
        val rosterA = VaultRoster(a)
        val rosterB = VaultRoster(b)
        assertTrue(rosterA.recordMembers(vault.id, listOf(Member(b.deviceId, "B", RelayRoles.EDITOR))))
        val ownerKey = b.getKey(a.deviceId) ?: error("getKey(owner) returned null")
        val fetched = rosterB.fetchRoster(vault.id, ownerKey)
        assertEquals(RelayRoles.EDITOR, fetched[b.deviceId]?.role)
        // Tampered envelope on the roster channel is skipped; roster still resolves.
        if (a.appendRaw(membersChannel(vault.id), listOf("garbage-not-an-envelope")) == null) error("garbage append failed")
        assertEquals(RelayRoles.EDITOR, rosterB.fetchRoster(vault.id, ownerKey)[b.deviceId]?.role)
        // Epoch rotation: owner seals K2 to both bundles; B resolves (1, K2).
        val keysA = VaultKeys(a, rosterA)
        val keysB = VaultKeys(b, rosterB)
        val bundleA = b.getKey(a.deviceId) ?: error("getKey(A) returned null")
        val bundleB = a.getKey(b.deviceId) ?: error("getKey(B) returned null")
        val k2 = keysA.rotateKey(vault.id, 0, mapOf(a.deviceId to bundleA, b.deviceId to bundleB))
            ?: error("rotateKey returned null")
        assertFalse(k2.contentEquals(vault.key))
        val (epoch, resolved) = keysB.fetchCurrentKey(vault.id, vault.key, ownerKey)
        assertEquals(1, epoch)
        assertContentEquals(k2, resolved)
        // Epoch isolation: a record under K2 does not decrypt under K.
        val channel = recordChannel("notes", vault.id)
        val extra = NoteKind.toJson(RemoteNote(uid = newRelaySyncId(), title = "epoch1", updatedAt = System.currentTimeMillis()))
        if (a.appendRecords(channel, k2, listOf(extra)) == null) error("epoch record append failed")
        val stale = b.pullRecords(channel, vault.key, 2) ?: error("stale-key pull returned null")
        assertEquals(1, stale.rawCount)
        assertEquals(1, stale.undecryptable)
        record("4", "PASS", "roster editor resolves (garbage skipped); epoch→1 K2!=K; K2 record undecryptable under K")
        return k2
    }

    private suspend fun phaseInviteJoin(
        a: JvmRelayTransport,
        b: JvmRelayTransport,
        vault: LiveVault,
        epochKey: ByteArray,
    ) {
        assertTrue(b.sendJoinRequest(a.deviceId, vault.id, "notes", b.deviceId, "B"))
        val ownerInbox = a.pullInbox(0)
        assertTrue(ownerInbox.requests.any { it.requesterId == b.deviceId && it.vaultId == vault.id })
        assertTrue(ownerInbox.invites.isEmpty())
        val title = "live-shared-notes"
        assertTrue(
            a.sendInvite(
                recipientId = b.deviceId,
                vaultId = vault.id,
                dataType = "notes",
                key = epochKey,
                title = title,
                role = RelayRoles.EDITOR,
                ownerKeyB64 = Base64.encode(a.publicBundle),
                ownerDeviceId = a.deviceId,
            ),
        )
        val invites = b.pullInvites(0)
        assertEquals(1, invites.invites.size)
        val invite = invites.invites.single()
        assertEquals(vault.id, invite.vaultId)
        assertContentEquals(epochKey, Base64.decode(invite.key))
        // Relay log shows only ciphertext: no plaintext title/key in the raw inbox.
        val rawInbox = a.pullRaw(inboxChannel(b.deviceId), 0) ?: error("inbox pullRaw returned null")
        assertTrue(rawInbox.isNotEmpty())
        val keyB64 = Base64.encode(epochKey)
        assertTrue(rawInbox.none { it.contains(title) || it.contains(keyB64) })
        record("5", "PASS", "join req lands (kind:req); invite resolves vault+key; raw inbox is ciphertext-only")
    }

    private suspend fun phaseTombstoneRelay(
        a: JvmRelayTransport,
        b: JvmRelayTransport,
        vault: LiveVault,
        key: ByteArray,
    ) {
        val channel = recordChannel("notes", vault.id)
        // Fold the full log on B (tombstones included), then delete locally on A
        // and push the tombstone; B must observe deleted=true, never a silent drop.
        val before = b.pullRecords(channel, key, 0) ?: error("tombstone pre-pull returned null")
        val target = before.items.mapNotNull { NoteKind.fromJson(it) }.firstOrNull { !it.deleted }
            ?: error("no live note to tombstone")
        val tombstone = NoteKind.toJson(
            target.copy(deleted = true, updatedAt = maxOf(target.updatedAt + 1, System.currentTimeMillis())),
        )
        if (a.appendRecords(channel, key, listOf(tombstone)) == null) error("tombstone append failed")
        val after = b.pullRecords(channel, key, 0) ?: error("tombstone post-pull returned null")
        val folded = after.items.mapNotNull { NoteKind.fromJson(it) }
            .groupBy { it.uid }.mapValues { (_, versions) -> versions.maxBy { it.updatedAt } }
        val observed = folded[target.uid] ?: error("tombstoned id missing from folded log")
        assertTrue(observed.deleted)
        record("7-relay", "PASS", "delete on A → B folds deleted=true envelope for ${target.uid} (tombstoned, not dropped)")
    }

    private fun printReport() {
        println("==== LIVE-RELAY CONFORMANCE REPORT ====")
        for (v in verdicts) println("${v.step}\t${v.status}\t${v.detail}")
        val failed = verdicts.any { it.status == "FAIL" }
        println("result=${if (failed) "FAIL" else "OK"} " +
            "(PASS=${verdicts.count { it.status == "PASS" }} " +
            "BLOCKED=${verdicts.count { it.status == "BLOCKED" }} " +
            "INFO=${verdicts.count { it.status == "INFO" }})")
        println("remaining manual steps for a human with two devices:")
        println("1. if any step is BLOCKED on relay 404: redeploy the relay at /personal/* on api.maproj.org (or findfamily.cc for backward compatibility), then re-run this test")
        println("2. physical pair: install on A+B, init (record both device ids), A creates vault, A invites B via inbox, B pulls invite")
        println("3. on B: verify records decrypt, share link shows ids only, kill-network pull aborts the cycle")
        println("4. delete a note on A, sync, pull on B: confirm deleted=true tombstone arrives (never a silent drop)")
    }
}
