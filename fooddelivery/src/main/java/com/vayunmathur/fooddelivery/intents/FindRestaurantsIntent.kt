package com.vayunmathur.fooddelivery.intents

import com.vayunmathur.library.intents.fooddelivery.FindRestaurants
import com.vayunmathur.library.intents.fooddelivery.RestaurantData
import com.vayunmathur.library.util.AssistantIntent
import kotlinx.serialization.builtins.ListSerializer

/** Assistant tool `find_restaurants`: merchants near a point, name/tag filtered. */
class FindRestaurantsIntent : AssistantIntent<FindRestaurants, List<RestaurantData>>(
    FindRestaurants.serializer(),
    ListSerializer(RestaurantData.serializer()),
) {
    override suspend fun performCalculation(input: FindRestaurants): List<RestaurantData> =
        findRestaurants(this, input, foodAssistantBackend())
}
