package com.vayunmathur.findfamily.intents

import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.library.intents.findfamily.FamilyMemberData
import com.vayunmathur.library.util.AssistantIntent
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer

@OptIn(InternalSerializationApi::class)
/** Assistant intent returning family members' names and latest locations. */
class GetIntent :
    AssistantIntent<Unit, List<FamilyMemberData>>(serializer<Unit>(), serializer<List<FamilyMemberData>>()) {

    override suspend fun performCalculation(input: Unit): List<FamilyMemberData> {
        val repository = FindFamilyRepository.get(this)
        val latestLocations = repository.latestLocationsOnce().associateBy { it.userid }
        return repository.getAllUsers().map { user ->
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
