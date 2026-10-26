package com.vayunmathur.logviewer.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.logviewer.domain.LogKind
import com.vayunmathur.logviewer.domain.LogLevel
import com.vayunmathur.logviewer.platform.LogViewerActions
import com.vayunmathur.logviewer.platform.LogViewerUiState
import com.vayunmathur.logviewer.platform.LogcatFilters

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Expanded desktop/tablet window — comfortably above the 840dp expanded-width threshold. */
private const val EXPANDED = "spec:width=1280dp,height=800dp,dpi=240"

/** Preview-only actions: every callback is a no-op. */
private object PreviewActions : LogViewerActions {
    override fun copy() {}
    override fun share() {}
    override fun report() {}
    override fun save(uri: Uri) {}
    override fun zoom(factor: Float) {}
    override fun setBuffers(buffers: List<String>) {}
    override fun setLevel(level: LogLevel) {}
    override fun setFilter(regex: String) {}
    override fun perform(action: com.vayunmathur.logviewer.platform.ExtraAction) {}
    override fun dismissStackTrace() {}
    override fun copyStackTrace() {}
}

private val SAMPLE_LINES = listOf(
    "beginning of crash",
    "Build fingerprint: 'google/cheetah/cheetah:14/UP1A.231105.003/11010345:user/release-keys'",
    "Revision: 'MP1.0'",
    "ABI: 'arm64'",
    "Timestamp: 2026-07-15 14:32:11.408+0100",
    "Process uptime: 0s",
    "Cmdline: com.vayunmathur.weather",
    "pid: 1234, tid: 1234, name: vayunmathur.weather  >>> com.vayunmathur.weather <<<",
    "signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0",
    "Cause: null pointer dereference",
    "    #00 pc 0000000000012345  /apex/com.android.runtime/lib64/bionic/libc.so (strlen+52)",
    "    #01 pc 0000000000067890  /data/app/com.vayunmathur.weather/lib/arm64/libweather.so",
)

private val SAMPLE_TRACE = """java.lang.NullPointerException: Attempt to invoke virtual method 'int java.lang.String.length()' on a null object reference
	at com.vayunmathur.weather.platform.WeatherViewModel.refreshAll(WeatherViewModel.kt:42)
	at com.vayunmathur.weather.ui.HomePageKt${'$'}HomePage${'$'}3.invokeSuspend(HomePage.kt:118)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:33)"""

/**
 * Store listing images for `:logviewer`. See `common-conventions-preview-metadata`.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1…, Preview2…).
 *
 * Everything here is a literal — no logcat, no tombstone file — which is also what makes
 * the images reproducible from a clean checkout.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-report", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Report() {
        DynamicTheme(darkTheme = true) {
            LogViewerScreen(
                state = LogViewerUiState(
                    loading = false,
                    title = "Weather crashed",
                    lines = SAMPLE_LINES,
                    kind = LogKind.ErrorReport,
                    fontSizeSp = LogKind.ErrorReport.initialFontSizeSp,
                    showReportButton = true,
                    snapshotFileName = "weather-crash.txt",
                ),
                actions = PreviewActions,
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-expanded", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    fun Preview2Expanded() {
        DynamicTheme(darkTheme = true) {
            // Expanded windows render the log lines and the "unable to save"
            // stack trace side by side instead of stacking a modal dialog
            // over the log.
            LogViewerScreen(
                state = LogViewerUiState(
                    loading = false,
                    title = "Weather crashed",
                    lines = SAMPLE_LINES,
                    kind = LogKind.ErrorReport,
                    fontSizeSp = LogKind.ErrorReport.initialFontSizeSp,
                    showReportButton = true,
                    snapshotFileName = "weather-crash.txt",
                    stackTrace = SAMPLE_TRACE,
                ),
                actions = PreviewActions,
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-logcat", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3Logcat() {
        DynamicTheme(darkTheme = true) {
            LogViewerScreen(
                state = LogViewerUiState(
                    loading = false,
                    title = "Logcat — Weather",
                    lines = listOf(
                        "07-15 14:32:11.408  1234  1234 I WeatherViewModel: refreshing all locations",
                        "07-15 14:32:11.512  1234  1287 D WeatherApi: GET /v1/forecast?latitude=51.51&longitude=-0.13",
                        "07-15 14:32:12.003  1234  1287 W WeatherApi: retry 1/3 after 500ms",
                        "07-15 14:32:12.611  1234  1287 I WeatherApi: forecast cached for London",
                        "07-15 14:32:12.612  1234  1234 I WeatherViewModel: refresh complete",
                    ),
                    kind = LogKind.Logcat,
                    fontSizeSp = LogKind.Logcat.initialFontSizeSp,
                    snapshotFileName = "weather-logcat.txt",
                    logcat = LogcatFilters(
                        buffers = listOf("main"),
                        level = LogLevel.Info,
                        filterRegex = "",
                    ),
                ),
                actions = PreviewActions,
            )
        }
    }
}
