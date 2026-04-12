package com.vayunmathur.findfamily.intents

import com.vayunmathur.findfamily.data.FFDatabase
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.library.intents.findfamily.FamilyMemberData
import com.vayunmathur.library.util.AssistantIntent
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.buildDatabase
import kotlinx.coroutines.flow.first
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer

@OptIn(InternalSerializationApi::class)
class GetIntent: AssistantIntent<Unit, List<FamilyMemberData>>(serializer<Unit>(), serializer<List<FamilyMemberData>>()) {

    override suspend fun performCalculation(input: Unit): List<FamilyMemberData> {
        val db = buildDatabase<FFDatabase>()
        val viewModel = DatabaseViewModel(db, User::class to db.userDao(), LocationValue::class to db.locationValueDao())
        val latestLocations = db.locationValueDao().getLatest().first().associateBy { it.userid }
        return viewModel.getAll<User>().map { user ->
            val location = latestLocations[user.id]
            FamilyMemberData(
                user.name,
                user.locationName,
                location?.coord?.lat ?: 0.0,
                location?.coord?.lon ?: 0.0
            )
        }
    }
}
