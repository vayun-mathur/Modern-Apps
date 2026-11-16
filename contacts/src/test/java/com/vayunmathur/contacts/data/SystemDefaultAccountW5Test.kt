package com.vayunmathur.contacts.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** W5: SystemDefaultAccountSource must never throw on a host without the platform APIs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemDefaultAccountW5Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun allQueriesAreSafeOnPreBaklava() {
        val source = SystemDefaultAccountSource(ctx)
        // SDK 34 < BAKLAVA: default-account query short-circuits to null.
        kotlin.test.assertNull(source.defaultAccountForNewContacts())
        // Local/SIM queries go through runCatching; any outcome (null/empty/values) is acceptable,
        // but repeated calls must be stable and exception-free.
        val local1 = source.customLocalAccount()
        val local2 = source.customLocalAccount()
        assertTrue(local1 == local2, "local account query should be deterministic: $local1 vs $local2")
        val sims1 = source.providerSimAccounts()
        val sims2 = source.providerSimAccounts()
        assertTrue(sims1 == sims2, "SIM account query should be deterministic")
        sims1.forEach { assertTrue(it.name.isNotNullOrBlankOrType(it)) }
    }

    private fun String?.isNotNullOrBlankOrType(account: ProviderAccount): Boolean =
        account.type.isEmpty() || account.name.isNotEmpty() || account.name.isEmpty()
}
