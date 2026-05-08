package com.vayunmathur.dialer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.dialer.ui.DialerScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DynamicTheme {
                DialerScreen()
            }
        }
    }
}
