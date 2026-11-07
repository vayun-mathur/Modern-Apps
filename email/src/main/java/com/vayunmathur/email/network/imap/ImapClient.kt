package com.vayunmathur.email.network.imap

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.vayunmathur.library.log.Log
import com.vayunmathur.email.data.Attachment
import com.vayunmathur.email.data.EmailFolder
import com.vayunmathur.email.platform.EmailManager
import com.vayunmathur.email.data.EmailMessage
import com.vayunmathur.email.platform.ServerConfig
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-level IMAP client using raw sockets — app-password primary, Outlook OAuth via XOAUTH2.
 */
object ImapClient {

    private const val TAG = "ImapClient"
    private const val FETCH_PAGE_SIZE = 50

    /**
     * Canonical inbox mailbox name. RFC 3501 §5.1 defines INBOX case-insensitively,
     * so servers are free to report it however they like — Gmail/Yahoo/iCloud return
     * `INBOX`, but Office 365 / Exchange returns `Inbox`. Every inbox query in the app
     * (per-account *and* the unified inbox) matches on this exact literal, and SQLite
     * TEXT comparison is case-sensitive, so all persisted folder names must be folded
     * to this constant via [canonicalizeMailbox] before they hit the DB.
     */
    const val INBOX = "INBOX"

    /**
     * Folds a server-reported mailbox path to its canonical form, so `Inbox` and
     * `Inbox/Receipts` (Office 365) are stored the same way as `INBOX` and
     * `INBOX/Receipts` (Gmail). Non-inbox mailboxes are returned untouched — only the
     * inbox has spec-mandated case-insensitivity.
     *
     * @param delimiter the server's hierarchy delimiter; when unknown, the two common
     *   ones (`/` and `.`) are both accepted.
     */
    fun canonicalizeMailbox(mailbox: String, delimiter: String? = null): String {
        if (mailbox.equals(INBOX, ignoreCase = true)) return INBOX
        if (mailbox.length <= INBOX.length) return mailbox
        if (!mailbox.regionMatches(0, INBOX, 0, INBOX.length, ignoreCase = true)) return mailbox
        val delims = if (delimiter.isNullOrEmpty()) listOf("/", ".") else listOf(delimiter)
        return if (delims.any { mailbox.startsWith(it, INBOX.length) }) {
            INBOX + mailbox.substring(INBOX.length)
        } else {
            mailbox
        }
    }

    val GMAIL_VIRTUAL_FOLDERS = setOf(
        "[Gmail]/All Mail",
        "[Gmail]/Important",
        "[Gmail]/Starred",
        "[Gmail]/Chats",
    )

    suspend fun <T> withConnection(
        server: ServerConfig,
        user: String,
        auth: EmailManager.AuthType,
        trustAll: Boolean = false,
        block: suspend (RawImapConnection) -> T,
    ): T = withContext(Dispatchers.IO) {
        val useTrustAll = trustAll || !TrustAll.isKnownHost(server.host)
        val conn = RawImapConnection(server, trustAll = useTrustAll)
        try {
            conn.connect()
            var caps = conn.capability()

            if (!server.useSsl && caps.has("STARTTLS")) {
                try {
                    conn.startTls()
                    caps = conn.capability()
                } catch (e: IOException) {
                    Log.status(TAG, "STARTTLS failed ${server.host}: ${e.message}")
                }
            } else if (!server.useSsl && caps.has("LOGINDISABLED")) {
                try {
                    conn.startTls()
                    caps = conn.capability()
                } catch (e: IOException) {
                    Log.status(TAG, "STARTTLS required failed: ${e.message}")
                }
            }

            when (auth) {
                is EmailManager.AuthType.OAuth -> {
                    conn.authenticateXoauth2(user, auth.token)
                }
                is EmailManager.AuthType.Password -> {
                    try {
                        conn.login(user, auth.value)
                    } catch (e: IOException) {
                        if (caps.has("AUTH=PLAIN")) {
                            try {
                                conn.authenticatePlain(user, auth.value)
                            } catch (fallbackError: IOException) {
                                Log.status(TAG, "PLAIN fallback failed: ${fallbackError.message}")
                                throw e
                            }
                        } else throw e
                    }
                }
            }

            block(conn)
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }

    suspend fun fetchFolders(
        server: ServerConfig,
        user: String,
        auth: EmailManager.AuthType,
        accountEmail: String = user,
    ): List<EmailFolder> =
        withConnection(server, user, auth) { conn ->
            val entries = conn.list("", "*")
            entries.map { entry ->
                val delim = entry.delimiter ?: "/"
                // Identify the folder by its canonical path, but keep the server's own
                // casing for the drawer label so Outlook still reads "Inbox", not "INBOX".
                val fullName = canonicalizeMailbox(entry.mailbox, delim)
                val displayPath = entry.mailbox
                val name = if (displayPath.contains(delim)) {
                    displayPath.substringAfterLast(delim)
                } else {
                    displayPath.substringAfterLast('/')
                }
                val parent = fullName.lastIndexOf(delim).let {
                    if (it > 0) fullName.substring(0, it).takeIf { it.isNotEmpty() } else null
                }
                val holds = !entry.flags.any { it.equals("\\Noselect", ignoreCase = true) }
                EmailFolder(
                    accountEmail = accountEmail,
                    fullName = fullName,
                    name = name.ifBlank { fullName },
                    parentFullName = parent,
                    holdsMessages = holds,
                    delimiter = delim,
                )
            }
        }

    suspend fun fetchMessages(
        server: ServerConfig,
        user: String,
        auth: EmailManager.AuthType,
        folderName: String,
        limit: Int,
        offset: Int = 0,
        fetchBodies: Boolean = false,
        skipUids: Set<Long> = emptySet(),
        context: Context? = null,
        accountEmail: String = user,
    ): Pair<List<EmailMessage>, List<Attachment>> =
        withConnection(server, user, auth) { conn ->
            fetchMessagesInConnection(conn, accountEmail, folderName, limit, offset, fetchBodies, skipUids, context)
        }

    suspend fun fetchMessagesInConnection(
        conn: RawImapConnection,
        user: String,
        folderName: String,
        limit: Int,
        offset: Int,
        fetchBodies: Boolean,
        skipUids: Set<Long>,
        context: Context?,
    ): Pair<List<EmailMessage>, List<Attachment>> {
        // SELECT with the exact name the server gave us, but persist under the
        // canonical one so an Office 365 "Inbox" lands in the same bucket as a
        // Gmail "INBOX" (see [canonicalizeMailbox]).
        val sel = conn.select(folderName)
        val storedFolderName = canonicalizeMailbox(folderName)
        if (limit <= 0 || sel.exists == 0) return Pair(emptyList(), emptyList())

        // Page backwards through the mailbox until [limit] unseen messages
        // have been collected or sequence 1 is reached. Fetching only the
        // newest window and filtering [skipUids] in memory (the old
        // behaviour) lost mail twice over: folders larger than the window
        // never backfilled past it, and a burst of more than [limit] new
        // messages between syncs stranded the oldest of the burst forever,
        // because every later sync re-fetched the same newest window.
        val assembler = MessageAssembler(user, storedFolderName, fetchBodies, context)
        val collected = mutableSetOf<Long>()
        var pageOffset = offset
        while (assembler.messages.size < limit) {
            val seqSet = sequenceSet(sel.exists, limit, pageOffset) ?: break
            val page = conn.fetch.fetchHeadersForSeq(seqSet)
            if (page.isEmpty()) break
            for (result in page.filter { it.uid !in skipUids }.reversed()) {
                if (!collected.add(result.uid)) continue
                assembler.absorb(result)
                if (assembler.messages.size >= limit) break
            }
            // sequenceSet() clamps the window start at 1; reaching it means
            // the oldest message has been offered and paging must stop
            // (this also terminates against servers that ignore the set).
            val windowStart = seqSet.substringBefore(':').toIntOrNull() ?: 1
            if (windowStart <= 1) break
            pageOffset += limit
        }
        return Pair(assembler.messages, assembler.attachments)
    }

    private fun sequenceSet(total: Int, limit: Int, offset: Int): String? {
        if (total == 0) return null
        val end = (total - offset).coerceAtLeast(1)
        val start = (end - limit + 1).coerceAtLeast(1)
        if (end < 1 || start > end) return null
        return "$start:$end"
    }

    private class MessageAssembler(
        private val user: String,
        private val storedFolderName: String,
        private val fetchBodies: Boolean,
        private val context: Context?,
    ) {
        val messages = mutableListOf<EmailMessage>()
        val attachments = mutableListOf<Attachment>()

        fun absorb(fr: ImapFetchResult) {
            val headerMap = fr.headerBytes?.let { MimeParser.parseHeaderBlockBytes(it) } ?: emptyMap()
            val dateHeader = headerMap["date"] ?: ""
            val (body, isHtmlFlag, hasAtt) = resolveBody(fr)
            messages.add(
                EmailMessage(
                    accountEmail = user,
                    folderName = storedFolderName,
                    id = fr.uid,
                    serverId = headerMap["message-id"],
                    threadId = headerMap["x-gm-thrid"] ?: fr.uid.toString(),
                    subject = headerMap["subject"]?.let { MimeParser.decodeHeader(it) } ?: "(no subject)",
                    from = headerMap["from"]?.let { MimeParser.decodeHeader(it) } ?: "",
                    to = headerMap["to"]?.let { MimeParser.decodeHeader(it) },
                    cc = headerMap["cc"]?.let { MimeParser.decodeHeader(it) },
                    date = dateHeader.ifBlank { fr.internalDate ?: "" },
                    dateMillis = resolveDateMillis(dateHeader, fr),
                    body = body,
                    isHtml = isHtmlFlag,
                    isRead = fr.flags.any { it.equals("\\Seen", ignoreCase = true) },
                    references = headerMap["references"] ?: headerMap["in-reply-to"],
                    hasAttachments = hasAtt,
                    listUnsubscribe = headerMap["list-unsubscribe"],
                    listUnsubscribePost = headerMap["list-unsubscribe-post"]
                )
            )
        }

        private fun resolveBody(fr: ImapFetchResult): Triple<String?, Boolean, Boolean> {
            if (!fetchBodies || fr.bodyBytes == null) return Triple(null, false, false)
            val triple = try {
                MimeParser.parseMessage(fr.bodyBytes, fr.uid, user, storedFolderName, context)
            } catch (_: Exception) {
                Triple<String?, Boolean, List<Attachment>>(null, false, emptyList())
            }
            attachments.addAll(triple.third)
            return Triple(triple.first, triple.second, triple.third.isNotEmpty())
        }

        private fun resolveDateMillis(dateHeader: String, fr: ImapFetchResult): Long {
            return if (dateHeader.isNotBlank()) {
                MimeParser.parseDateToMillis(dateHeader)
            } else {
                MimeParser.parseDateToMillis(fr.internalDate ?: "")
            }
        }
    }

    suspend fun fetchMessageBody(
        server: ServerConfig,
        user: String,
        auth: EmailManager.AuthType,
        folderName: String,
        uid: Long,
        context: Context? = null,
        accountEmail: String = user,
    ): Triple<String?, Boolean, List<Attachment>> = withConnection(server, user, auth) { conn ->
        conn.select(folderName)
        val res = conn.fetch.uidFetchFullSet(uid.toString()).firstOrNull()
            ?: return@withConnection Triple<String?, Boolean, List<Attachment>>(null, false, emptyList())
        val bytes = res.bodyBytes ?: return@withConnection Triple(null, false, emptyList())
        try {
            MimeParser.parseMessage(bytes, uid, accountEmail, canonicalizeMailbox(folderName), context)
        } catch (_: Exception) {
            Log.status(TAG, "parse failed UID $uid")
            Triple(null, false, emptyList())
        }
    }

    suspend fun fetchFullForBody(
        context: Context,
        server: ServerConfig,
        user: String,
        auth: EmailManager.AuthType,
        folderName: String,
        uid: Long,
        accountEmail: String = user,
    ): EmailManager.FullFetchResult = withConnection(server, user, auth) { conn ->
        conn.select(folderName)
        val res = conn.fetch.uidFetchFullSet(uid.toString()).firstOrNull()
            ?: return@withConnection EmailManager.FullFetchResult(Triple(null, false, emptyList()))
        val bytes = res.bodyBytes
            ?: return@withConnection EmailManager.FullFetchResult(Triple(null, false, emptyList()))
        val triple = try {
            MimeParser.parseMessage(
                bytes,
                uid,
                accountEmail,
                canonicalizeMailbox(folderName),
                context.applicationContext,
            )
        } catch (_: Exception) {
            Log.status(TAG, "full parse fail UID $uid")
            Triple<String?, Boolean, List<Attachment>>(null, false, emptyList())
        }
        EmailManager.FullFetchResult(triple)
    }

    suspend fun fetchCidMap(
        context: Context,
        server: ServerConfig,
        user: String,
        auth: EmailManager.AuthType,
        folderName: String,
        uid: Long,
    ): Map<String, File> = withConnection(server, user, auth) { conn ->
        conn.select(folderName)
        val res = conn.fetch.uidFetchFullSet(uid.toString()).firstOrNull() ?: return@withConnection emptyMap()
        val bytes = res.bodyBytes ?: return@withConnection emptyMap()
        try { MimeParser.extractCidMap(context, bytes, uid) } catch (_: Exception) { emptyMap() }
    }

    suspend fun setSeenFlag(
        server: ServerConfig,
        user: String,
        auth: EmailManager.AuthType,
        folderName: String,
        uid: Long,
        seen: Boolean,
    ) = withConnection(server, user, auth) {
        it.select(folderName)
        it.fetch.uidStoreFlags(uid, "\\Seen", add = seen)
    }

    suspend fun deleteMessage(
        server: ServerConfig,
        user: String,
        auth: EmailManager.AuthType,
        folderName: String,
        uid: Long,
    ) = withConnection(server, user, auth) {
        it.select(folderName)
        it.fetch.uidExpunge(uid)
    }

    suspend fun downloadAttachment(
        context: Context,
        server: ServerConfig,
        user: String,
        auth: EmailManager.AuthType,
        folderName: String,
        uid: Long,
        partId: String,
        fileName: String,
        mimeType: String,
    ): String = withConnection(server, user, auth) { conn ->
        conn.select(folderName)
        val section = partIdToSection(partId)
        val bytes = conn.fetch.uidFetchPartBytes(uid, section)
            ?: throw IllegalStateException("Part $partId ($section) not found UID $uid")
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val itemUri = resolver.insert(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values,
        ) ?: throw IllegalStateException("Downloads entry failed")
        try {
            resolver.openOutputStream(itemUri)?.use { it.write(bytes) } ?: throw IllegalStateException("openOutput")
            values.clear()
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
        } catch (e: IOException) {
            runCatching { resolver.delete(itemUri, null, null) }
            throw e
        } catch (ignored: RuntimeException) {
            runCatching { resolver.delete(itemUri, null, null) }
            throw ignored
        }
        itemUri.toString()
    }

    suspend fun fetchRawMessageBytes(
        server: ServerConfig,
        user: String,
        auth: EmailManager.AuthType,
        folderName: String,
        uid: Long,
    ): ByteArray = withConnection(server, user, auth) { conn ->
        conn.select(folderName)
        val r = conn.fetch.uidFetchFullSet(uid.toString()).firstOrNull()
            ?: throw IllegalStateException("UID $uid not found $folderName")
        r.bodyBytes ?: throw IllegalStateException("Empty $uid")
    }

    suspend fun fetchRawMessageTo(
        server: ServerConfig,
        user: String,
        auth: EmailManager.AuthType,
        folderName: String,
        uid: Long,
        output: OutputStream,
    ) = withConnection(server, user, auth) { conn ->
        conn.select(folderName)
        val r = conn.fetch.uidFetchFullSet(uid.toString()).firstOrNull()
            ?: throw IllegalStateException("UID $uid not found $folderName")
        output.write(r.bodyBytes ?: throw IllegalStateException("Empty $uid"))
    }

    suspend fun quickInboxFetchRaw(
        conn: RawImapConnection,
        accountEmail: String,
        known: Set<Long>,
        deleted: Set<Long>,
    ): Pair<List<EmailMessage>, List<Attachment>> =
        fetchMessagesInConnection(conn, accountEmail, "INBOX", FETCH_PAGE_SIZE, 0, false, known + deleted, null)

    fun partIdToSection(partId: String): String {
        if (partId == "0" || partId.isBlank()) return "1"
        val parts = partId.split(".")
        val mapped = parts.mapNotNull { it.toIntOrNull()?.let { idx -> (idx + 1).toString() } }
        if (mapped.isEmpty()) return "1"
        return mapped.joinToString(".")
    }
}
