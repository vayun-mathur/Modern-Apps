package com.vayunmathur.passwords.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.passwords.Route

@Composable
fun MenuPage(backStack: NavBackStack<Route>) {
    Scaffold() { paddingValues ->
        Column(Modifier.padding(paddingValues)) {
            Text("Passwords")
        }
    }
}