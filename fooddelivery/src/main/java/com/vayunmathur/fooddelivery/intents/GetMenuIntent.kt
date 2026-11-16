package com.vayunmathur.fooddelivery.intents

import com.vayunmathur.library.intents.fooddelivery.GetMenu
import com.vayunmathur.library.intents.fooddelivery.MenuData
import com.vayunmathur.library.util.AssistantIntent

/** Assistant tool `get_restaurant_menu`: categories + items with modifier summaries. */
class GetMenuIntent : AssistantIntent<GetMenu, MenuData>(
    GetMenu.serializer(),
    MenuData.serializer(),
) {
    override suspend fun performCalculation(input: GetMenu): MenuData =
        getMenu(this, input, foodAssistantBackend())
}
