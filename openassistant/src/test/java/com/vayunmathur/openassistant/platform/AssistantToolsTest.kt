package com.vayunmathur.openassistant.platform

import com.vayunmathur.library.ml.declareGemma4Tools
import com.vayunmathur.openassistant.domain.ASSISTANT_TOOL_SPECS
import com.vayunmathur.openassistant.domain.ToolParamType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AssistantToolsTest {

    private val snake = Regex("[a-z]+(_[a-z0-9]+)*")

    @Test
    fun namesAreUniqueSnakeCase() {
        val names = ASSISTANT_TOOL_SPECS.map { it.name }
        assertEquals(47, names.size)
        assertEquals(names.size, names.toSet().size)
        for (spec in ASSISTANT_TOOL_SPECS) {
            assertTrue(snake.matches(spec.name), spec.name)
            assertTrue(spec.description.isNotBlank(), spec.name)
            assertEquals(spec.params.size, spec.params.map { it.name }.toSet().size, spec.name)
            spec.params.forEach { assertTrue(snake.matches(it.name), "${spec.name}.${it.name}") }
        }
    }

    @Test
    fun declarationsMirrorTheSpecs() {
        for (spec in ASSISTANT_TOOL_SPECS) {
            val declaration = spec.toDeclaration()
            assertEquals(spec.name, declaration.name)
            assertEquals(spec.description, declaration.description)
            assertEquals(spec.params.map { it.name }, declaration.parameters.map { it.name })
            assertEquals(spec.params.map { it.required }, declaration.parameters.map { it.required })
            assertEquals(spec.params.map { it.type.gemmaName }, declaration.parameters.map { it.type })
        }
    }

    @Test
    fun declarationTextNamesEveryToolAndMarksOptionalParams() {
        val text = declareGemma4Tools(ASSISTANT_TOOL_SPECS.map { it.toDeclaration() })
        ASSISTANT_TOOL_SPECS.forEach { assertTrue("declaration:${it.name}{" in text, it.name) }
        val timer = declareGemma4Tools(listOf(ASSISTANT_TOOL_SPECS.first { it.name == "set_timer" }.toDeclaration()))
        assertTrue("required:[<|\"|>seconds<|\"|>]" in timer, timer)
    }

    @Test
    fun numericParamsAreTheOnesLiteRtPassedAsDouble() {
        val numeric = ASSISTANT_TOOL_SPECS.flatMap { spec ->
            spec.params.filter { it.type == ToolParamType.NUMBER }.map { "${spec.name}.${it.name}" }
        }.toSet()
        assertEquals(
            setOf(
                "create_calendar_event.start", "create_calendar_event.end",
                "create_location_share_link.duration_millis", "play_music.id",
                "set_alarm.hour", "set_alarm.minute", "set_timer.seconds",
                "get_weather.latitude", "get_weather.longitude", "remove_memory.id",
                "create_todo.due", "create_todo.priority",
                "get_directions.from_lat", "get_directions.from_lng",
                "get_directions.to_lat", "get_directions.to_lng",
                "get_ride_estimate.pickup_lat", "get_ride_estimate.pickup_lng",
                "get_ride_estimate.dest_lat", "get_ride_estimate.dest_lng",
                "food_order_lookup.lat", "food_order_lookup.lng",
                "web_search.count", "web_read_result.index", "web_read_result.offset",
                "find_conversations.limit", "get_conversation_messages.limit",
                "find_restaurants.lat", "find_restaurants.lng", "find_restaurants.limit",
                "get_restaurant_menu.merchant_id", "food_set_cart.merchant_id",
                "food_prepare_order.merchant_id", "food_prepare_order.is_pickup",
                "food_prepare_order.tip_cents", "place_food_order.merchant_id",
            ),
            numeric,
        )
    }
}
