package com.vayunmathur.travel.data

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Room DAOs over an in-memory database: trips, recents, flyers, customers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TravelDatabaseTest {

    private lateinit var db: TravelDatabase
    @BeforeTest fun open() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, TravelDatabase::class.java).build()
    }
    @AfterTest fun close() = db.close()

    @Test fun bookedTrip_crudAndObserve() = runTest {
        val dao = db.bookedTripDao()
        dao.upsert(BookedTrip(orderId = "o1", bookingReference = "ABC123", route = "SFO → JFK", departDate = "2026-06-24", amount = "250", currency = "USD"))
        assertEquals("ABC123", dao.byId("o1")?.bookingReference)
        assertEquals(1, dao.getAll().size)
        assertEquals("o1", dao.observeAll().first().single().orderId)
        dao.deleteById("o1")
        assertNull(dao.byId("o1"))
    }

    @Test fun recentSearch_trimAndClear() = runTest {
        val dao = db.recentSearchDao()
        repeat(5) { dao.insert(RecentSearch(vertical = "flights", label = "L$it")) }
        assertEquals(5, dao.observeRecent(10).first().size)
        dao.trim(2)
        assertEquals(2, dao.observeRecent(10).first().size)
        dao.clear()
        assertTrue(dao.observeRecent(10).first().isEmpty())
    }

    @Test fun frequentFlyerAndCustomer_crud() = runTest {
        db.frequentFlyerDao().upsert(FrequentFlyer(airlineIata = "AS", accountNumber = "123", airlineName = "Alaska"))
        assertEquals("123", db.frequentFlyerDao().getAll().single().accountNumber)
        db.frequentFlyerDao().deleteById("AS")
        assertTrue(db.frequentFlyerDao().getAll().isEmpty())
        db.customerDao().upsert(Customer(id = "c1", email = "a@b.c", givenName = "Jane", familyName = "Doe"))
        assertEquals("Jane Doe", db.customerDao().byId("c1")?.displayName)
        db.customerDao().deleteById("c1")
        assertNull(db.customerDao().byId("c1"))
    }
}
