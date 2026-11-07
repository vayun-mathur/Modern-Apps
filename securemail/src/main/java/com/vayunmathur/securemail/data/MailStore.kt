package com.vayunmathur.securemail.data

/**
 * The DAO surface the ViewModel and controllers persist through.
 *
 * [MailRepository] is the only production implementation. The seam exists
 * because the repository's SQLCipher database cannot be opened in a JVM
 * unit test (no AndroidKeyStore/SQLCipher native on the host), while the
 * ViewModel logic above it is plain state handling that should be
 * testable with in-memory DAOs.
 */
interface MailStore {
    fun messageDao(): MessageDao
    fun keyDao(): KeyDao
    fun outboxDao(): OutboxDao
    fun identityDao(): IdentityDao
}
