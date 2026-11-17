package com.vayunmathur.contacts.util
import com.vayunmathur.contacts.data.ContactSourceLoader

import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.Name
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue 926 regression: one contact source must never wedge the whole
 * load. These drive [ContactSourceLoader] with fake source seams —
 * throwing, hanging (non-cancellable sleep, like a stuck Binder call
 * into the ICC provider) and healthy — and prove the other source
 * still publishes and the merged load always returns.
 */
@RunWith(RobolectricTestRunner::class)
class ContactSourceLoaderTest {

    private fun contact(id: Long, first: String) = Contact(
        id = id,
        accountType = null,
        accountName = null,
        isFavorite = false,
        details = ContactDetails.empty().copy(
            names = listOf(Name(0, "", first, "", "Lee", "")),
        ),
    )

    @Test
    fun throwingSimSourceLeavesDeviceContactsAndCompletes() = runBlocking {
        val loader = ContactSourceLoader()
        try {
            val device = listOf(contact(7, "Aisha"), contact(8, "Bob"))
            val published = mutableListOf<List<Contact>>()
            val merged = withTimeout(10_000) {
                loader.loadContacts(
                    deviceLoader = { device },
                    simLoader = { throw IllegalStateException("icc wedged") },
                    timeoutMillis = 1_000,
                    publish = { published.add(it) },
                )
            }
            assertEquals(device, merged)
            // Device contacts were published on their own before the merge.
            assertEquals(device, published.first())
        } finally {
            loader.close()
        }
    }

    @Test
    fun hangingSimSourceTimesOutAndDeviceContactsStillPublish() = runBlocking {
        val loader = ContactSourceLoader()
        try {
            val device = listOf(contact(7, "Aisha"))
            val published = mutableListOf<List<Contact>>()
            val started = System.nanoTime()
            val merged = withTimeout(10_000) {
                loader.loadContacts(
                    deviceLoader = { device },
                    // A stuck Binder query cannot be cancelled; sleep
                    // simulates exactly that (delay() would cancel).
                    simLoader = { Thread.sleep(5_000); listOf(contact(9, "Sim")) },
                    timeoutMillis = 150,
                    publish = { published.add(it) },
                )
            }
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            assertEquals(device, merged)
            assertEquals(device, published.first())
            assertTrue(elapsedMillis < 4_000, "load took ${elapsedMillis}ms despite the timeout")
        } finally {
            loader.close()
        }
    }

    @Test
    fun hangingDeviceSourceLeavesSimContactsAndCompletes() = runBlocking {
        val loader = ContactSourceLoader()
        try {
            val sim = listOf(contact(-1, "Sim"))
            val merged = withTimeout(10_000) {
                loader.loadContacts(
                    deviceLoader = { Thread.sleep(5_000); listOf(contact(7, "Aisha")) },
                    simLoader = { sim },
                    timeoutMillis = 150,
                )
            }
            assertEquals(sim, merged)
        } finally {
            loader.close()
        }
    }

    @Test
    fun healthySourcesMergeAndSlowSourceWithinTimeoutIsKept() = runBlocking {
        val loader = ContactSourceLoader()
        try {
            val device = listOf(contact(7, "Aisha"))
            val sim = listOf(contact(-1, "Sim"))
            val merged = loader.loadContacts(
                deviceLoader = { device },
                simLoader = { delay(50); sim },
                timeoutMillis = 2_000,
            )
            assertEquals(device + sim, merged)
        } finally {
            loader.close()
        }
    }

    @Test
    fun singleSourceLoadIsolatesThrowAndHang() = runBlocking {
        val loader = ContactSourceLoader()
        try {
            assertEquals("ok", loader.load("healthy", 1_000) { "ok" })
            assertNull(loader.load<String>("throwing", 1_000) {
                throw SecurityException("no sim permission")
            })
            assertNull(loader.load<String>("hanging", 150) {
                Thread.sleep(5_000)
                "never"
            })
        } finally {
            loader.close()
        }
    }
}
