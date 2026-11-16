package com.vayunmathur.pdf

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
/** Navigation destinations for the PDF viewer, capture and cut-and-glue flows. */
sealed interface Route : NavKey {
    @Serializable
    /** The start screen: open a document, capture one, or cut-and-glue. */
    data object Home : Route

    @Serializable
    /** The document viewer; the URI is the document identity (a string so the route serializes). */
    data class Viewer(val uri: String) : Route

    @Serializable
    /** Camera capture with no page selected yet. */
    data object Capture : Route

    @Serializable
    /** One captured page, previewed inside capture. */
    data class CapturePage(val index: Int) : Route

    @Serializable
    /** The crop editor for one captured page, over its preview. */
    data class Crop(val index: Int) : Route

    @Serializable
    /** Cut-and-glue assembly of PDFs and images into a new document. */
    data object CutGlue : Route
}
