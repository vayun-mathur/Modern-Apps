package com.vayunmathur.logviewer.intents

import kotlin.test.Test
import kotlin.test.assertEquals

class LogViewerIntentsTest {

    @Test
    fun `wire constants match the framework contract`() {
        assertEquals("com.vayunmathur.logviewer", LogViewerIntents.PACKAGE_NAME)
        assertEquals("com.vayunmathur.logviewer.ERROR_REPORT", LogViewerIntents.ACTION_ERROR_REPORT)
        assertEquals("com.vayunmathur.logviewer.LOGCAT", LogViewerIntents.ACTION_LOGCAT)
        assertEquals("com.vayunmathur.logviewer.PKG_LOGCAT", LogViewerIntents.ACTION_PKG_LOGCAT)
        assertEquals("type", LogViewerIntents.EXTRA_ERROR_TYPE)
        assertEquals("gzipped_msg", LogViewerIntents.EXTRA_GZIPPED_MESSAGE)
        assertEquals("source_app_info", LogViewerIntents.EXTRA_SOURCE_APP_INFO)
        assertEquals("show_report_button", LogViewerIntents.EXTRA_SHOW_REPORT_BUTTON)
        assertEquals("text_tombstone_file_path", LogViewerIntents.EXTRA_TEXT_TOMBSTONE_FILE_PATH)
        assertEquals("text_tombstone_last_modified", LogViewerIntents.EXTRA_TEXT_TOMBSTONE_LAST_MODIFIED_TIME)
        assertEquals("prefer_text_tombstone", LogViewerIntents.EXTRA_PREFER_TEXT_TOMBSTONE)
    }
}
