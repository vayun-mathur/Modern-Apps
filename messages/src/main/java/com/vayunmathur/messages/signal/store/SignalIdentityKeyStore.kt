package com.vayunmathur.messages.signal.store

import kotlinx.coroutines.runBlocking
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.IdentityKeyStore
import java.util.Base64

class SignalIdentityKeyStore(
    private val db: SignalDatabase,
    private val identityKeyPairBase64: String,
    private val localRegistrationId: Int,
) : IdentityKeyStore {

    override fun getIdentityKeyPair(): IdentityKeyPair {
        return IdentityKeyPair(Base64.getDecoder().decode(identityKeyPairBase64))
    }

    override fun getLocalRegistrationId(): Int = localRegistrationId

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): Boolean {
        val existing = runBlocking { db.identityKeyDao().get(address.name) }
        runBlocking {
            db.identityKeyDao().insert(
                SignalIdentityKeyEntity(address.name, identityKey.serialize(), true)
            )
        }
        return existing != null && !existing.identityKey.contentEquals(identityKey.serialize())
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean {
        val entity = runBlocking { db.identityKeyDao().get(address.name) } ?: return true
        if (direction == IdentityKeyStore.Direction.SENDING) {
            return entity.identityKey.contentEquals(identityKey.serialize())
        }
        return true
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        val entity = runBlocking { db.identityKeyDao().get(address.name) } ?: return null
        return IdentityKey(entity.identityKey)
    }
}
