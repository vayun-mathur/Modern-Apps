package com.vayunmathur.securemail.ui

import android.content.Context
import com.vayunmathur.securemail.data.Identities
import kotlin.coroutines.Continuation
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Robolectric shadow for [Identities]: its migration path opens the
 * process-wide SQLCipher [com.vayunmathur.securemail.data.MailRepository]
 * singleton, whose native library cannot load on a host JVM. Navigation
 * tests drive the ViewModel through FakeMailStore instead, so the legacy
 * identity migration is a no-op here.
 */
@Implements(Identities::class)
class ShadowIdentities {
    @Suppress("UnusedParameter") // signature must match the shadowed suspend fun
    @Implementation
    fun ensureMigrated(context: Context, continuation: Continuation<Unit>): Any? = Unit
}
