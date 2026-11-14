package com.vayunmathur.pdf

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.pdf.R
import androidx.compose.ui.unit.dp
import com.vayunmathur.pdf.ui.CapturePdfScreen
import com.vayunmathur.pdf.ui.CutGlueScreen
import com.vayunmathur.pdf.ui.SafePdfViewerScreen
import com.vayunmathur.pdf.util.PdfViewModel

/** Main Activity: a ma activity implementation for main activity. */
class MainActivity : MaActivity() {
    private val pdfViewModel: PdfViewModel by viewModels()
    private var intentData: Uri? = null

    override fun onCreateBeforeContent() {
        intentData = intent.data
    }

    @Composable
    override fun Content() {
        val startedWithIntent = remember { intentData != null }
        var data: Uri? by rememberSaveable { mutableStateOf(intentData) }
        var isCapturing by rememberSaveable { mutableStateOf(false) }
        var isCutGlue by rememberSaveable { mutableStateOf(false) }

        val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { data = it }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            if (isCutGlue) {
                CutGlueScreen(onBack = { isCutGlue = false })
            } else if (isCapturing) {
                CapturePdfScreen(
                    viewModel = pdfViewModel,
                    onBack = { isCapturing = false },
                    onPdfCreated = { uri ->
                        data = uri
                        isCapturing = false
                    }
                )
            } else if (data != null) {
                SafePdfViewerScreen(
                    uri = data!!,
                    onBack = {
                        if (startedWithIntent) finish() else data = null
                    }
                )
            } else {
                InitialScreen(
                    onOpenPdf = { filePickerLauncher.launch(arrayOf("application/pdf")) },
                    onCapturePdf = { isCapturing = true },
                    onCutGlue = { isCutGlue = true }
                )
            }
        }
    }
}

@Composable
fun InitialScreen(onOpenPdf: () -> Unit, onCapturePdf: () -> Unit, onCutGlue: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Button(onClick = onOpenPdf, Modifier.padding(16.dp)) {
                Text(stringResource(R.string.open_pdf))
            }
            Button(onClick = onCapturePdf, Modifier.padding(16.dp)) {
                Text(stringResource(R.string.capture_pdf_title))
            }
            Button(onClick = onCutGlue, Modifier.padding(16.dp)) {
                Text(stringResource(R.string.pdf_cut_and_glue))
            }
        }
    }
}
