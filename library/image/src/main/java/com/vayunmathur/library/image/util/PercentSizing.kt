package com.vayunmathur.library.image.util

import java.io.File

fun File.ensureExists(): File {
    if (!exists()) mkdirs()
    return this
}
