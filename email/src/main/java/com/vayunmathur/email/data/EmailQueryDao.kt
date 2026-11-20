package com.vayunmathur.email.data

import androidx.room3.Dao
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/** Maximum whitespace-separated tokens a search query is split into. */
internal const val MAX_EMAIL_SEARCH_TOKENS = 8

private val EMAIL_SEARCH_WHITESPACE = Regex("\\s+")

/**
 * Escapes SQLite `LIKE` wildcards in one user-typed [token] so `%`, `_` and
 * the escape character itself match literally (queries use `ESCAPE '\'`).
 */
internal fun escapeEmailLikeToken(token: String): String =
    token.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/**
 * Splits [query] into whitespace tokens and returns exactly
 * [MAX_EMAIL_SEARCH_TOKENS] `LIKE` patterns (`%token%`, wildcard-escaped);
 * unused slots are `""`, which the SQL treats as "no constraint". Tokens
 * beyond the cap are folded, space-joined, into the last slot so no typed
 * word is silently dropped. A blank query yields all-empty patterns.
 */
internal fun emailSearchPatterns(query: String): List<String> {
    val raw = query.trim().split(EMAIL_SEARCH_WHITESPACE).filter { it.isNotEmpty() }
    val tokens = when {
        raw.size <= MAX_EMAIL_SEARCH_TOKENS -> raw
        else -> raw.take(MAX_EMAIL_SEARCH_TOKENS - 1) +
            listOf(raw.drop(MAX_EMAIL_SEARCH_TOKENS - 1).joinToString(" "))
    }
    val patterns = tokens.map { "%" + escapeEmailLikeToken(it) + "%" }
    return patterns + List(MAX_EMAIL_SEARCH_TOKENS - patterns.size) { "" }
}

/** Full-text search, unified-inbox and preview projections, plus one-time backfills. */
@Dao
interface EmailQueryDao {
    @Query(
        "SELECT * FROM EmailMessage WHERE accountEmail = :accountEmail AND folderName = :folderName " +
            "AND snoozedUntil <= :now " +
            "AND (:p1 = '' OR (subject LIKE :p1 ESCAPE '\\' OR `from` LIKE :p1 ESCAPE '\\' OR body LIKE :p1 ESCAPE '\\')) " +
            "AND (:p2 = '' OR (subject LIKE :p2 ESCAPE '\\' OR `from` LIKE :p2 ESCAPE '\\' OR body LIKE :p2 ESCAPE '\\')) " +
            "AND (:p3 = '' OR (subject LIKE :p3 ESCAPE '\\' OR `from` LIKE :p3 ESCAPE '\\' OR body LIKE :p3 ESCAPE '\\')) " +
            "AND (:p4 = '' OR (subject LIKE :p4 ESCAPE '\\' OR `from` LIKE :p4 ESCAPE '\\' OR body LIKE :p4 ESCAPE '\\')) " +
            "AND (:p5 = '' OR (subject LIKE :p5 ESCAPE '\\' OR `from` LIKE :p5 ESCAPE '\\' OR body LIKE :p5 ESCAPE '\\')) " +
            "AND (:p6 = '' OR (subject LIKE :p6 ESCAPE '\\' OR `from` LIKE :p6 ESCAPE '\\' OR body LIKE :p6 ESCAPE '\\')) " +
            "AND (:p7 = '' OR (subject LIKE :p7 ESCAPE '\\' OR `from` LIKE :p7 ESCAPE '\\' OR body LIKE :p7 ESCAPE '\\')) " +
            "AND (:p8 = '' OR (subject LIKE :p8 ESCAPE '\\' OR `from` LIKE :p8 ESCAPE '\\' OR body LIKE :p8 ESCAPE '\\')) " +
            "ORDER BY dateMillis DESC, id DESC",
    )
    fun searchMessagesFlowByTokens(
        accountEmail: String,
        folderName: String,
        p1: String,
        p2: String,
        p3: String,
        p4: String,
        p5: String,
        p6: String,
        p7: String,
        p8: String,
        now: Long,
    ): Flow<List<EmailMessage>>

    /** Tokenised search: every word in [query] must match subject, sender or body. */
    fun searchMessagesFlow(
        accountEmail: String,
        folderName: String,
        query: String,
        now: Long,
    ): Flow<List<EmailMessage>> {
        val p = emailSearchPatterns(query)
        return searchMessagesFlowByTokens(accountEmail, folderName, p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], now)
    }

    // Unified Inbox
    @Query(
        "SELECT * FROM EmailMessage WHERE folderName = :folderName AND snoozedUntil <= :now " +
            "ORDER BY dateMillis DESC, id DESC",
    )
    fun getUnifiedMessagesFlow(folderName: String, now: Long): Flow<List<EmailMessage>>

    @Query(
        "SELECT * FROM EmailMessage WHERE folderName = :folderName AND snoozedUntil <= :now " +
            "AND (:p1 = '' OR (subject LIKE :p1 ESCAPE '\\' OR `from` LIKE :p1 ESCAPE '\\' OR body LIKE :p1 ESCAPE '\\')) " +
            "AND (:p2 = '' OR (subject LIKE :p2 ESCAPE '\\' OR `from` LIKE :p2 ESCAPE '\\' OR body LIKE :p2 ESCAPE '\\')) " +
            "AND (:p3 = '' OR (subject LIKE :p3 ESCAPE '\\' OR `from` LIKE :p3 ESCAPE '\\' OR body LIKE :p3 ESCAPE '\\')) " +
            "AND (:p4 = '' OR (subject LIKE :p4 ESCAPE '\\' OR `from` LIKE :p4 ESCAPE '\\' OR body LIKE :p4 ESCAPE '\\')) " +
            "AND (:p5 = '' OR (subject LIKE :p5 ESCAPE '\\' OR `from` LIKE :p5 ESCAPE '\\' OR body LIKE :p5 ESCAPE '\\')) " +
            "AND (:p6 = '' OR (subject LIKE :p6 ESCAPE '\\' OR `from` LIKE :p6 ESCAPE '\\' OR body LIKE :p6 ESCAPE '\\')) " +
            "AND (:p7 = '' OR (subject LIKE :p7 ESCAPE '\\' OR `from` LIKE :p7 ESCAPE '\\' OR body LIKE :p7 ESCAPE '\\')) " +
            "AND (:p8 = '' OR (subject LIKE :p8 ESCAPE '\\' OR `from` LIKE :p8 ESCAPE '\\' OR body LIKE :p8 ESCAPE '\\')) " +
            "ORDER BY dateMillis DESC, id DESC",
    )
    fun searchUnifiedMessagesFlowByTokens(
        folderName: String,
        p1: String,
        p2: String,
        p3: String,
        p4: String,
        p5: String,
        p6: String,
        p7: String,
        p8: String,
        now: Long,
    ): Flow<List<EmailMessage>>

    /** Tokenised unified search; see [searchMessagesFlow]. */
    fun searchUnifiedMessagesFlow(
        folderName: String,
        query: String,
        now: Long,
    ): Flow<List<EmailMessage>> {
        val p = emailSearchPatterns(query)
        return searchUnifiedMessagesFlowByTokens(folderName, p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], now)
    }

    @Query("SELECT * FROM EmailMessage WHERE folderName = 'INBOX' ORDER BY dateMillis DESC, id DESC LIMIT 100")
    suspend fun getRecentUnifiedMessages(): List<EmailMessage>

    @Query("SELECT * FROM EmailMessage WHERE folderName = 'INBOX' ORDER BY dateMillis DESC, id DESC LIMIT 30")
    suspend fun getRecentInboxMessages(): List<EmailMessage>

    @Query(
        "SELECT * FROM EmailMessage WHERE " +
            "(:p1 = '' OR (subject LIKE :p1 ESCAPE '\\' OR `from` LIKE :p1 ESCAPE '\\' OR body LIKE :p1 ESCAPE '\\')) " +
            "AND (:p2 = '' OR (subject LIKE :p2 ESCAPE '\\' OR `from` LIKE :p2 ESCAPE '\\' OR body LIKE :p2 ESCAPE '\\')) " +
            "AND (:p3 = '' OR (subject LIKE :p3 ESCAPE '\\' OR `from` LIKE :p3 ESCAPE '\\' OR body LIKE :p3 ESCAPE '\\')) " +
            "AND (:p4 = '' OR (subject LIKE :p4 ESCAPE '\\' OR `from` LIKE :p4 ESCAPE '\\' OR body LIKE :p4 ESCAPE '\\')) " +
            "AND (:p5 = '' OR (subject LIKE :p5 ESCAPE '\\' OR `from` LIKE :p5 ESCAPE '\\' OR body LIKE :p5 ESCAPE '\\')) " +
            "AND (:p6 = '' OR (subject LIKE :p6 ESCAPE '\\' OR `from` LIKE :p6 ESCAPE '\\' OR body LIKE :p6 ESCAPE '\\')) " +
            "AND (:p7 = '' OR (subject LIKE :p7 ESCAPE '\\' OR `from` LIKE :p7 ESCAPE '\\' OR body LIKE :p7 ESCAPE '\\')) " +
            "AND (:p8 = '' OR (subject LIKE :p8 ESCAPE '\\' OR `from` LIKE :p8 ESCAPE '\\' OR body LIKE :p8 ESCAPE '\\')) " +
            "ORDER BY dateMillis DESC, id DESC LIMIT 30",
    )
    suspend fun searchMessagesByTokens(
        p1: String,
        p2: String,
        p3: String,
        p4: String,
        p5: String,
        p6: String,
        p7: String,
        p8: String,
    ): List<EmailMessage>

    /** Tokenised one-shot search; see [searchMessagesFlow]. */
    suspend fun searchMessages(query: String): List<EmailMessage> {
        val p = emailSearchPatterns(query)
        return searchMessagesByTokens(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7])
    }

    @Query("SELECT * FROM EmailMessage WHERE folderName = 'INBOX' ORDER BY dateMillis DESC, id DESC LIMIT 10")
    fun getRecentUnifiedMessagesFlow(): Flow<List<EmailMessage>>

    // ---- Light preview projections (no body) for list/widget screens ----
    // These SELECT only the columns [EmailPreview] needs and read the stored
    // peekContent snippet, so list rows and the widget never load the full body.

    @Query(
        "SELECT accountEmail, folderName, id, threadId, subject, `from`, date, dateMillis, isRead, " +
            "hasAttachments, snoozedUntil, peekContent FROM EmailMessage " +
            "WHERE accountEmail = :accountEmail AND folderName = :folderName AND snoozedUntil <= :now " +
            "ORDER BY dateMillis DESC, id DESC",
    )
    fun getMessagesPreviewFlow(
        accountEmail: String,
        folderName: String,
        now: Long,
    ): Flow<List<EmailPreview>>

    @Query(
        "SELECT accountEmail, folderName, id, threadId, subject, `from`, date, dateMillis, isRead, " +
            "hasAttachments, snoozedUntil, peekContent FROM EmailMessage " +
            "WHERE accountEmail = :accountEmail AND folderName = :folderName AND snoozedUntil <= :now " +
            "AND (:p1 = '' OR (subject LIKE :p1 ESCAPE '\\' OR `from` LIKE :p1 ESCAPE '\\' OR body LIKE :p1 ESCAPE '\\')) " +
            "AND (:p2 = '' OR (subject LIKE :p2 ESCAPE '\\' OR `from` LIKE :p2 ESCAPE '\\' OR body LIKE :p2 ESCAPE '\\')) " +
            "AND (:p3 = '' OR (subject LIKE :p3 ESCAPE '\\' OR `from` LIKE :p3 ESCAPE '\\' OR body LIKE :p3 ESCAPE '\\')) " +
            "AND (:p4 = '' OR (subject LIKE :p4 ESCAPE '\\' OR `from` LIKE :p4 ESCAPE '\\' OR body LIKE :p4 ESCAPE '\\')) " +
            "AND (:p5 = '' OR (subject LIKE :p5 ESCAPE '\\' OR `from` LIKE :p5 ESCAPE '\\' OR body LIKE :p5 ESCAPE '\\')) " +
            "AND (:p6 = '' OR (subject LIKE :p6 ESCAPE '\\' OR `from` LIKE :p6 ESCAPE '\\' OR body LIKE :p6 ESCAPE '\\')) " +
            "AND (:p7 = '' OR (subject LIKE :p7 ESCAPE '\\' OR `from` LIKE :p7 ESCAPE '\\' OR body LIKE :p7 ESCAPE '\\')) " +
            "AND (:p8 = '' OR (subject LIKE :p8 ESCAPE '\\' OR `from` LIKE :p8 ESCAPE '\\' OR body LIKE :p8 ESCAPE '\\')) " +
            "ORDER BY dateMillis DESC, id DESC",
    )
    fun searchMessagesPreviewFlowByTokens(
        accountEmail: String,
        folderName: String,
        p1: String,
        p2: String,
        p3: String,
        p4: String,
        p5: String,
        p6: String,
        p7: String,
        p8: String,
        now: Long,
    ): Flow<List<EmailPreview>>

    /** Tokenised preview search; see [searchMessagesFlow]. */
    fun searchMessagesPreviewFlow(
        accountEmail: String,
        folderName: String,
        query: String,
        now: Long,
    ): Flow<List<EmailPreview>> {
        val p = emailSearchPatterns(query)
        return searchMessagesPreviewFlowByTokens(accountEmail, folderName, p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], now)
    }

    @Query(
        "SELECT accountEmail, folderName, id, threadId, subject, `from`, date, dateMillis, isRead, " +
            "hasAttachments, snoozedUntil, peekContent FROM EmailMessage " +
            "WHERE folderName = :folderName AND snoozedUntil <= :now ORDER BY dateMillis DESC, id DESC",
    )
    fun getUnifiedMessagesPreviewFlow(folderName: String, now: Long): Flow<List<EmailPreview>>

    @Query(
        "SELECT accountEmail, folderName, id, threadId, subject, `from`, date, dateMillis, isRead, " +
            "hasAttachments, snoozedUntil, peekContent FROM EmailMessage " +
            "WHERE folderName = :folderName AND snoozedUntil <= :now " +
            "AND (:p1 = '' OR (subject LIKE :p1 ESCAPE '\\' OR `from` LIKE :p1 ESCAPE '\\' OR body LIKE :p1 ESCAPE '\\')) " +
            "AND (:p2 = '' OR (subject LIKE :p2 ESCAPE '\\' OR `from` LIKE :p2 ESCAPE '\\' OR body LIKE :p2 ESCAPE '\\')) " +
            "AND (:p3 = '' OR (subject LIKE :p3 ESCAPE '\\' OR `from` LIKE :p3 ESCAPE '\\' OR body LIKE :p3 ESCAPE '\\')) " +
            "AND (:p4 = '' OR (subject LIKE :p4 ESCAPE '\\' OR `from` LIKE :p4 ESCAPE '\\' OR body LIKE :p4 ESCAPE '\\')) " +
            "AND (:p5 = '' OR (subject LIKE :p5 ESCAPE '\\' OR `from` LIKE :p5 ESCAPE '\\' OR body LIKE :p5 ESCAPE '\\')) " +
            "AND (:p6 = '' OR (subject LIKE :p6 ESCAPE '\\' OR `from` LIKE :p6 ESCAPE '\\' OR body LIKE :p6 ESCAPE '\\')) " +
            "AND (:p7 = '' OR (subject LIKE :p7 ESCAPE '\\' OR `from` LIKE :p7 ESCAPE '\\' OR body LIKE :p7 ESCAPE '\\')) " +
            "AND (:p8 = '' OR (subject LIKE :p8 ESCAPE '\\' OR `from` LIKE :p8 ESCAPE '\\' OR body LIKE :p8 ESCAPE '\\')) " +
            "ORDER BY dateMillis DESC, id DESC",
    )
    fun searchUnifiedMessagesPreviewFlowByTokens(
        folderName: String,
        p1: String,
        p2: String,
        p3: String,
        p4: String,
        p5: String,
        p6: String,
        p7: String,
        p8: String,
        now: Long,
    ): Flow<List<EmailPreview>>

    /** Tokenised unified preview search; see [searchMessagesFlow]. */
    fun searchUnifiedMessagesPreviewFlow(
        folderName: String,
        query: String,
        now: Long,
    ): Flow<List<EmailPreview>> {
        val p = emailSearchPatterns(query)
        return searchUnifiedMessagesPreviewFlowByTokens(folderName, p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], now)
    }

    @Query(
        "SELECT accountEmail, folderName, id, threadId, subject, `from`, date, dateMillis, isRead, " +
            "hasAttachments, snoozedUntil, peekContent FROM EmailMessage WHERE folderName = 'INBOX' " +
            "ORDER BY dateMillis DESC, id DESC LIMIT 100",
    )
    suspend fun getRecentUnifiedPreview(): List<EmailPreview>

    // ---- One-time backfill for the dateMillis column ----

    /** Rows persisted before `dateMillis` existed; their `date` string needs parsing. */
    @Query("SELECT * FROM EmailMessage WHERE dateMillis = 0 LIMIT 1000")
    suspend fun getRowsWithZeroDateMillis(): List<EmailMessage>

    @Query(
        "UPDATE EmailMessage SET dateMillis = :millis WHERE accountEmail = :accountEmail " +
            "AND folderName = :folderName AND id = :uid",
    )
    suspend fun updateDateMillis(
        accountEmail: String,
        folderName: String,
        uid: Long,
        millis: Long,
    )

    // ---- One-time backfill for the peekContent column ----

    /** Rows with a body but no computed preview yet — either persisted before the
     *  peekContent column existed (default '') or inserted with an empty body since
     *  filled in. previewText's HTML strip can't be done in SQL, so recompute in
     *  Kotlin (see [PeekContentBackfill]). */
    @Query("SELECT * FROM EmailMessage WHERE peekContent = '' AND body IS NOT NULL AND body != '' LIMIT 1000")
    suspend fun getRowsWithEmptyPeek(): List<EmailMessage>

    @Query(
        "UPDATE EmailMessage SET peekContent = :peek WHERE accountEmail = :accountEmail " +
            "AND folderName = :folderName AND id = :uid",
    )
    suspend fun updatePeekContent(accountEmail: String, folderName: String, uid: Long, peek: String)
}
