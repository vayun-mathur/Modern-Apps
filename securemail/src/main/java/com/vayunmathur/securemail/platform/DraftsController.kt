package com.vayunmathur.securemail.platform

import com.vayunmathur.securemail.data.MailFolder
import com.vayunmathur.securemail.data.MailRepository
import com.vayunmathur.securemail.data.StoredMessage
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** An unsent composition. [id] is stable across saves so re-saving updates in place. */
data class Draft(
    val id: String = "draft-${UUID.randomUUID()}",
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String = "",
    val body: String = "",
    val inReplyTo: String? = null,
) {
    val isEmpty: Boolean
        get() = to.isEmpty() && cc.isEmpty() && bcc.isEmpty() && subject.isBlank() && body.isBlank()
}

/**
 * Drafts live in the message table as [MailFolder.DRAFTS] rows (local only,
 * never sent to the relay). Recipients and reply threading ride in the row's
 * `toJson` as JSON; subject/body use the decrypted columns like Sent copies.
 */
class DraftsController(
    private val repo: MailRepository,
    private val scope: CoroutineScope,
    private val ownAddress: () -> String,
) {
    val drafts: Flow<List<StoredMessage>> = repo.messageDao().observeFolder(MailFolder.DRAFTS)
    val sent: Flow<List<StoredMessage>> = repo.messageDao().observeFolder(MailFolder.SENT)

    fun save(draft: Draft) {
        scope.launch(Dispatchers.IO) {
            repo.messageDao().upsert(
                StoredMessage(
                    id = draft.id,
                    from = ownAddress(),
                    toJson = json.encodeToString(Envelope(draft.to, draft.cc, draft.bcc, draft.inReplyTo)),
                    dateMillis = System.currentTimeMillis(),
                    messageId = draft.id,
                    blobBase64 = "",
                    decryptedBody = draft.body,
                    decryptedSubject = draft.subject,
                    isRead = true,
                    folder = MailFolder.DRAFTS,
                ),
            )
        }
    }

    fun delete(id: String) {
        scope.launch(Dispatchers.IO) { repo.messageDao().deleteAll(listOf(id)) }
    }

    @Serializable
    private data class Envelope(
        val to: List<String> = emptyList(),
        val cc: List<String> = emptyList(),
        val bcc: List<String> = emptyList(),
        val inReplyTo: String? = null,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Reads a [MailFolder.DRAFTS] row back into a [Draft]. */
        fun decode(row: StoredMessage): Draft {
            val env = runCatching { json.decodeFromString<Envelope>(row.toJson) }.getOrDefault(Envelope())
            return Draft(
                id = row.id,
                to = env.to,
                cc = env.cc,
                bcc = env.bcc,
                subject = row.decryptedSubject.orEmpty(),
                body = row.decryptedBody.orEmpty(),
                inReplyTo = env.inReplyTo,
            )
        }
    }
}
