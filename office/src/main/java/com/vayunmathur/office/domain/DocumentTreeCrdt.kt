package com.vayunmathur.office.domain

import com.vayunmathur.office.util.OfficeNative
/**
 * Thin handle wrapper over the native `office_engine` document-tree CRDT.
 *
 * The CRDT algorithm (hierarchical tree over flat-ODF XML, RGA text merge, LWW
 * attributes) lives in the Rust crate; this class only owns a native handle and
 * forwards calls. The native serialization is byte-identical to the previous
 * Kotlin serializer, so the signed collaboration wire format is unchanged.
 *
 * Call [close] to release the native handle when the CRDT is no longer needed.
 */
class DocumentTreeCrdt(device: String) {
    private val handle: Long = OfficeNative.crdtNew(device)

    fun loadState(json: String) = OfficeNative.crdtLoadState(handle, json)

    fun serialize(): String = OfficeNative.crdtSerialize(handle)

    /** Merge a batch of remote ops (a JSON array of Node). */
    fun applyJson(opsJson: String) = OfficeNative.crdtApply(handle, opsJson)

    /** Reconcile toward [xml]; returns produced ops as a JSON array (or "[]"). */
    fun updateJson(xml: String): String = OfficeNative.crdtUpdate(handle, xml)

    fun render(): String = OfficeNative.crdtRender(handle)

    /** JSON array of the current state's nodes (for re-baseline snapshots). */
    fun toStateNodesJson(): String = OfficeNative.crdtToStateNodesJson(handle)

    fun close() = OfficeNative.crdtFree(handle)
}
