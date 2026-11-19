package com.vayunmathur.everysync.provider

/** The only provider EverySync ships: personal E2EE vaults. Drives the "Add account" grid. */
object ProviderRegistry {
    val all: List<SyncProvider> = listOf(
        E2eeProvider(),
    )

    fun get(id: String): SyncProvider? = all.firstOrNull { it.id == id }
}
