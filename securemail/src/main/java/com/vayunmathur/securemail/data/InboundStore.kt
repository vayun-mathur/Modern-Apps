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

    /** Decrypts an encrypted frame into a stored message; null when it stays locked. */
    private suspend fun storeEncrypted(
        msg: MailSocket.InboundMsg,
        deliveredTo: String,
        folder: MailFolder,
        raw: ByteArray,
        identity: MailIdentity?,
        passphrase: String?,
        senderKeyResolver: suspend (String) -> String?,
    ): StoredMessage? {
        return if (passphrase != null && identity != null) {
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
                    val senderKey = senderKeyResolver(claimedFrom)
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
    }

    suspend fun store(context: Context, msg: MailSocket.InboundMsg) {
        val app = context.applicationContext
        val repo = MailRepository.get(app)
        val identity = Identities.forDelivery(app, msg.deliveredTo)
        store(
            msg = msg,
            repo = repo,
            identity = identity,
            passphraseProvider = { KeyManager(app).getKeyPassphrase() },
            senderKeyResolver = { SenderKeys.resolve(app, it) },
        )
    }

    /**
     * Testable core of [store]: [identity]/[passphraseProvider]/
     * [senderKeyResolver] are resolved by the production entry point above;
     * tests inject fakes so no Keystore/SQLCipher/socket is touched.
     */
    internal suspend fun store(
        msg: MailSocket.InboundMsg,
        repo: MailStore,
        identity: MailIdentity?,
        passphraseProvider: () -> String?,
        senderKeyResolver: suspend (String) -> String?,
    ) {
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
        val passphrase = runCatching { passphraseProvider() }.getOrNull()
        val stored = storeEncrypted(msg, deliveredTo, folder, raw, identity, passphrase, senderKeyResolver)
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
