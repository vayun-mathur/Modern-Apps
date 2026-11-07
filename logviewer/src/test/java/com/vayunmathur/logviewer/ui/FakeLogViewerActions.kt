package com.vayunmathur.logviewer.ui

import android.net.Uri
import com.vayunmathur.logviewer.domain.LogLevel
import com.vayunmathur.logviewer.platform.ExtraAction
import com.vayunmathur.logviewer.platform.LogViewerActions

/** Recording [LogViewerActions] for Compose tests. */
internal class FakeLogViewerActions : LogViewerActions {
    var copies = 0
    var shares = 0
    var reports = 0
    val saves = mutableListOf<Uri>()
    val zooms = mutableListOf<Float>()
    val buffersSet = mutableListOf<List<String>>()
    val levelsSet = mutableListOf<LogLevel>()
    val filtersSet = mutableListOf<String>()
    val performed = mutableListOf<ExtraAction>()
    var stackTraceDismissals = 0
    var stackTraceCopies = 0

    override fun copy() { copies++ }
    override fun share() { shares++ }
    override fun report() { reports++ }
    override fun save(uri: Uri) { saves += uri }
    override fun zoom(factor: Float) { zooms += factor }
    override fun setBuffers(buffers: List<String>) { buffersSet += buffers }
    override fun setLevel(level: LogLevel) { levelsSet += level }
    override fun setFilter(regex: String) { filtersSet += regex }
    override fun perform(action: ExtraAction) { performed += action }
    override fun dismissStackTrace() { stackTraceDismissals++ }
    override fun copyStackTrace() { stackTraceCopies++ }
}
