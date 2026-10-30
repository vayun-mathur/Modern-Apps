package com.vayunmathur.openassistant.ui

import androidx.a2ui.engine.model.A2uiCoreSurfaceModel
import androidx.a2ui.engine.model.A2uiCoreValueResolver
import androidx.a2ui.model.protocol.A2uiDataPath
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.openassistant.platform.ComposeComponentRegistry
import com.vayunmathur.openassistant.platform.ComposeDataModel

private const val ROOT_ID = "root"
private const val MAX_DEPTH = 12

/**
 * Draws an A2UI surface from its `root` component.
 *
 * Props are evaluated through the engine, so `{"path": ...}` bindings and catalog function
 * calls work anywhere a string is accepted; reads go through [ComposeDataModel]'s snapshot
 * state, so edits recompose whatever is bound to them.
 */
@Composable
fun A2uiSurfaceView(surface: A2uiCoreSurfaceModel, modifier: Modifier = Modifier) {
    val registry = surface.componentRegistry as? ComposeComponentRegistry ?: return
    val data = surface.dataModel as? ComposeDataModel ?: return
    val renderer = remember(surface) { SurfaceRenderer(surface, registry, data) }
    Box(modifier.widthIn(max = 400.dp)) { renderer.Node(ROOT_ID, A2uiDataPath("/"), 0) }
}

private class SurfaceRenderer(
    val surface: A2uiCoreSurfaceModel,
    val registry: ComposeComponentRegistry,
    val data: ComposeDataModel,
) {
    private val resolver = A2uiCoreValueResolver { data[it] }

    fun evaluate(componentId: String, dataPath: A2uiDataPath, payload: Any?): Any? =
        surface.evaluatePayload(componentId, resolver, dataPath, payload)

    fun string(componentId: String, dataPath: A2uiDataPath, payload: Any?): String =
        when (val value = evaluate(componentId, dataPath, payload)) {
            null -> ""
            is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
            else -> value.toString()
        }

    fun boundPath(dataPath: A2uiDataPath, binding: Any?): A2uiDataPath? =
        ((binding as? Map<*, *>)?.get("path") as? String)?.let { dataPath / it }

    /**
     * Fires [action] with its event context already resolved at [dataPath].
     *
     * The engine resolves context at the surface root, which would point a button inside a
     * list template at the wrong item; resolving here first leaves it only literals.
     */
    @Suppress("UNCHECKED_CAST")
    fun dispatch(componentId: String, dataPath: A2uiDataPath, action: Any?) {
        val map = action as? Map<String, Any?> ?: return
        val event = map["event"] as? Map<String, Any?>
        val context = event?.get("context") as? Map<String, Any?>
        val resolved = if (event == null || context == null) {
            map
        } else {
            val literal = context.mapValues { evaluate(componentId, dataPath, it.value) }
            mapOf("event" to event + ("context" to literal))
        }
        surface.dispatchAction(componentId, resolved)
    }

    @Composable
    fun Node(id: String, dataPath: A2uiDataPath, depth: Int) {
        if (depth > MAX_DEPTH) return
        registry.errors[id]?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            return
        }
        val component = registry.components[id] ?: return
        val props = component.properties
        val str = { key: String -> string(id, dataPath, props[key]) }
        when (component.type) {
            "Text" -> Text(
                str("text"),
                style = when (props["variant"]) {
                    "title" -> MaterialTheme.typography.titleMedium
                    "caption" -> MaterialTheme.typography.bodySmall
                    else -> MaterialTheme.typography.bodyMedium
                },
            )
            "Column", "List" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Children(props["children"], dataPath, depth)
            }
            "Row" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Children(props["children"], dataPath, depth)
            }
            "Card" -> Card(Modifier.fillMaxWidth()) {
                Box(Modifier.padding(16.dp)) {
                    (props["child"] as? String)?.let { Node(it, dataPath, depth + 1) }
                }
            }
            "Button" -> {
                val onClick = { dispatch(id, dataPath, props["action"]) }
                if (props["primary"] == true) {
                    Button(onClick) { Text(str("label")) }
                } else {
                    OutlinedButton(onClick) { Text(str("label")) }
                }
            }
            "TextField" -> OutlinedTextField(
                value = str("value"),
                onValueChange = { text -> boundPath(dataPath, props["value"])?.let { data.update(it, text) } },
                modifier = Modifier.fillMaxWidth(),
                label = props["label"]?.let { { Text(str("label")) } },
                singleLine = true,
            )
            "CheckBox" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = evaluate(id, dataPath, props["value"]) == true,
                    onCheckedChange = { checked -> boundPath(dataPath, props["value"])?.let { data.update(it, checked) } },
                )
                Text(str("label"))
            }
            "Image" -> AsyncImage(
                ImageRequest.Builder(LocalContext.current).data(str("url")).build(),
                str("description").ifEmpty { null },
                Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
            )
            "Divider" -> HorizontalDivider()
        }
    }

    /** A static id list, or a `{componentId, path}` template repeated per item of a data list. */
    @Composable
    fun Children(children: Any?, dataPath: A2uiDataPath, depth: Int) {
        when (children) {
            is List<*> -> children.forEach { child -> (child as? String)?.let { Node(it, dataPath, depth + 1) } }
            is Map<*, *> -> {
                val template = children["componentId"] as? String ?: return
                val listPath = (children["path"] as? String)?.let { dataPath / it } ?: return
                val items = data[listPath] as? List<*> ?: return
                items.indices.forEach { Node(template, listPath / it.toString(), depth + 1) }
            }
        }
    }
}
