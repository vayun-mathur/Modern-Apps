package com.vayunmathur.openassistant.platform

import androidx.a2ui.engine.catalog.A2uiCoreCatalog
import androidx.a2ui.engine.catalog.A2uiCoreComponentDefinition
import androidx.a2ui.engine.catalog.A2uiCoreComponentDefinitionCollection
import androidx.a2ui.model.catalog.A2uiFunctionCollection
import androidx.a2ui.model.catalog.basiccatalog.createBasicCatalogFunctions
import androidx.a2ui.model.catalog.functions.A2uiMessageFormatter
import androidx.a2ui.model.catalog.functions.A2uiUrlOpener
import androidx.a2ui.model.schema.A2uiBooleanSchema
import androidx.a2ui.model.schema.A2uiObjectSchema
import androidx.a2ui.model.schema.A2uiSchema
import androidx.a2ui.model.schema.A2uiSchemaKeyword
import androidx.a2ui.model.schema.A2uiStringSchema
import androidx.a2ui.model.schema.commontypes.A2uiActionSchema
import androidx.a2ui.model.schema.commontypes.A2uiChildListSchema
import androidx.a2ui.model.schema.commontypes.A2uiComponentIdSchema
import androidx.a2ui.model.schema.commontypes.A2uiDataBindingSchema
import androidx.a2ui.model.schema.commontypes.A2uiDynamicStringSchema
import com.vayunmathur.openassistant.domain.A2UI_FENCE_OPEN

/**
 * The components the model may put in an A2UI block, and the prompt that teaches them.
 *
 * Each prompt line is generated from the same [Component] that carries the schema, so the
 * model is never told about a prop the validator would reject. Extra props are allowed:
 * a small model adds stray ones, and failing a whole component over them helps nobody.
 */
class AssistantCatalog(urlOpener: A2uiUrlOpener, messageFormatter: A2uiMessageFormatter) : A2uiCoreCatalog {
    override val id: String = ID
    override val title: String = "OpenAssistant"
    override val componentDefinitions: A2uiCoreComponentDefinitionCollection =
        A2uiCoreComponentDefinitionCollection(COMPONENTS)
    override val functions: A2uiFunctionCollection =
        A2uiFunctionCollection(createBasicCatalogFunctions(urlOpener, messageFormatter))

    private class Component(
        override val name: String,
        override val description: String,
        val promptProps: String,
        props: Map<String, A2uiSchema> = emptyMap(),
        required: Set<String> = props.keys,
    ) : A2uiCoreComponentDefinition {
        override val propertySchema: A2uiSchema = A2uiObjectSchema(properties = props, required = required)
    }

    companion object {
        const val ID = "https://vayunmathur.com/openassistant/a2ui/catalog/v1"

        private val text = A2uiDynamicStringSchema.DEFAULT_INSTANCE
        private val children = mapOf("children" to A2uiChildListSchema.DEFAULT_INSTANCE)
        private val binding = A2uiDataBindingSchema.DEFAULT_INSTANCE
        private val variant = A2uiStringSchema(
            keywords = listOf(A2uiSchemaKeyword.Enum(listOf("title", "body", "caption"))),
        )
        private const val BINDING_PROPS = "label, value {\"path\":\"/field\"}"

        private val COMPONENTS: List<Component> = listOf(
            Component(
                "Text", "A run of text.", "text, variant (\"title\"|\"body\"|\"caption\")",
                mapOf(
                    "text" to text,
                    "variant" to variant,
                ),
                required = setOf("text"),
            ),
            Component("Column", "Children stacked vertically.", "children (array of ids)", children),
            Component("Row", "Children side by side.", "children (array of ids)", children),
            Component("List", "A vertical list of items.", "children (array of ids)", children),
            Component(
                "Card", "A raised container around one child.", "child (one id)",
                mapOf("child" to A2uiComponentIdSchema.DEFAULT_INSTANCE),
            ),
            Component(
                "Button", "A tappable button that sends an event back to the assistant.",
                "label, action {\"event\":{\"name\":\"...\",\"context\":{...}}}, primary (true|false)",
                mapOf(
                    "label" to text,
                    "action" to A2uiActionSchema.DEFAULT_INSTANCE,
                    "primary" to A2uiBooleanSchema.INSTANCE,
                ),
                required = setOf("label", "action"),
            ),
            Component(
                "TextField", "A single-line text input bound to the data model.", BINDING_PROPS,
                mapOf("label" to text, "value" to binding),
                required = setOf("value"),
            ),
            Component(
                "CheckBox", "A checkbox bound to a boolean in the data model.", BINDING_PROPS,
                mapOf("label" to text, "value" to binding),
            ),
            Component(
                "Image", "An image from a URL.", "url, description",
                mapOf("url" to text, "description" to text),
                required = setOf("url"),
            ),
            Component("Divider", "A thin horizontal rule.", "(no props)"),
        )

        val COMPONENT_NAMES: List<String> = COMPONENTS.map { it.name }

        const val EXAMPLE = """{"components":[{"id":"root","component":"Card","child":"c"},""" +
            """{"id":"c","component":"Column","children":["q","n","v","b"]},""" +
            """{"id":"q","component":"Text","text":"Who's coming?","variant":"title"},""" +
            """{"id":"n","component":"TextField","label":"Name","value":{"path":"/name"}},""" +
            """{"id":"v","component":"CheckBox","label":"Vegetarian","value":{"path":"/veg"}},""" +
            """{"id":"b","component":"Button","label":"RSVP","primary":true,""" +
            """"action":{"event":{"name":"rsvp","context":{"name":{"path":"/name"},"veg":{"path":"/veg"}}}}}],""" +
            """"data":{"name":"","veg":false}}"""

        /** Appended to the system prompt; describes exactly [COMPONENTS]. */
        val PROMPT_SECTION: String = buildString {
            appendLine("RICH UI:")
            appendLine(
                "When a card, list, set of choices or form would help more than plain text " +
                    "(collecting details, offering options, showing structured results), " +
                    "add ONE block after your text:",
            )
            appendLine(A2UI_FENCE_OPEN)
            appendLine("""{"components":[...],"data":{...}}""")
            appendLine("```")
            appendLine("""Each component is {"id":"...","component":"<Type>", ...props}.""")
            appendLine("""The component with id "root" is shown. Types:""")
            COMPONENTS.forEach { appendLine("- ${it.name}: ${it.promptProps}") }
            appendLine("""Any text may be {"path":"/field"} to show a data value. "data" sets initial values.""")
            appendLine("""Button context values may be {"path":"/field"} to send what the user entered.""")
            appendLine("""When the user taps a button you receive a turn like [UI action "name": {...}].""")
            appendLine("Respond to it.")
            appendLine("Keep blocks small (under 15 components). Never use a block for a plain answer. Example:")
            appendLine(A2UI_FENCE_OPEN)
            appendLine(EXAMPLE)
            append("```")
        }
    }
}
