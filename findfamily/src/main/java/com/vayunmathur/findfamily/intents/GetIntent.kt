package com.vayunmathur.findfamily.intents

import com.vayunmathur.findfamily.data.FFDatabase
import com.vayunmathur.library.intents.findfamily.FamilyMemberData
import com.vayunmathur.library.util.AssistantIntent
import com.vayunmathur.library.room.buildDatabase
import kotlinx.coroutines.flow.first
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer

@OptIn(InternalSerializationApi::class)
class GetIntent: AssistantIntent<Unit, List<FamilyMemberData>>(serializer<Unit>(), serializer<List<FamilyMemberData>>()) {

    override suspend fun performCalculation(input: Unit): List<FamilyMemberData> {
        val db = buildDatabase<FFDatabase>()
        val latestLocations = db.locationValueDao().getLatest().first().associateBy { it.userid }
        return db.userDao().getAll().map { user ->
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
