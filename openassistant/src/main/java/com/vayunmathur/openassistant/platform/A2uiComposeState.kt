package com.vayunmathur.openassistant.platform

import androidx.a2ui.engine.platform.A2uiCoreComponentRegistry
import androidx.a2ui.engine.platform.A2uiCoreDataModel
import androidx.a2ui.model.protocol.A2uiComponentPayload
import androidx.a2ui.model.protocol.A2uiDataPath
import androidx.a2ui.model.protocol.A2uiException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot

/** Components and per-component errors in snapshot state, so composition follows engine updates. */
class ComposeComponentRegistry : A2uiCoreComponentRegistry {
    val components = mutableStateMapOf<String, A2uiComponentPayload>()
    val errors = mutableStateMapOf<String, String>()

    override fun update(components: List<A2uiComponentPayload>) = Snapshot.withMutableSnapshot {
        for (component in components) {
            this.components[component.id] = component
            errors.remove(component.id)
        }
    }

    override fun reportError(id: String, exception: A2uiException) {
        errors[id] = exception.message ?: exception.code
    }

    override fun close() = Snapshot.withMutableSnapshot {
        components.clear()
        errors.clear()
    }
}

/**
 * The surface's JSON tree as one immutable value in snapshot state.
 *
 * Every write copies the path it touches, so a reader in composition always sees a
 * consistent tree. Writes come from both the engine's actor and the UI thread (text
 * fields, checkboxes), hence the lock around read-modify-write.
 */
class ComposeDataModel : A2uiCoreDataModel {
    private var root: Any? by mutableStateOf(emptyMap<String, Any?>())
    private val lock = Any()

    override fun update(path: A2uiDataPath, value: Any?) {
        synchronized(lock) { root = setAt(root, path.segments, 0, value) ?: emptyMap<String, Any?>() }
    }

    override fun get(path: A2uiDataPath): Any? {
        var node = root
        for (segment in path.segments) {
            node = when (node) {
                is Map<*, *> -> node[segment]
                is List<*> -> segment.toIntOrNull()?.let { node.getOrNull(it) }
                else -> null
            } ?: return null
        }
        return node
    }

    override fun close() {
        synchronized(lock) { root = emptyMap<String, Any?>() }
    }

    private fun setAt(node: Any?, segments: List<String>, at: Int, value: Any?): Any? {
        if (at == segments.size) return value
        val key = segments[at]
        val index = key.toIntOrNull()
        if (node is List<*> && index != null && index >= 0) {
            val list = node.toMutableList()
            while (list.size <= index) list.add(null)
            list[index] = setAt(list[index], segments, at + 1, value)
            return list
        }
        val map = (node as? Map<*, *>)?.entries?.associateTo(LinkedHashMap()) { (k, v) -> k.toString() to v }
            ?: LinkedHashMap()
        val child = setAt(map[key], segments, at + 1, value)
        if (child == null && at + 1 == segments.size) map.remove(key) else map[key] = child
        return map
    }
}
