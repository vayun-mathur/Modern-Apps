package com.vayunmathur.youpipe.util.sabr

import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat
import java.io.File
import java.util.TreeMap

internal data class SabrDownloadTarget(
    val resourceIndex: Int,
    val format: YoutubeSabrFormat,
    val file: File,
    var nextWriteSequence: Int = 1,
    var initializationWritten: Boolean = false,
    var initializationObserved: Boolean = false,
    var initializationData: ByteArray? = null,
    val pending: TreeMap<Int, ByteArray> = TreeMap(),
    var pendingBytes: Long = 0,
)
