package com.vayunmathur.contacts.data

/**
 * An account as the contacts provider names it. Blank [name] and [type] is the
 * device-local NULL account (shown as "On-Device").
 */
data class ProviderAccount(val name: String, val type: String)

/** Mirrors `ContactsContract.RawContacts.DefaultAccount.DefaultAccountAndState` states. */
enum class DefaultAccountKind { NOT_SET, LOCAL, SIM, CLOUD }

/** The system's default account for new contacts. [account] is set for SIM and CLOUD. */
data class DefaultAccountSnapshot(val kind: DefaultAccountKind, val account: ProviderAccount?)

/**
 * Where the provider's account facts come from. The platform implementation is
 * [SystemDefaultAccountSource]; tests substitute a fake.
 */
interface DefaultAccountSource {
    /** Null before Android 16, or when the query failed (OEM builds may differ). */
    fun defaultAccountForNewContacts(): DefaultAccountSnapshot?

    /**
     * The device's customised local account (`RawContacts.getLocalAccountName/Type`),
     * or null when the device uses the NULL account for local contacts.
     */
    fun customLocalAccount(): ProviderAccount?

    /** SIM accounts registered with the contacts provider (`SimContacts.getSimAccounts`). */
    fun providerSimAccounts(): List<ProviderAccount>
}

/**
 * Accounts the provider refuses new contacts for while the default account is the
 * cloud account [cloudAccount] (Android 16+, compat change
 * RESTRICT_CONTACTS_CREATION_IN_ACCOUNTS): the device-local account and the SIM
 * accounts registered with the provider.
 *
 * The app's own SIM targets ([SIM_ACCOUNT_TYPE]) are not blocked: they write the card
 * through `content://icc/adn`, not the contacts provider, so the restriction does not
 * reach them. Accounts of [LOCAL_ACCOUNT_TYPE] are ordinary named accounts to the
 * provider and are not blocked either.
 */
data class NewContactRestriction(
    val cloudAccount: ProviderAccount,
    val blockedAccounts: Set<ProviderAccount> = emptySet(),
) {
    /** True when the provider would reject a new contact in the account ([name], [type]). */
    fun blocks(name: String?, type: String?): Boolean {
        val n = name.orEmpty()
        val t = type.orEmpty()
        if (n.isBlank() && t.isBlank()) return true
        return ProviderAccount(n, t) in blockedAccounts
    }
}

/** Result of [Contact.saveWithOutcome]. */
enum class SaveOutcome { SAVED, FAILED, REJECTED_CLOUD_DEFAULT }

/**
 * Decides which accounts can take new contacts under the system default account
 * (Android 16+, #916) and recognises the provider's refusal.
 */
object NewContactAccountPolicy {

    /** Start of the provider's message (ContactsProvider AccountResolver). */
    const val CLOUD_DEFAULT_REJECTION = "Cannot add contacts to local or SIM accounts"

    /** Bound on the cause chain walked by [isCloudDefaultRejection]. */
    private const val MAX_CAUSE_DEPTH = 8

    /**
     * The restriction in force, or null when new contacts may go anywhere: the
     * default account is unknown (pre-Android 16 or query failure), not set, local,
     * or SIM.
     */
    fun resolve(source: DefaultAccountSource): NewContactRestriction? {
        val snapshot = source.defaultAccountForNewContacts() ?: return null
        if (snapshot.kind != DefaultAccountKind.CLOUD) return null
        val cloud = snapshot.account?.takeIf { it.name.isNotBlank() && it.type.isNotBlank() }
            ?: return null
        val blocked = buildSet {
            source.customLocalAccount()?.let(::add)
            addAll(source.providerSimAccounts())
        }
        return NewContactRestriction(cloud, blocked)
    }

    /**
     * True when [error], or one of its causes, is the provider rejecting a new contact
     * in a device or SIM account because the default account is a cloud account.
     */
    fun isCloudDefaultRejection(error: Throwable): Boolean =
        generateSequence(error) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .any { it is IllegalArgumentException && it.message?.contains(CLOUD_DEFAULT_REJECTION) == true }
}
