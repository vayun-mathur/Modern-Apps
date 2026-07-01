# Media Features — Capability Matrix & Shared API Contracts

Owner: **integrator** (shared files). Backend devs (wa-meta-dev, tg-gm-dev, signal-dev)
implement the per-platform methods below so the integrator can wire UI uniformly.

This is the single source of truth for signatures. If you need to change one, coordinate
via `send_message` BEFORE changing it.

---

## 1. Capability matrix

Which platforms support each feature. ✅ = works today · ➕ = backend dev must add (this project)
· ❌ = not supported by the underlying protocol (UI must hide the entry).

| Platform (MessageSource) | Image | File | Poll | Location |
|---|---|---|---|---|
| MESSAGES_WEB (gmessages) | ✅ | ✅ | ❌ | ✅ (link-as-text) |
| VOICE (gvoice)           | ✅ (png/jpeg/bmp/tiff, ≤2MB) | ❌ | ❌ | ✅ (link-as-text) |
| SIGNAL                   | ✅ | ✅ | ➕ (expose `sendPoll`) | ✅ (link-as-text) |
| TELEGRAM                 | ✅ | ✅ | ➕ (`sendPoll` new) | ✅ (native + link) |
| MESSENGER (meta)         | ✅ | ✅ | ➕ (add `allowMultiple`) | ✅ (link-as-text) |
| INSTAGRAM (meta)         | ✅ | ✅ | ➕ (add `allowMultiple`) | ✅ (link-as-text) |
| WHATSAPP                 | ✅ | ✅ | ✅ | ✅ (native + link) |

Notes:
- **Image + File both flow through the EXISTING `sendMedia`** (mime-based). No backend
  changes needed for image/file. The UI just picks a different mime/picker.
- **Location = FindFamily share URL sent as a normal message.** Because the payload is a
  URL, EVERY text-capable platform supports it with zero backend work — SessionManager
  falls back to `sendMessage(text)`. WhatsApp/Telegram MAY additionally render a native pin
  when lat/lng are supplied (already implemented). So **location needs NO backend dev work.**
- **Poll** is the only feature needing backend work, and only on SIGNAL / TELEGRAM / META.

---

## 2. Shared per-platform API contracts

SessionManager dispatches by `MessageSource` (prefix of `conversationId`, see §3). Each
platform client (Kotlin `object` singleton, or `SignalClient` wrapper) MUST expose:

### 2a. Media — image & file (ALREADY EXISTS — do not change)
The existing `sendMedia` covers both image and arbitrary file (distinguished by `mime`).
SessionManager already adapts the small per-platform naming differences (`mime` vs
`mimeType`, optional `caption`) at each call site. **No action required.**

```kotlin
// canonical shape SessionManager calls (adapted per platform internally):
suspend fun sendMedia(
    conversationId: String,
    bytes: ByteArray,
    mime: String,
    fileName: String,
    caption: String?,
): Boolean
```

### 2b. Poll — REQUIRED canonical signature (backend devs: implement exactly this)
```kotlin
suspend fun sendPoll(
    conversationId: String,
    question: String,
    options: List<String>,
    allowMultiple: Boolean,
): Boolean
```
Per-platform action:
- **SIGNAL** (signal-dev): expose `sendPoll(...)` — wrap the existing
  `createPoll(conversationId, question, options, allowMultiple)`.
- **TELEGRAM** (tg-gm-dev): ADD `TelegramClient.sendPoll(...)` (build `InputMediaPoll` +
  `Poll`/`PollAnswer`, route through `MessagesSendMedia`, mirror existing `sendLocation`).
  `allowMultiple` → `Poll.multipleChoice`.
- **MESSENGER + INSTAGRAM** (wa-meta-dev): change `createPoll` to
  `sendPoll(conversationId, question, options, allowMultiple)` (add the `allowMultiple`
  param; wire it into the create-poll Lightspeed payload).
- **WHATSAPP** (wa-meta-dev): expose `sendPoll(...)` — wrap existing
  `sendPollCreation(conversationId, question, options, selectableCount)`.
  Map `allowMultiple` → `selectableCount = if (allowMultiple) options.size else 1`.
- **GMESSAGES / GVOICE**: no-op — protocol unsupported; UI hides the poll entry.

### 2c. Location — NO backend work (integrator handles in SessionManager)
Per product decision: location is ALWAYS the FindFamily share URL sent via the normal
`sendMessage(text)` path on every platform. No native location pins on any platform.
SessionManager.sendLocation(conversationId, text) → sendMessage(conversationId, text).
(Platform clients' pre-existing native sendLocation methods are left in place but unused.)

---

## 3. SessionManager dispatch & conversationId scheme

`MessagesSessionManager` (util/MessagesSessionManager.kt) routes every call by inspecting the
`conversationId` prefix → `MessageSource` (see `sourceFor`). Prefixes (`MessageSource.idPrefix`):

| Source | prefix | conversationId example |
|---|---|---|
| MESSAGES_WEB | `msgs` | `msgs:<googleThreadId>` |
| VOICE | `voice` | `voice:<threadId>` |
| TELEGRAM | `tg` | `tg:<peerId>` (forum topic: `..._topic_<id>`) |
| SIGNAL | `sig` | `sig:<recipientAci>` / `group:<base64GroupId>` |
| WHATSAPP | `wa` | `wa:<jid>` (`<phone>@s.whatsapp.net` / `<n>-<n>@g.us`) |
| MESSENGER | `fb` | `fb:<threadId>` |
| INSTAGRAM | `ig` | `ig:<threadId>` |

New SessionManager entrypoints the integrator is adding (call these from UI):
```kotlin
suspend fun sendImage(conversationId, bytes, mime, fileName, caption): Boolean   // → sendMedia
suspend fun sendFile(conversationId, bytes, mime, fileName, caption): Boolean    // → sendMedia
suspend fun sendPoll(conversationId, question, options, allowMultiple): Boolean  // → platform sendPoll
suspend fun sendLocation(conversationId, text, latitude?, longitude?): Boolean   // → see §2c
fun capabilitiesFor(conversationId): Set<MediaCapability>                        // drives UI menu
```

`MediaCapability` enum (new, in data/ or util/): `IMAGE, FILE, POLL, LOCATION`.

---

## 4. Message-request UI plumbing (tasks #6/#7) — field signal-dev must populate

To surface Accept/Block/Delete UI, the integrator is adding an `isMessageRequest` flag that
flows event → DB → UI.

**Exact additions (signal-dev + any platform with message requests, please populate):**

1. `GMEvent.ConversationUpdate` gets a new field:
   ```kotlin
   val isMessageRequest: Boolean = false,
   ```
   (default false so existing emitters compile unchanged.)

2. `data.Conversation` entity gets a new column:
   ```kotlin
   @ColumnInfo(name = "is_message_request") val isMessageRequest: Boolean = false,
   ```
   SessionManager.handleEvent merges `event.isMessageRequest` into the row.

3. SessionManager gains: `suspend fun acceptMessageRequest(conversationId): Boolean`,
   `blockConversation(conversationId): Boolean`, `deleteConversation` (exists).
   Per-platform accept/block methods are owned by each backend dev where the protocol
   supports it; default returns false (UI hides the action).

**signal-dev:** set `isMessageRequest = true` on `ConversationUpdate` emitted for
unaccepted/unknown-sender threads, and expose `SignalClient.acceptMessageRequest(conv)` +
`SignalClient.blockConversation(conv)`. Confirm the exact field name `isMessageRequest`
matches what you read.

---

_Last updated by integrator. Reply on the team channel to negotiate any signature change._

---

## 5. FINAL STATUS — what each platform supports (post-integration)

All platform clients expose the canonical `sendPoll(...)`; media (image+file) flows through
the pre-existing `sendMedia`; location is a FindFamily share URL sent as a message (native
pin on WhatsApp/Telegram when coords are supplied). Attachment menu is gated by
`MessageSource.mediaCapabilities()`.

| Platform | Photo | File | Camera | Poll | Location | Msg-request Accept | Block |
|---|---|---|---|---|---|---|---|
| Google Messages | ✅ | ✅ | ✅ | ❌ hidden | ✅ (link) | — | — |
| Google Voice | ✅ (png/jpeg/bmp/tiff) | ❌ hidden | ✅ | ❌ hidden | ✅ (link) | — | — |
| Telegram | ✅ | ✅ | ✅ | ✅ | ✅ (link) | — | — |
| Signal | ✅ | ✅ | ✅ | ✅ | ✅ (link) | ✅ | ✅ (deleteThread fromMessageRequest) |
| WhatsApp | ✅ | ✅ | ✅ | ✅ | ✅ (link) | — | — |
| Messenger | ✅ | ✅ | ✅ | ✅ | ✅ (link) | stub (returns false) | — |
| Instagram | ✅ | ✅ | ✅ | ✅ | ✅ (link) | ✅ | — |

Camera: system ACTION_IMAGE_CAPTURE via FileProvider, with a built-in CameraX fallback
screen when no camera app is installed. Location link creation delegated to FindFamily's
`CreateLinkIntent` (shipped by findfamily-dev; matches the contract in §2c/§3). Location is
link-only (no native pins on any platform).

Message-request flag persisted in `Conversation.serviceData` JSON (no schema bump); UI shows
an Accept/Block/Delete bar in the conversation + a "Message request" badge in the inbox.

Build: the `:whatsapp-signal` module (classic X3DH for WhatsApp) source was lost/untracked;
its prebuilt shaded jar was vendored to `whatsapp-signal/libs/` and re-exposed so `:messages`
configures + compiles. Follow-up: properly restore that module's shadow build.
