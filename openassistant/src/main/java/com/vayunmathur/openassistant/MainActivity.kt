package com.vayunmathur.openassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.InitialDownloadChecker
import com.vayunmathur.library.util.DataStoreUtils
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val ds = DataStoreUtils.getInstance(this)

        setContent {
            DynamicTheme {
                InitialDownloadChecker(ds, listOf(
                    Triple("https://huggingface.co/charlesLoder/gemma-3n-E4B-it-litert-lm/resolve/main/gemma-3n-E4B-it-int4.litertlm", "model.litertlm", "Model"),
                )) {
                    // Once downloads are complete, find the file in internal storage
                    val modelFile = File(getExternalFilesDir(null)!!, "model.litertlm")

                    if (modelFile.exists()) {
                        LiteRTChatUi(modelFile)
                    } else {
                        Text("Model file not found.")
                    }
                }
            }
        }
    }
}