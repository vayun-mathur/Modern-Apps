package com.vayunmathur.messages.signal.store

class SignalRecipientStore(private val db: SignalDatabase) {

    suspend fun getRecipient(aci: String): SignalRecipientEntity? {
        return db.recipientDao().get(aci)
    }

    suspend fun storeRecipient(entity: SignalRecipientEntity) {
        db.recipientDao().insert(entity)
    }

    suspend fun getByE164(phone: String): SignalRecipientEntity? {
        return db.recipientDao().getByE164(phone)
    }

    suspend fun search(query: String): List<SignalRecipientEntity> {
        return db.recipientDao().search(query)
    }

    suspend fun getAllRecipients(): List<SignalRecipientEntity> {
        return db.recipientDao().getAll()
    }
}
