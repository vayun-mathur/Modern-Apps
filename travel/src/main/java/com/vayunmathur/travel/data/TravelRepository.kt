package com.vayunmathur.travel.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow

/** Test seam: subclassable + overridable flows so unit tests can drive ViewModels without SQLCipher. */
open class TravelRepository internal constructor(context: Context) :
    RoomRepository<TravelDatabase>(context, TravelDatabase::class, DB_NAME) {

    private val recentSearchDao: RecentSearchDao get() = db.recentSearchDao()
    private val bookedTripDao: BookedTripDao get() = db.bookedTripDao()
    private val frequentFlyerDao: FrequentFlyerDao get() = db.frequentFlyerDao()
    private val customerDao: CustomerDao get() = db.customerDao()

    // RecentSearch
    open fun observeRecent(limit: Int = 10): Flow<List<RecentSearch>> = recentSearchDao.observeRecent(limit)
    suspend fun insertRecent(value: RecentSearch) = recentSearchDao.insert(value)
    suspend fun deleteRecent(value: RecentSearch) = recentSearchDao.delete(value)
    suspend fun clearRecent() = recentSearchDao.clear()
    suspend fun trimRecent(keep: Int = 20) = recentSearchDao.trim(keep)

    // BookedTrip
    open fun observeBookedTrips(): Flow<List<BookedTrip>> = bookedTripDao.observeAll()
    suspend fun getAllBookedTrips(): List<BookedTrip> = bookedTripDao.getAll()
    suspend fun getBookedTrip(orderId: String): BookedTrip? = bookedTripDao.byId(orderId)
    suspend fun upsertBookedTrip(value: BookedTrip) = bookedTripDao.upsert(value)
    suspend fun deleteBookedTripById(orderId: String) = bookedTripDao.deleteById(orderId)

    // FrequentFlyer
    open fun observeFrequentFlyers(): Flow<List<FrequentFlyer>> = frequentFlyerDao.observeAll()
    open suspend fun getAllFrequentFlyers(): List<FrequentFlyer> = frequentFlyerDao.getAll()
    suspend fun upsertFrequentFlyer(value: FrequentFlyer) = frequentFlyerDao.upsert(value)
    suspend fun deleteFrequentFlyer(airlineIata: String) = frequentFlyerDao.deleteById(airlineIata)

    // Customer
    open fun observeCustomers(): Flow<List<Customer>> = customerDao.observeAll()
    suspend fun getCustomer(id: String): Customer? = customerDao.byId(id)
    suspend fun upsertCustomer(value: Customer) = customerDao.upsert(value)
    suspend fun deleteCustomerById(id: String) = customerDao.deleteById(id)

    companion object {
        @Volatile private var instance: TravelRepository? = null
        fun get(context: Context): TravelRepository =
            instance ?: synchronized(this) {
                instance ?: TravelRepository(context).also { instance = it }
            }
    }
}
