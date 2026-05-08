package com.vayunmathur.dialer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialerScreen() {
    var selectedTab by remember { mutableStateOf(0) }
    var isDialpadOpen by remember { mutableStateOf(false) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0; isDialpadOpen = false },
                    icon = { Icon(Icons.Default.Favorite, contentDescription = "Favorites") },
                    label = { Text("Favorites") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1; isDialpadOpen = false },
                    icon = { Icon(Icons.Default.Schedule, contentDescription = "Recents") },
                    label = { Text("Recents") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2; isDialpadOpen = false },
                    icon = { Icon(Icons.Default.Contacts, contentDescription = "Contacts") },
                    label = { Text("Contacts") }
                )
            }
        },
        floatingActionButton = {
            if (!isDialpadOpen) {
                FloatingActionButton(
                    onClick = { isDialpadOpen = true },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    elevation = FloatingActionButtonDefaults.elevation(6.dp)
                ) {
                    Icon(Icons.Default.Dialpad, contentDescription = "Open dialpad")
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            // Main content depending on the tab
            if (!isDialpadOpen) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (selectedTab) {
                        0 -> Text("Your favorite contacts will appear here", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        1 -> Text("Your call history will appear here", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        2 -> Text("Your contacts will appear here", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                DialpadView(onClose = { isDialpadOpen = false })
            }
        }
    }
}

@Composable
fun DialpadView(onClose: () -> Unit) {
    var number by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        verticalArrangement = Arrangement.Bottom
    ) {
        // Number Input Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.BottomCenter
        ) {
            Text(
                text = number,
                fontSize = 44.sp, // Larger font
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 32.dp),
                maxLines = 1,
                softWrap = false
            )
        }

        // Dialpad Grid
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface) // Flat surface
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val rows = listOf(
                listOf("1" to "", "2" to "ABC", "3" to "DEF"),
                listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
                listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
                listOf("*" to "", "0" to "+", "#" to "")
            )

            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    row.forEach { (main, sub) ->
                        DialpadButton(main = main, sub = sub) {
                            number += main
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Call Button Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.size(64.dp))
                
                // Call Button (Green / Primary)
                FloatingActionButton(
                    onClick = { /* Handle call */ },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp) // Flat design
                ) {
                    Icon(
                        Icons.Default.Call,
                        contentDescription = "Call",
                        modifier = Modifier.size(32.dp)
                    )
                }
                
                // Backspace/Close
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .clickable { if (number.isNotEmpty()) number = number.dropLast(1) else onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (number.isNotEmpty()) "⌫" else "▼", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun DialpadButton(main: String, sub: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .background(Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(text = main, fontSize = 32.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Normal)
            if (sub.isNotEmpty()) {
                Text(text = sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
fun DialpadButton(main: String, sub: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = main, fontSize = 28.sp, color = MaterialTheme.colorScheme.onSurface)
            if (sub.isNotEmpty()) {
                Text(text = sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
