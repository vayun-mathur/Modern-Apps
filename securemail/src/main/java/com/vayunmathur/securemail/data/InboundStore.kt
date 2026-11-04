package com.vayunmathur.securemail.data

import android.content.Context
import android.util.Base64
import com.vayunmathur.securemail.domain.mail.MimeCodec
import com.vayunmathur.securemail.domain.pgp.PgpCrypto
import com.vayunmathur.securemail.network.MailSocket
import com.vayunmathur.securemail.platform.crypto.KeyManager

/**
 * Single choke point for inbound socket frames (relay MSG + SMTP-ingested
 * blobs): writes to the Room vault, decrypting immediately when the Keystore
 * holds the S2K passphrase. Used by both [MailSocketService] (live socket)
 * and [MailSyncWorker] (background drain) so arrival behavior is identical
 * everywhere — no caller duplicates this logic.
 */
object InboundStore {

    /**
     * Stores one inbound frame. Encrypted frames decrypt on arrival with the
     * key of the own address they were delivered to (primary or alias);
     * unencrypted frames parse their MIME envelope directly; undecryptable
     * frames persist as locked blobs for later retry. The server spam verdict
     * ([MailSocket.MsgFlags.spam]) routes to the Spam folder — independent of
     * PGP signing (unsigned mail is accepted, not spam).
     */
    suspend fun store(context: Context, msg: MailSocket.InboundMsg) {
        val app = context.applicationContext
        val repo = MailRepository.get(app)
        val identity = Identities.forDelivery(app, msg.deliveredTo)
        val deliveredTo = msg.deliveredTo.ifBlank { identity?.address.orEmpty() }
        val folder = if (msg.flags.spam) MailFolder.SPAM else MailFolder.INBOX
        val raw = msg.raw
        if (msg.flags.unencrypted) {
            val (subject, body, from, html) = MimeCodec.parseMime(raw.toString(Charsets.UTF_8))
            repo.messageDao().upsert(
                StoredMessage(
                    id = msg.id,
                    from = from,
                    toJson = deliveredTo,
                    dateMillis = System.currentTimeMillis(),
                    messageId = msg.id,
                    blobBase64 = "",
                    decryptedBody = body,
                    decryptedSubject = subject,
                    unencrypted = true,
                    folder = folder,
                    decryptedHtml = html,
                    deliveredTo = deliveredTo,
                ),
            )
            return
        }
        val passphrase = KeyManager(app).getKeyPassphrase()
        val stored = if (passphrase != null && identity != null) {
            runCatching {
                val pgpB64 = MimeCodec.extractPgpPayloadB64(raw)
                val pgpBytes = if (pgpB64 != null) {
                    Base64.decode(pgpB64, Base64.DEFAULT)
                } else {
                    raw
                }
                // Sender address is inside the encrypted payload — decrypt
                // first (verifier empty = no check yet), then resolve the
                // sender key and re-verify for the badge. Two-step because
                // the verifier key is only known after decryption.
                val provisional = PgpCrypto.decrypt(
                    pgpBytes,
                    identity.secretArmor,
                    passphrase,
                ).toString(Charsets.UTF_8)
                val (_, _, claimedFrom) = MimeCodec.parseMime(provisional)
                val (mime, verified) = if (claimedFrom.isNotBlank()) {
                    val senderKey = SenderKeys.resolve(app, claimedFrom)
                    if (senderKey != null) {
                        val checked = PgpCrypto.decryptVerify(
                            pgpBytes,
                            identity.secretArmor,
                            passphrase,
                            senderKey,
                        )
                        checked.payload.toString(Charsets.UTF_8) to
                            checked.signerFingerprintHex.ifBlank { null }
                    } else {
                        provisional to null
                    }
                } else {
                    provisional to null
                }
                val (subject, body, from, html) = MimeCodec.parseMime(mime)
                StoredMessage(
                    id = msg.id,
                    from = from,
                    toJson = deliveredTo,
                    dateMillis = System.currentTimeMillis(),
                    messageId = msg.id,
                    blobBase64 = Base64.encodeToString(raw, Base64.NO_WRAP),
                    decryptedBody = body,
                    decryptedSubject = subject,
                    verifiedSigner = verified,
                    unencrypted = false,
                    folder = folder,
                    decryptedHtml = html,
                    deliveredTo = deliveredTo,
                )
            }.getOrNull()
        } else {
            null
        }
        repo.messageDao().upsert(
            stored ?: StoredMessage(
                id = msg.id,
                from = "",
                toJson = deliveredTo,
                dateMillis = System.currentTimeMillis(),
                messageId = msg.id,
                blobBase64 = Base64.encodeToString(raw, Base64.NO_WRAP),
                unencrypted = false,
                folder = folder,
                deliveredTo = deliveredTo,
            ),
        )
    }
}
