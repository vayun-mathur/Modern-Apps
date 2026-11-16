package com.vayunmathur.openassistant.platform

import com.vayunmathur.openassistant.domain.PendingToolCall
import com.vayunmathur.openassistant.domain.valueToJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One selectable item in a [A2uiTemplates.listPicker] card. */
data class PickerItem(
    val id: String,
    val title: String,
    val caption: String = "",
    val imageUrl: String? = null,
)

/** One menu row in a [A2uiTemplates.foodMenu] card. */
data class MenuItemRow(
    val id: Int,
    val name: String,
    val priceCents: Int,
    val available: Boolean,
    val qty: Int = 0,
)

/** One cart/order line row in the food and order-summary cards. */
data class CartLineRow(
    val itemId: Int,
    val name: String,
    val qty: Int,
    val lineTotalCents: Int,
)

/** Formats integer cents as a dollar string ($12.34, -$1.00). */
fun formatCents(cents: Int): String {
    val sign = if (cents < 0) "-" else ""
    val abs = kotlin.math.abs(cents)
    return "$sign\$${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
}

/** One web search result in a [A2uiTemplates.searchResults] card. */
data class SearchResultItem(
    val index: Int,
    val title: String,
    val snippet: String,
    val domain: String,
    val url: String,
)

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
            add(text("title", "Confirm ${pending.name}", "title"))
            add(text("summary", pending.summary, "body"))
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
        return envelope(components, data)
    }

    /** Single-pick card: a [Picker] over [items]; selecting fires [actionName] with the id. */
    fun listPicker(title: String, items: List<PickerItem>, actionName: String = "pick.item"): String {
        val components = buildJsonArray {
            add(obj("id" to s("root"), "component" to s("Card"), "child" to s("col")))
            add(
                obj(
                    "id" to s("col"), "component" to s("Column"),
                    "children" to JsonArray(listOf(s("title"), s("picker"))),
                ),
            )
            add(text("title", title, "title"))
            add(
                obj(
                    "id" to s("picker"), "component" to s("Picker"),
                    "options" to path("/items"), "value" to path("/selected"),
                    "action" to action(actionName, mapOf("id" to pathValue("/selected"))),
                ),
            )
        }
        val data = buildJsonObject {
            put(
                "items",
                JsonArray(
                    items.map { item ->
                        buildJsonObject {
                            put("id", item.id)
                            put("title", item.title)
                            put("caption", item.caption)
                            item.imageUrl?.let { put("imageUrl", it) }
                        }
                    },
                ),
            )
            put("selected", "")
        }
        return envelope(components, data)
    }

    /** Small status/result card. [variant] is a host hint (e.g. "success"/"error"), kept in data. */
    fun statusCard(title: String, caption: String, variant: String? = null): String {
        val components = buildJsonArray {
            add(obj("id" to s("root"), "component" to s("Card"), "child" to s("col")))
            add(
                obj(
                    "id" to s("col"), "component" to s("Column"),
                    "children" to JsonArray(listOf(s("title"), s("caption"))),
                ),
            )
            add(text("title", title, "title"))
            add(text("caption", caption, "caption"))
        }
        val data = buildJsonObject {
            put("title", title)
            put("caption", caption)
            variant?.let { put("variant", it) }
        }
        return envelope(components, data)
    }

    /** Card with a title, body lines and one optional action button. */
    fun resultCard(
        title: String,
        lines: List<String>,
        actionLabel: String? = null,
        actionName: String? = null,
        actionContext: Map<String, Any?>? = null,
    ): String {
        val lineIds = lines.indices.map { "line$it" }
        val hasAction = actionLabel != null && actionName != null
        val components = buildJsonArray {
            add(obj("id" to s("root"), "component" to s("Card"), "child" to s("col")))
            add(
                obj(
                    "id" to s("col"), "component" to s("Column"),
                    "children" to JsonArray(
                        listOf(s("title")) + lineIds.map { s(it) } +
                            if (hasAction) listOf(s("btnAction")) else emptyList(),
                    ),
                ),
            )
            add(text("title", title, "title"))
            lines.forEachIndexed { i, line -> add(text("line$i", line, "body")) }
            if (hasAction) {
                add(
                    obj(
                        "id" to s("btnAction"), "component" to s("Button"), "label" to s(actionLabel),
                        "primary" to JsonPrimitive(true),
                        "action" to action(actionName, (actionContext ?: emptyMap()).mapValues { valueToJson(it.value) }),
                    ),
                )
            }
        }
        val data = buildJsonObject {
            put("title", title)
            put("lines", JsonArray(lines.map { s(it) }))
        }
        return envelope(components, data)
    }

    /**
     * Message composer card (design §3.2): recipient/line header, an editable
     * multiline body and Cancel/Send. Send is host-intercepted (`msg.send`)
     * and gated by the send confirmation card; Cancel is a plain model turn.
     * Nothing sends from this card alone.
     */
    fun messageComposer(
        recipientName: String,
        address: String,
        line: String,
        body: String,
        threadRemoteId: String? = null,
    ): String {
        val toLine = if (recipientName.isBlank()) address else "$recipientName ($address)"
        val components = buildJsonArray {
            add(obj("id" to s("root"), "component" to s("Card"), "child" to s("col")))
            add(
                obj(
                    "id" to s("col"), "component" to s("Column"),
                    "children" to JsonArray(listOf(s("to"), s("line"), s("body"), s("actions"))),
                ),
            )
            add(text("to", "To: $toLine", "title"))
            add(text("line", "Line: $line", "caption"))
            add(
                obj(
                    "id" to s("body"), "component" to s("TextField"),
                    "label" to s("Message"), "value" to path("/body"),
                    "multiline" to JsonPrimitive(true),
                ),
            )
            add(
                obj(
                    "id" to s("actions"), "component" to s("Row"),
                    "children" to JsonArray(listOf(s("btnCancel"), s("btnSend"))),
                ),
            )
            add(actionButton("btnCancel", "Cancel", "msg.cancel", emptyMap(), primary = false))
            add(
                actionButton(
                    "btnSend", "Send", "msg.send",
                    buildMap {
                        put("address", s(address))
                        put("line", s(line))
                        put("body", pathValue("/body"))
                        threadRemoteId?.takeIf { it.isNotBlank() }
                            ?.let { put("thread_remote_id", s(it)) }
                    },
                    primary = true,
                ),
            )
        }
        val data = buildJsonObject {
            put("address", address)
            put("line", line)
            put("body", body)
            threadRemoteId?.takeIf { it.isNotBlank() }?.let { put("threadRemoteId", it) }
        }
        return envelope(components, data)
    }

    /**
     * Menu card (design §3.2 item card): one row per item with a
     * [QuantityStepper] bound to that item's qty. Steppers only edit card
     * data; the single Update cart button (`cart.food.update`) snapshots
     * every qty back to the model in one turn.
     */
    fun foodMenu(merchantName: String, merchantId: Int, items: List<MenuItemRow>): String {
        val components = buildJsonArray {
            add(obj("id" to s("root"), "component" to s("Card"), "child" to s("col")))
            add(
                obj(
                    "id" to s("col"), "component" to s("Column"),
                    "children" to JsonArray(listOf(s("title"), s("items"), s("btnUpdate"))),
                ),
            )
            add(text("title", merchantName, "title"))
            add(
                obj(
                    "id" to s("items"), "component" to s("List"),
                    "children" to buildJsonObject {
                        put("componentId", "item")
                        put("path", "/items")
                    },
                ),
            )
            add(
                obj(
                    "id" to s("item"), "component" to s("Row"),
                    "children" to JsonArray(listOf(s("itemBody"), s("stepper"))),
                ),
            )
            add(
                obj(
                    "id" to s("itemBody"), "component" to s("Column"),
                    "children" to JsonArray(listOf(s("itemName"), s("itemPrice"))),
                ),
            )
            add(boundText("itemName", "/name", "body"))
            add(boundText("itemPrice", "/priceText", "caption"))
            add(
                obj(
                    "id" to s("stepper"), "component" to s("QuantityStepper"),
                    "value" to path("/qty"),
                    "min" to JsonPrimitive(0), "max" to JsonPrimitive(99),
                ),
            )
            add(
                actionButton(
                    "btnUpdate", "Update cart", "cart.food.update",
                    mapOf("merchantId" to JsonPrimitive(merchantId), "items" to pathValue("/items")),
                    primary = true,
                ),
            )
        }
        val data = buildJsonObject {
            put("merchantId", merchantId)
            put(
                "items",
                JsonArray(
                    items.map { item ->
                        buildJsonObject {
                            put("id", item.id)
                            put("name", item.name)
                            put("priceCents", item.priceCents)
                            put("priceText", formatCents(item.priceCents) + if (item.available) "" else " · unavailable")
                            put("qty", item.qty)
                        }
                    },
                ),
            )
        }
        return envelope(components, data)
    }

    /** Cart card: lines, total, and (when non-empty) a checkout handoff button. */
    fun foodCart(merchantName: String, merchantId: Int, items: List<CartLineRow>, totalCents: Int): String =
        cartCard(
            title = merchantName.ifBlank { "Your cart" },
            lines = items.map { "${it.name} × ${it.qty} — ${formatCents(it.lineTotalCents)}" },
            totalLine = "Total ${formatCents(totalCents)}",
            dataExtra = mapOf("merchantId" to JsonPrimitive(merchantId)),
        )

    /** Order-summary card: the primary button is the food checkout host handoff. */
    fun foodOrderSummary(
        merchantName: String,
        lines: List<String>,
        subtotalCents: Int,
        tipCents: Int,
        totalCents: Int,
        checkoutDeepLink: String,
        merchantId: Int,
    ): String {
        val allLines = lines + listOf(
            "Subtotal ${formatCents(subtotalCents)}",
            "Tip ${formatCents(tipCents)}",
            "Total ${formatCents(totalCents)} (final total confirmed in FoodDelivery)",
        )
        val lineIds = allLines.indices.map { "line$it" }
        val components = buildJsonArray {
            add(obj("id" to s("root"), "component" to s("Card"), "child" to s("col")))
            add(
                obj(
                    "id" to s("col"), "component" to s("Column"),
                    "children" to JsonArray(
                        listOf(s("title")) + lineIds.map { s(it) } + listOf(s("btnCheckout")),
                    ),
                ),
            )
            add(text("title", merchantName.ifBlank { "Order" }, "title"))
            allLines.forEachIndexed { i, line -> add(text("line$i", line, "body")) }
            add(
                actionButton(
                    "btnCheckout", "Place order in FoodDelivery", "handoff.food_checkout",
                    mapOf("deepLink" to s(checkoutDeepLink), "merchantId" to JsonPrimitive(merchantId)),
                    primary = true,
                ),
            )
        }
        val data = buildJsonObject {
            put("merchantId", merchantId)
            put("checkoutDeepLink", checkoutDeepLink)
            put("totalCents", totalCents)
        }
        return envelope(components, data)
    }

    private fun cartCard(
        title: String,
        lines: List<String>,
        totalLine: String,
        dataExtra: Map<String, JsonElement>,
    ): String {
        val lineIds = lines.indices.map { "line$it" }
        val components = buildJsonArray {
            add(obj("id" to s("root"), "component" to s("Card"), "child" to s("col")))
            add(
                obj(
                    "id" to s("col"), "component" to s("Column"),
                    "children" to JsonArray(listOf(s("title")) + lineIds.map { s(it) } + listOf(s("total"))),
                ),
            )
            add(text("title", title, "title"))
            lines.forEachIndexed { i, line -> add(text("line$i", line, "body")) }
            add(text("total", totalLine, "title"))
        }
        val data = buildJsonObject {
            put("title", title)
            dataExtra.forEach { (k, v) -> put(k, v) }
        }
        return envelope(components, data)
    }

    private fun actionButton(
        id: String,
        label: String,
        event: String,
        context: Map<String, JsonElement>,
        primary: Boolean,
    ): JsonObject = obj(
        "id" to s(id), "component" to s("Button"), "label" to s(label),
        "primary" to JsonPrimitive(primary),
        "action" to action(event, context),
    )

    /**
     * Web search results card (oa-web-design §A2UI): a List template repeating a
     * Row[Column["[n] title", snippet, domain], Read (web.read {index}), Open (web.open {url})].
     */
    fun searchResults(query: String, results: List<SearchResultItem>): String {
        val components = buildJsonArray {
            add(obj("id" to s("root"), "component" to s("Card"), "child" to s("col")))
            add(
                obj(
                    "id" to s("col"), "component" to s("Column"),
                    "children" to JsonArray(listOf(s("title"), s("results"))),
                ),
            )
            add(text("title", query, "title"))
            add(
                obj(
                    "id" to s("results"), "component" to s("List"),
                    "children" to buildJsonObject {
                        put("componentId", "result")
                        put("path", "/results")
                    },
                ),
            )
            add(
                obj(
                    "id" to s("result"), "component" to s("Row"),
                    "children" to JsonArray(listOf(s("resultBody"), s("btnRead"), s("btnOpen"))),
                ),
            )
            add(
                obj(
                    "id" to s("resultBody"), "component" to s("Column"),
                    "children" to JsonArray(listOf(s("resultTitle"), s("resultSnippet"), s("resultDomain"))),
                ),
            )
            add(boundText("resultTitle", "/displayTitle", "body"))
            add(boundText("resultSnippet", "/snippet", "caption"))
            add(boundText("resultDomain", "/domain", "caption"))
            add(
                obj(
                    "id" to s("btnRead"), "component" to s("Button"), "label" to s("Read"),
                    "action" to action("web.read", mapOf("index" to pathValue("/index"))),
                ),
            )
            add(
                obj(
                    "id" to s("btnOpen"), "component" to s("Button"), "label" to s("Open"),
                    "action" to action("web.open", mapOf("url" to pathValue("/url"))),
                ),
            )
        }
        val data = buildJsonObject {
            put("query", query)
            put(
                "results",
                JsonArray(
                    results.map { r ->
                        buildJsonObject {
                            put("index", r.index)
                            put("title", r.title)
                            put("displayTitle", "[${r.index}] ${r.title}")
                            put("snippet", r.snippet)
                            put("domain", r.domain)
                            put("url", r.url)
                        }
                    },
                ),
            )
        }
        return envelope(components, data)
    }

    private fun envelope(components: JsonArray, data: JsonObject): String =
        JsonObject(mapOf("components" to components, "data" to data)).toString()

    private fun text(id: String, value: String, variant: String): JsonObject =
        obj("id" to s(id), "component" to s("Text"), "text" to s(value), "variant" to s(variant))

    private fun boundText(id: String, path: String, variant: String): JsonObject =
        obj("id" to s(id), "component" to s("Text"), "text" to pathValue(path), "variant" to s(variant))

    private fun path(value: String): JsonObject = buildJsonObject { put("path", value) }

    private fun pathValue(value: String): JsonElement = path(value)

    private fun action(name: String, context: Map<String, JsonElement>): JsonObject =
        buildJsonObject {
            put(
                "event",
                buildJsonObject {
                    put("name", name)
                    put("context", JsonObject(context))
                },
            )
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
        "action" to action(event, mapOf("id" to s(pendingId))),
    )

    private fun s(value: String) = JsonPrimitive(value)
    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))
}
