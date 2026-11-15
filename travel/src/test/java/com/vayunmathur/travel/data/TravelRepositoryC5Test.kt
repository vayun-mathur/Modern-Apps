package com.vayunmathur.travel.data

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.databases
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real [TravelRepository] delegates against an in-memory database seeded
 * into the shared `databases` cache (the same seam HealthRepository tests
 * use), so every DAO delegation — not just the fake overrides used to drive
 * ViewModels elsewhere — is exercised end to end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TravelRepositoryC5Test {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @BeforeTest
    fun open() {
        synchronized(databases) {
            if (databases[TravelDatabase::class] == null) {
                databases[TravelDatabase::class] = Room
                    .inMemoryDatabaseBuilder(context, TravelDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            }
        }
    }

    private fun repo() = TravelRepository(context)

    @Test
    fun recentSearches_insertObserveDeleteTrimClear() = runTest {
        val repo = repo()
        repo.clearRecent()
        val first = RecentSearch(vertical = "flights", label = "C5 SFO to JFK")
        repo.insertRecent(first)
        repo.insertRecent(RecentSearch(vertical = "stays", label = "C5 Paris hotels"))
        repo.insertRecent(RecentSearch(vertical = "flights", label = "C5 LAX to ORD"))
        assertEquals(3, repo.observeRecent(10).first().size)
        assertEquals(2, repo.observeRecent(2).first().size)
        repo.deleteRecent(repo.observeRecent(10).first().first { it.label == "C5 SFO to JFK" })
        assertTrue(repo.observeRecent(10).first().none { it.label == "C5 SFO to JFK" })
        repo.trimRecent(keep = 1)
        assertEquals(1, repo.observeRecent(10).first().size)
        repo.clearRecent()
        assertTrue(repo.observeRecent(10).first().isEmpty())
    }

    @Test
    fun bookedTrips_upsertObserveGetDelete() = runTest {
        val repo = repo()
        val trip = BookedTrip(
            orderId = "c5_ord_1", bookingReference = "C5REF1", route = "SFO → JFK",
            departDate = "2027-06-24", amount = "250.00", currency = "USD",
        )
        repo.upsertBookedTrip(trip)
        repo.upsertBookedTrip(trip.copy(amount = "275.00", status = "processing"))
        assertEquals("275.00", repo.getBookedTrip("c5_ord_1")?.amount)
        assertTrue(repo.getAllBookedTrips().any { it.orderId == "c5_ord_1" })
        assertTrue(repo.observeBookedTrips().first().any { it.orderId == "c5_ord_1" })
        assertNull(repo.getBookedTrip("c5_missing"))
        repo.deleteBookedTripById("c5_ord_1")
        assertNull(repo.getBookedTrip("c5_ord_1"))
    }

    @Test
    fun frequentFlyers_upsertObserveGetDelete() = runTest {
        val repo = repo()
        repo.upsertFrequentFlyer(FrequentFlyer(airlineIata = "C5", accountNumber = "998877", airlineName = "C5 Air"))
        assertEquals("998877", repo.getAllFrequentFlyers().first { it.airlineIata == "C5" }.accountNumber)
        assertTrue(repo.observeFrequentFlyers().first().any { it.airlineIata == "C5" })
        repo.deleteFrequentFlyer("C5")
        assertTrue(repo.getAllFrequentFlyers().none { it.airlineIata == "C5" })
    }

    @Test
    fun customers_upsertObserveGetDelete() = runTest {
        val repo = repo()
        repo.upsertCustomer(Customer(id = "c5_cust_1", email = "c5@example.com", givenName = "Casey", familyName = "Five"))
        assertEquals("Casey Five", repo.getCustomer("c5_cust_1")?.displayName)
        assertTrue(repo.observeCustomers().first().any { it.id == "c5_cust_1" })
        assertNull(repo.getCustomer("c5_nobody"))
        repo.deleteCustomerById("c5_cust_1")
        assertNull(repo.getCustomer("c5_cust_1"))
    }

    @Test
    fun singleton_getReturnsSameInstance() {
        assertSame(TravelRepository.get(context), TravelRepository.get(context))
    }
}
