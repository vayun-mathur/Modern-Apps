package com.vayunmathur.dialer

import android.os.Bundle
import android.telecom.Call
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.DynamicTheme

class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DynamicTheme {
                ActiveCallScreen(
                    onEndCall = {
                        CallManager.rejectCall()
                        finish()
                    },
                    onAnswerCall = {
                        CallManager.acceptCall()
                    }
                )
            }
        }
    }
}

@Composable
fun ActiveCallScreen(onEndCall: () -> Unit, onAnswerCall: () -> Unit) {
    val call by CallManager.activeCall.collectAsState()
    
    val state = call?.state ?: Call.STATE_DISCONNECTED
    val isRinging = state == Call.STATE_RINGING
    val number = call?.details?.handle?.schemeSpecificPart ?: "Unknown Caller"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant) // Slightly darker surface for call screen
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 72.dp, bottom = 48.dp, start = 32.dp, end = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Caller Avatar
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = number,
                fontSize = 36.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isRinging) "Incoming call" else "00:00",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
            
            Spacer(modifier = Modifier.weight(1f))

            if (isRinging) {
                // Incoming Call Buttons
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FloatingActionButton(
                            onClick = onEndCall,
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(72.dp),
                            shape = CircleShape,
                            elevation = FloatingActionButtonDefaults.elevation(0.dp)
                        ) {
                            Icon(Icons.Default.CallEnd, contentDescription = "Decline", modifier = Modifier.size(32.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Decline", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FloatingActionButton(
                            onClick = onAnswerCall,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(72.dp),
                            shape = CircleShape,
                            elevation = FloatingActionButtonDefaults.elevation(0.dp)
                        ) {
                            Icon(Icons.Default.Phone, contentDescription = "Answer", modifier = Modifier.size(32.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Answer", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                // Active Call Controls inside a distinct sheet/card
                androidx.compose.material3.Surface(
                    shape = RoundedCornerShape(32.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 32.dp, horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            CallControlButton(Icons.Default.MicOff, "Mute")
                            CallControlButton(Icons.Default.Dialpad, "Keypad")
                            CallControlButton(Icons.Default.VolumeUp, "Speaker")
                        }
                        
                        Spacer(modifier = Modifier.height(48.dp))
                        
                        FloatingActionButton(
                            onClick = onEndCall,
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.size(72.dp),
                            shape = CircleShape,
                            elevation = FloatingActionButtonDefaults.elevation(0.dp)
                        ) {
                            Icon(
                                Icons.Default.CallEnd,
                                contentDescription = "End Call",
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CallControlButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .clickable { /* Toggle state */ }
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

