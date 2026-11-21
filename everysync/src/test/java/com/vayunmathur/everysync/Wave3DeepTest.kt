package com.vayunmathur.everysync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.everysync.auth.AccountConfig
import com.vayunmathur.everysync.auth.AccountStore
import com.vayunmathur.everysync.auth.TokenStore
import com.vayunmathur.everysync.data.RemoteNote
import com.vayunmathur.everysync.data.RemotePassword
import com.vayunmathur.everysync.data.RemoteTodo
import com.vayunmathur.everysync.data.VaultMeta
import com.vayunmathur.everysync.data.VaultStore
import com.vayunmathur.everysync.data.sink.NoteSink
import com.vayunmathur.everysync.data.sink.PasswordSink
import com.vayunmathur.everysync.data.sink.TodoIpcSink
import com.vayunmathur.everysync.domain.format.ICalendar
import com.vayunmathur.everysync.network.remote.RelaySync
import com.vayunmathur.everysync.platform.EverySyncViewModel
import com.vayunmathur.everysync.sync.RelayMerge
import com.vayunmathur.everysync.sync.RelaySnapshot
import com.vayunmathur.everysync.sync.SnapshotKeyStore
import com.vayunmathur.everysync.sync.SnapshotStore
import com.vayunmathur.everysync.sync.adapters.CalendarKind
import com.vayunmathur.everysync.sync.adapters.ContactKind
import com.vayunmathur.everysync.sync.adapters.NoteKind
import com.vayunmathur.everysync.sync.adapters.PasswordKind
import com.vayunmathur.everysync.sync.adapters.TodoKind
import com.vayunmathur.everysync.data.RemoteContact
import com.vayunmathur.everysync.data.RemoteEvent
import com.vayunmathur.everysync.sync.CalendarRelayStore
import com.vayunmathur.everysync.sync.ContactRelayStore
import java.util.TimeZone
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deep paths left uncovered: TZID/floating/invalid iCalendar times, the
 * soft-delete pull-back in RelayMerge, per-type snapshots/restores, and a
 * joined (non-owner) vault adopting its owner-signed title and roster.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class Wave3DeepTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app
    private val relay get() = RelaySuccessTest.sharedRelay
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        relay.install()
        relay.transportDown = false
        relay.httpStatus = 200
        FakeIpcProvider.install(NoteSink.AUTHORITY, PasswordSink.AUTHORITY, TodoIpcSink.AUTHORITY)
        val softwareKey = SecretKeySpec(ByteArray(32) { (it * 17 + 9).toByte() }, "AES")
        com.vayunmathur.everysync.data.VaultKeyStore.secretKeyForTest = softwareKey
        SnapshotKeyStore.secretKeyForTest = softwareKey
        TokenStore.getInstance(app).secretKeyForTest = softwareKey
    }
    @AfterTest fun tearDown() { Dispatchers.resetMain(); relay.uninstall() }

    private fun await(deadlineMs: Long = 20_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(25)
        }
        return condition()
    }

    // ---- ICalendar time forms ----

    @Test fun tzidFloatingInvalidAndBlankForms() {
        val tzText = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:tz1\r\n" +
            "DTSTART;TZID=America/New_York:20240115T120000\r\n" +
            "DTEND;TZID=America/New_York:20240115T130000\r\n" +
            "SUMMARY:Zoned\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val zoned = ICalendar.parse(tzText, "cal").single()
        assertEquals("America/New_York", zoned.timezone)
        assertEquals(false, zoned.allDay)
        // Noon in New York (EST, UTC-5) is 17:00 UTC.
        val utc = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", java.util.Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        assertEquals("20240115T170000Z", utc.format(zoned.startMillis))
        assertEquals(3_600_000L, zoned.endMillis - zoned.startMillis)

        // Floating local time (no Z, no TZID) is read in the device
        // default zone (RFC 5545 floating time), not UTC, and reports it.
        val defaultZone = TimeZone.getDefault()
        val floating = ICalendar.parse(
            "BEGIN:VEVENT\r\nUID:f1\r\nDTSTART:20240201T090000\r\nDTEND:20240201T100000\r\nEND:VEVENT\r\n",
            "cal",
        ).single()
        assertEquals(defaultZone.id, floating.timezone)
        val expectedFloatingMillis = java.text.SimpleDateFormat(
            "yyyyMMdd'T'HHmmss", java.util.Locale.US,
        ).apply { timeZone = defaultZone; isLenient = false }
            .parse("20240201T090000")!!.time
        assertEquals(expectedFloatingMillis, floating.startMillis)
        assertEquals(utc.format(expectedFloatingMillis), utc.format(floating.startMillis))
        assertEquals(3_600_000L, floating.endMillis - floating.startMillis)

        // A lowercase tzid parameter name still resolves.
        val lower = ICalendar.parse(
            "BEGIN:VEVENT\r\nUID:f2\r\nDTSTART;tzid=Europe/London:20240601T120000\r\nEND:VEVENT\r\n",
            "cal",
        ).single()
        assertEquals("Europe/London", lower.timezone)

        // Garbage dates fall back to epoch start plus the one-hour default.
        val garbage = ICalendar.parse(
            "BEGIN:VEVENT\r\nUID:g1\r\nDTSTART:not-a-date\r\nEND:VEVENT\r\n",
            "cal",
        ).single()
        assertEquals(0L, garbage.startMillis)
        assertEquals(3_600_000L, garbage.endMillis - garbage.startMillis)

        // Blank UID with no fallback still gets a stable synthetic uid, and
        // blank RRULE / recurrence-id become null rather than empty strings.
        val blank = ICalendar.parse(
            "BEGIN:VEVENT\r\nUID:\r\nDTSTART:20240102T030405Z\r\nRRULE:\r\nRECURRENCE-ID:\r\nEND:VEVENT\r\n",
            "cal",
        ).single()
        assertTrue(blank.uid.isNotBlank())
        assertNull(blank.rrule)
        assertNull(blank.recurrenceId)

        // An all-day DTEND equal to DTSTART is extended to a full day, and a
        // date-only value without VALUE=DATE is still recognised as all-day.
        val sameDay = ICalendar.parse(
            "BEGIN:VEVENT\r\nUID:a1\r\nDTSTART:20240305\r\nDTEND:20240305\r\nEND:VEVENT\r\n",
            "cal",
        ).single()
        assertTrue(sameDay.allDay)
        assertEquals(86_400_000L, sameDay.endMillis - sameDay.startMillis)

        // Serializing a minimal event omits every blank optional field.
        val minimal = ICalendar.serialize(
            com.vayunmathur.everysync.data.RemoteEvent(
                uid = "m1", calendarId = "cal", summary = "",
                startMillis = 0L, endMillis = 3_600_000L,
            ),
        )
        assertFalse(minimal.contains("SUMMARY:"), minimal)
        assertFalse(minimal.contains("RRULE:"), minimal)
        assertFalse(minimal.contains("RECURRENCE-ID:"), minimal)
    }

    // ---- RelayMerge soft-delete pull-back ----

    @Test fun softDeletedLocalAbsencePullsRemoteBackInsteadOfTombstoning() {
        val remote = RemoteNote(uid = "soft-1", title = "Keep me", updatedAt = 500)
        val baseline = mapOf(
            "soft-1" to RelaySnapshot("soft-1", NoteKind.kind, NoteKind.contentHash(remote), 500, 500, 400),
        )
        // Local list is empty (a missed read, not a marked delete): the merge
        // must pull the relay copy back, never tombstone it.
        val plan = RelayMerge.merge(NoteKind, emptyList(), listOf(remote), baseline, now = 1_000)
        assertEquals(listOf(remote), plan.localUpserts)
        assertTrue(plan.remoteOut.isEmpty(), plan.remoteOut.toString())
        assertEquals(1, plan.pulled)
        assertEquals(0, plan.deletedRemote)

        // Same shape for a hard-delete kind DOES tombstone (passwords).
        val pw = RemotePassword(uid = "hard-1", name = "Bank", updatedAt = 500)
        val pwBaseline = mapOf(
            "hard-1" to RelaySnapshot("hard-1", PasswordKind.kind, PasswordKind.contentHash(pw), 500, 500, 400),
        )
        val pwPlan = RelayMerge.merge(PasswordKind, emptyList(), listOf(pw), pwBaseline, now = 1_000)
        assertTrue(pwPlan.localUpserts.isEmpty())
        assertTrue(pwPlan.remoteOut.single().deleted)
        assertEquals(1, pwPlan.deletedRemote)
    }

    @Test fun kindAdaptersRebindWithDeletedAndStamps() {
        val todo = RemoteTodo(uid = "t-old", title = "Task", updatedAt = 10)
        assertEquals("t-new", TodoKind.rebind(todo, "t-new").uid)
        assertTrue(TodoKind.withDeleted(todo, true).deleted)
        assertFalse(TodoKind.withDeleted(todo, false).deleted)
        assertEquals(99L, TodoKind.withUpdatedAt(todo, 99).updatedAt)
        assertEquals(todo, TodoKind.fromJson(TodoKind.toJson(todo)))
        assertEquals("", TodoKind.fromJson("""{"uid":""}""")?.uid)
        assertEquals(ContactKind.kind, "contact")
        assertEquals(CalendarKind.kind, "event")
    }

    // ---- Snapshots for IPC types + joined vault title ----

    @Test fun passwordAndTodoSnapshotsRoundTripThroughRestore() { runBlocking {
        assertTrue(RelaySync.init(ctx))
        val pwVault = "v-wave3-passwords"
        VaultStore.upsert(
            ctx,
            VaultMeta(
                vaultId = pwVault, dataType = "passwords", title = "Passwords",
                keyB64 = Base64.encode(RelaySync.newVaultKey()),
                ownerDeviceId = RelaySync.deviceId,
                ownerKeyB64 = Base64.encode(RelaySync.publicBundle), isOwner = true,
            ),
        )
        PasswordSink.upsert(
            ctx, pwVault,
            RemotePassword(uid = "wave3-p1", name = "Mail", username = "alice", password = "s3cret", updatedAt = 100),
        )
        assertTrue(SnapshotStore.snapshotVault(ctx, pwVault, "passwords", nowMs = 1_800_000_000_000L))
        val pwSnap = SnapshotStore.listForVault(ctx, pwVault).single()
        assertEquals(1, pwSnap.recordCount)
        PasswordSink.delete(ctx, pwVault, "wave3-p1")
        assertTrue(PasswordSink.listLocal(ctx, pwVault).none { it.uid == "wave3-p1" })
        assertEquals(1, SnapshotStore.restore(ctx, pwSnap))
        assertTrue(PasswordSink.listLocal(ctx, pwVault).any { it.uid == "wave3-p1" })

        val todoVault = "v-wave3-todos"
        VaultStore.upsert(
            ctx,
            VaultMeta(
                vaultId = todoVault, dataType = "todos", title = "Todos",
                keyB64 = Base64.encode(RelaySync.newVaultKey()),
                ownerDeviceId = RelaySync.deviceId,
                ownerKeyB64 = Base64.encode(RelaySync.publicBundle), isOwner = true,
            ),
        )
        TodoIpcSink.upsert(ctx, todoVault, RemoteTodo(uid = "wave3-t1", title = "Snap task", updatedAt = 100))
        assertTrue(SnapshotStore.snapshotVault(ctx, todoVault, "todos", nowMs = 1_800_000_000_000L))
        val todoSnap = SnapshotStore.listForVault(ctx, todoVault).single()
        assertEquals(1, SnapshotStore.restore(ctx, todoSnap))
        // Unknown data types restore to null and never throw.
        assertNull(
            SnapshotStore.restore(
                ctx,
                SnapshotStore.SnapshotMeta(todoVault, "bogus", todoSnap.dayEpochMs, 1),
            ),
        )
        // A contacts snapshot with no bound account is unhealthy, not empty.
        assertFalse(SnapshotStore.snapshotVault(ctx, "v-wave3-nocontact", "contacts", nowMs = 1_800_000_000_000L))
        // Calendar with exactly one account resolves the account binding path.
        AccountStore.getInstance(ctx).upsert(AccountConfig("wave3-only-account", "e2ee", emptySet()))
        assertNotNull(SnapshotStore.listAll(ctx))
    } }

    @Test fun joinedVaultAdoptsOwnerSignedTitleAndIgnoresForgedTitle() { runBlocking {
        assertTrue(RelaySync.init(ctx))
        val vaultId = "v-wave3-joined"
        val key = RelaySync.newVaultKey()
        VaultStore.upsert(
            ctx,
            VaultMeta(
                vaultId = vaultId, dataType = "notes", title = "Local title",
                keyB64 = Base64.encode(key), ownerDeviceId = RelaySync.deviceId,
                ownerKeyB64 = Base64.encode(RelaySync.publicBundle), isOwner = false,
            ),
        )
        // Owner-signed title + roster, as the share flow publishes them.
        suspend fun publishSigned(channel: String, payload: String) {
            val sig = Base64.encode(RelaySync.sign(payload.encodeToByteArray()))
            val envelope = json.encodeToString(
                RelaySync.SignedEnvelope(RelaySync.deviceId, sig, payload),
            )
            RelaySync.appendRaw(channel, listOf(envelope))
        }
        publishSigned("title:$vaultId", json.encodeToString(mapOf("title" to "Owner title")))
        publishSigned(
            "members:$vaultId",
            json.encodeToString(listOf(RelaySync.Member(RelaySync.deviceId, "Me", "viewer"))),
        )
        // A forged title appended afterwards must not win.
        val forgedPayload = json.encodeToString(mapOf("title" to "Forged title"))
        val forged = json.encodeToString(
            RelaySync.SignedEnvelope("evil", Base64.encode(byteArrayOf(1, 2, 3)), forgedPayload),
        )
        RelaySync.appendRaw("title:$vaultId", listOf(forged))
        RelaySync.appendRaw("title:$vaultId", listOf("not json at all"))

        val vm = EverySyncViewModel(app)
        vm.vaults.load()
        assertTrue(
            await { vm.vaults.vaultUi.value[vaultId]?.meta?.title == "Owner title" },
            "ui=${vm.vaults.vaultUi.value}",
        )
        val ui = vm.vaults.vaultUi.value.getValue(vaultId)
        assertEquals("viewer", ui.members.single { it.id == RelaySync.deviceId }.role)
        assertTrue(ui.shareLink.contains(vaultId))
        // The adopted title is persisted back to the local store.
        assertEquals("Owner title", VaultStore.get(ctx, vaultId)?.title)
    } }

    @Test fun calendarAndContactRelayStoresAndSnapshots() { runBlocking {
        // Direct store behaviour over the (absent) system providers: reads
        // fail closed, writes are graceful no-ops, baselines round-trip.
        val contacts = ContactRelayStore(ctx, "wave3-store-account", "v-store-contacts")
        val contact = RemoteContact(uid = "sc1", displayName = "Store Contact")
        assertEquals(contact, contacts.parseRemote(contacts.serializeForRemote(contact)))
        assertNull(contacts.parseRemote("not json"))
        assertNull(contacts.parseRemote(ContactKind.toJson(RemoteContact(uid = "", displayName = "x"))))
        contacts.upsertLocal(listOf(contact))
        contacts.saveSnapshots(listOf(RelaySnapshot("sc1", ContactKind.kind, "h1", 1, 1, 1)))
        assertEquals("h1", contacts.loadBaseline()["sc1"]?.contentHash)
        contacts.removeSnapshots(listOf("sc1"))
        contacts.deleteLocal(listOf(contact))
        assertTrue(contacts.listLocal().isEmpty())

        val calendar = CalendarRelayStore(ctx, "wave3-store-account", 4242L)
        val event = RemoteEvent(uid = "se1", calendarId = "4242", summary = "Store event", startMillis = 1000, endMillis = 2000)
        assertEquals(event, calendar.parseRemote(calendar.serializeForRemote(event)))
        assertNull(calendar.parseRemote("{}"))
        calendar.upsertLocal(listOf(event))
        calendar.saveSnapshots(listOf(RelaySnapshot("se1", CalendarKind.kind, "h2", 2, 2, 2)))
        assertEquals("h2", calendar.loadBaseline()["se1"]?.contentHash)
        calendar.removeSnapshots(listOf("se1"))
        calendar.deleteLocal(listOf(event))
        assertTrue(calendar.listLocal().isEmpty())

        // Todo IPC sink read paths against the fake provider.
        TodoIpcSink.upsert(ctx, "v-wave3-etag", RemoteTodo(uid = "etag-1", title = "One", updatedAt = 111))
        TodoIpcSink.upsert(ctx, "v-wave3-etag", RemoteTodo(uid = "etag-2", title = "Two", updatedAt = 222))
        val etags = TodoIpcSink.localUidToEtag(ctx, "v-wave3-etag")
        assertEquals(111L, etags["etag-1"]); assertEquals(222L, etags["etag-2"])
        TodoIpcSink.delete(ctx, "v-wave3-etag", "etag-2")
        assertEquals(setOf("etag-1"), TodoIpcSink.localUidToEtag(ctx, "v-wave3-etag").keys)
        val changes = TodoIpcSink.getLocalChanges(ctx, "v-wave3-etag", since = 0)
        assertTrue(changes.any { it.syncId == "etag-2" && it.deleted })

        // Calendar snapshot with a bound single account takes the healthy
        // empty-calendar path; contacts snapshot fails closed without a
        // contacts provider.
        AccountStore.getInstance(ctx).upsert(AccountConfig("wave3-snap-account", "e2ee", emptySet()))
        com.vayunmathur.everysync.provider.SyncState.set(ctx, "wave3-snap-account", "e2ee_vault_calendar", "v-wave3-cal-snap")
        com.vayunmathur.everysync.provider.SyncState.set(ctx, "wave3-snap-account", "e2ee_vault_contacts", "v-wave3-cal-snap")
        assertTrue(SnapshotStore.snapshotVault(ctx, "v-wave3-cal-snap", "calendar", nowMs = 1_800_100_000_000L))
        assertEquals(1, SnapshotStore.listForVault(ctx, "v-wave3-cal-snap").size)
        assertFalse(SnapshotStore.snapshotVault(ctx, "v-wave3-cal-snap", "contacts", nowMs = 1_800_100_000_000L))
    } }
}
