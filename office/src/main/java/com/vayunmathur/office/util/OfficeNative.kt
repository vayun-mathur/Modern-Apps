package com.vayunmathur.office.util

import com.vayunmathur.office.domain.DocumentTreeCrdt
import com.vayunmathur.office.platform.OfficeViewModel
// PACKAGE STRUCTURE EXCEPTION (JNI)
// OfficeNative's fully-qualified name is frozen: the Rust office_engine crate binds to it
// via Java_com_vayunmathur_office_util_OfficeNative_* JNI symbols, so it cannot move to a
// canonical package without breaking native linkage.

import com.vayunmathur.library.ui.odf.OdfNamedRange
import com.vayunmathur.library.ui.odf.OdfNumberFormat
import com.vayunmathur.library.ui.odf.OdfSheet
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JNI bridge to the native `office_engine` Rust library: the ODF formula engine
 * and the document-tree CRDT, which now own these features outright (the former
 * Kotlin implementations have been deleted).
 *
 * ## CRDT wire format (SIGNED — do not change casually)
 * The Rust CRDT serializes Node/State byte-for-byte identically to the previous
 * Kotlin `kotlinx.serialization` output used on the collaboration wire
 * (`Json { ignoreUnknownKeys = true }`, i.e. `encodeDefaults = false` — default
 * fields `deleted`/`name`/`attrLamport`/`attrDev` are OMITTED). This was verified
 * with an exact-string round-trip test in the crate (`crdt::tests`). The
 * signed-collaboration path in [OfficeViewModel] signs the native engine's ops
 * JSON directly; [DocumentTreeCrdt] is a thin handle wrapper over these calls.
 *
 * ## Formula engine
 * Build a workbook handle once per sheet snapshot via [createWorkbook] (serialize
 * all sheets with [buildWorkbookJson]), then call [nativeDisplayValue]/
 * [nativeIsNumeric], and [nativeFree] when the snapshot changes. Non-deterministic
 * funcs (TODAY/NOW/RAND) read [nowMillis]. [nativeFormatValue] formats a lone
 * number with no workbook (XLSX cell display).
 */
object OfficeNative {
    /** False when the native lib is absent for this ABI; callers must fall back, not crash. */
    val isAvailable: Boolean = try {
        System.loadLibrary("office_engine")
        true
    } catch (expected: UnsatisfiedLinkError) {
        false
    }

    // ---- Document tree CRDT ----

    /** Creates a native CRDT for [device]; returns a handle (0 on failure). */
    external fun crdtNew(device: String): Long

    /** Replaces the CRDT state from a serialized [State] JSON (ignores parse errors). */
    external fun crdtLoadState(handle: Long, json: String)

    /** Serializes the full [State] (byte-identical to the Kotlin serializer). */
    external fun crdtSerialize(handle: Long): String

    /** Merges a batch of remote node ops (a JSON array of Node). Commutative + idempotent. */
    external fun crdtApply(handle: Long, opsJson: String)

    /** Reconciles toward [xml], returning the produced ops as a JSON array of Node. */
    external fun crdtUpdate(handle: Long, xml: String): String

    /** Renders the current tree back to flat XML. */
    external fun crdtRender(handle: Long): String

    /** Serializes just the current state's node array (JSON array of Node). */
    external fun crdtToStateNodesJson(handle: Long): String

    /** Releases the CRDT. */
    external fun crdtFree(handle: Long)

    // ---- ODF formula engine ----

    /**
     * Parses a compact workbook JSON (see [buildWorkbookJson]) into a native
     * workbook; returns a handle (0 on failure). [nowMillis] seeds TODAY/NOW/RAND.
     */
    external fun nativeCreateWorkbook(json: String, nowMillis: Long): Long

    /** Display string for a cell (empty string out of range). */
    external fun nativeDisplayValue(handle: Long, sheetIdx: Int, row: Int, col: Int): String?

    /** Whether the (evaluated) cell holds a numeric value. */
    external fun nativeIsNumeric(handle: Long, sheetIdx: Int, row: Int, col: Int): Boolean

    /** Releases a workbook. */
    external fun nativeFree(handle: Long)

    /**
     * Formats [value] using a serialized [OdfNumberFormat] ([numberFormatJson]);
     * `"null"` or empty means "no format" (plain number). Standalone number
     * formatter for callers with no workbook context.
     */
    external fun nativeFormatValue(value: Double, numberFormatJson: String): String

    /** Formats [value] with an optional [fmt], serializing it to the NfDto JSON the Rust engine parses. */
    fun formatValue(value: Double, fmt: OdfNumberFormat?): String =
        nativeFormatValue(value, fmt?.let { json.encodeToString(nfDtoOf(it)) } ?: "null")

    /**
     * Convenience: serialize [sheets] and create a native workbook handle, or
     * return 0 if the native lib is unavailable or creation fails.
     */
    fun createWorkbook(
        sheets: List<OdfSheet>,
        nowMillis: Long = System.currentTimeMillis(),
        namedRanges: List<OdfNamedRange> = emptyList(),
    ): Long = if (isAvailable) nativeCreateWorkbook(buildWorkbookJson(sheets, namedRanges), nowMillis) else 0

    // ---- Workbook serialization (must agree with the serde schema in formula.rs) ----

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    /** Serializes all [sheets] (name + 2D cell grid) to the compact JSON the Rust engine parses. */
    fun buildWorkbookJson(sheets: List<OdfSheet>, namedRanges: List<OdfNamedRange> = emptyList()): String {
        val dto = WbDto(sheets.map { s ->
            SheetDto(s.name, s.rows.map { r ->
                RowDto(r.cells.map { c ->
                    CellDto(
                        text = c.text,
                        formula = c.formula,
                        numberValue = c.numberValue,
                        valueType = c.valueType,
                        isCovered = c.isCovered,
                        numberFormat = c.numberFormat?.let { nfDtoOf(it) }
                    )
                })
            })
        }, namedRanges.map { NrDto(it.name, it.cellRangeAddress) })
        return json.encodeToString(dto)
    }

    /** Maps an [OdfNumberFormat] to the compact NfDto the Rust engine parses. */
    private fun nfDtoOf(nf: OdfNumberFormat): NfDto = NfDto(
        decimals = nf.decimals,
        percent = nf.percent,
        currencySymbol = nf.currencySymbol,
        grouping = nf.grouping,
        isDate = nf.isDate,
        isTime = nf.isTime,
        isScientific = nf.isScientific,
        isFraction = nf.isFraction,
        fractionDenominatorDigits = nf.fractionDenominatorDigits,
        dateTimeTokens = nf.dateTimeTokens.map { t -> TokDto(t.kind, t.style, t.text, t.textual) }
    )

    @Serializable private data class WbDto(
        val sheets: List<SheetDto>,
        val namedRanges: List<NrDto> = emptyList(),
    )
    @Serializable private data class SheetDto(val name: String, val rows: List<RowDto>)
    @Serializable private data class RowDto(val cells: List<CellDto>)
    @Serializable private data class NrDto(val name: String, val addr: String)

    @Serializable private data class CellDto(
        val text: String,
        val formula: String? = null,
        val numberValue: Double? = null,
        val valueType: String? = null,
        val isCovered: Boolean = false,
        val numberFormat: NfDto? = null,
    )

    @Serializable private data class NfDto(
        val decimals: Int? = null,
        val percent: Boolean = false,
        val currencySymbol: String? = null,
        val grouping: Boolean = false,
        val isDate: Boolean = false,
        val isTime: Boolean = false,
        val isScientific: Boolean = false,
        val isFraction: Boolean = false,
        val fractionDenominatorDigits: Int = 1,
        val dateTimeTokens: List<TokDto> = emptyList(),
    )

    @Serializable private data class TokDto(
        val kind: String,
        val style: String? = null,
        val text: String? = null,
        val textual: Boolean = false,
    )
}
