package com.vayunmathur.youpipe.util.sabr

import java.io.IOException

internal class SabrDownloadException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {
    enum class Reason {
        FORMAT,
        INITIALIZATION,
        PROTECTED,
        STALLED,
        NETWORK,
        MUXING,
        STORAGE,
        PROTOCOL,
    }
}
