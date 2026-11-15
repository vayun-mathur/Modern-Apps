package com.vayunmathur.securemail.platform

import com.vayunmathur.library.log.Log
import com.vayunmathur.securemail.data.MailIdentity
import com.vayunmathur.securemail.data.MailStore
import com.vayunmathur.securemail.network.MailSocket
import com.vayunmathur.securemail.domain.mail.MimeCodec
import com.vayunmathur.securemail.domain.pgp.PgpCrypto

/** The plain MIME body for an outbound message. */
internal fun buildMimeBody(from: String, subject: String, body: String): ByteArray =
    MimeCodec.buildPlainBody(from, subject, body, MimeCodec.newMessageId())

/** The encrypted envelope wrapping an encrypted MIME body. */
internal fun buildEncryptedBody(encrypted: ByteArray): ByteArray =
    MimeCodec.buildEncryptedEnvelope(PgpCrypto.armorMessage(encrypted))

/**
 * Drains the local outbox straight to the server over the live socket —
 * no trust re-resolution (that already happened at send time).
 *
 * Key-known recipients re-encrypt to the cached key; keyless recipients
 * publish as plaintext frames for the server to federate. Entries stay
 * queued while offline; anything still failing after publish records an
 * attempt reason.
 */
internal class OutboxRetry(
    private val repo: MailStore,
    private val requirePassphrase: () -> String,
    private val senderIdentity: suspend (String?) -> MailIdentity,
) {
    /** Retries every queued entry; returns (sent, failed) counts. */
    suspend fun retryAll(): Pair<Int, Int> {
        val pending = repo.outboxDao().all()
        var sent = 0
        var failed = 0
        val ownPassphrase = requirePassphrase()
        for (entry in pending) {
            if (retryEntry(entry, ownPassphrase)) {
                repo.outboxDao().delete(entry.rowId)
                sent++
            } else {
                failed++
            }
        }
        return sent to failed
    }

    /** One queued entry: publish to every recipient; true when all publish. */
    // Per-entry encryption can fail for any crypto reason; the entry stays queued, logged.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun retryEntry(
        entry: com.vayunmathur.securemail.data.OutboxMessage,
        ownPassphrase: String,
    ): Boolean {
        var reason = "relay publish failed"
        val ok = runCatching {
            val sender = senderIdentity(entry.fromAddress)
            val ownSecret = sender.secretArmor
            val to = entry.toJson.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val mime = buildMimeBody(sender.address, entry.subject, entry.body)
            var entryOk = true
            for (address in to) {
                if (!publishTo(address, sender, mime, ownSecret, ownPassphrase) { reason = it }) {
                    entryOk = false
                }
            }
            entryOk
        }.onFailure {
            if (reason == "relay publish failed") {
                reason = "outbox error: " +
                    (it.message?.takeIf { m -> m.isNotBlank() } ?: it.javaClass.simpleName)
            }
            Log.status(TAG, "outbox entry failed", it)
        }.getOrDefault(false)
        if (!ok) repo.outboxDao().noteAttempt(entry.rowId, reason)
        return ok
    }

    /** Publishes one entry to one recipient; [setReason] records why it failed. */
    // Any crypto failure leaves the entry queued with the reason recorded.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun publishTo(
        address: String,
        sender: MailIdentity,
        mime: ByteArray,
        ownSecret: String,
        ownPassphrase: String,
        setReason: (String) -> Unit,
    ): Boolean {
        val cached = repo.keyDao().get(address)
        return if (cached != null) {
            val raw = try {
                PgpCrypto.encryptSigned(
                    mime, cached.armoredPublic, ownSecret, ownPassphrase,
                )
            } catch (e: Exception) {
                setReason(
                    "encrypt failed: " +
                        (e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName),
                )
                Log.status(TAG, "outbox encrypt failed", e)
                null
            }
            if (raw == null) {
                false
            } else if (MailSocket.publish(sender.address, address, buildEncryptedBody(raw))) {
                true
            } else {
                setReason("publish failed: " + (MailSocket.lastPublishError ?: "socket write failed"))
                false
            }
        } else {
            if (MailSocket.publish(sender.address, address, mime, unencrypted = true)) {
                true
            } else {
                setReason("publish failed: " + (MailSocket.lastPublishError ?: "socket write failed"))
                false
            }
        }
    }

    private companion object {
        const val TAG = "MailViewModel"
    }
}
