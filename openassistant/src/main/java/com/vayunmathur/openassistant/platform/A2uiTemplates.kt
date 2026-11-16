package com.vayunmathur.openassistant.platform

import com.vayunmathur.openassistant.domain.PendingToolCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Host-generated A2UI cards. The model never authors these. */
object A2uiTemplates {

    /** Confirmation card for a pending WRITE_CONFIRM tool call. */
    fun confirmCard(pending: PendingToolCall): String {
        val components = buildJsonArray {
            add(obj("id" to s("root"), "component" to s("Card"), "child" to s("col")))
            add(
                obj(
                    "id" to s("col"), "component" to s("Column"),
                    "children" to JsonArray(listOf(s("title"), s("summary"), s("actions"))),
                ),
            )
            add(
                obj(
                    "id" to s("title"), "component" to s("Text"),
                    "text" to s("Confirm ${pending.name}"), "variant" to s("title"),
                ),
            )
            add(
                obj(
                    "id" to s("summary"), "component" to s("Text"),
                    "text" to s(pending.summary), "variant" to s("body"),
                ),
            )
            add(
                obj(
                    "id" to s("actions"), "component" to s("Row"),
                    "children" to JsonArray(listOf(s("btnCancel"), s("btnConfirm"))),
                ),
            )
            add(button("btnCancel", "Cancel", "cancel.tool", pending.id, primary = false))
            add(button("btnConfirm", "Confirm", "confirm.tool", pending.id, primary = true))
        }
        val data = buildJsonObject {
            put("id", pending.id)
            put("tool", pending.name)
            put("summary", pending.summary)
        }
        return JsonObject(mapOf("components" to components, "data" to data)).toString()
    }

    private fun button(
        id: String,
        label: String,
        event: String,
        pendingId: String,
        primary: Boolean,
    ): JsonObject = obj(
        "id" to s(id), "component" to s("Button"), "label" to s(label),
        "primary" to JsonPrimitive(primary),
        "action" to buildJsonObject {
            put(
                "event",
                buildJsonObject {
                    put("name", event)
                    put("context", buildJsonObject { put("id", pendingId) })
                },
            )
        },
    )

    private fun s(value: String) = JsonPrimitive(value)
    private fun obj(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) =
        JsonObject(mapOf(*pairs))
}
