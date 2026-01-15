package com.vayunmathur.crypto.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.isFinished
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.crypto.PortfolioViewModel
import com.vayunmathur.crypto.api.PendingOrder
import com.vayunmathur.crypto.token.TokenInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.pow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderDialog(
    viewModel: PortfolioViewModel,
    getOrder: suspend (Double) -> PendingOrder?,
    inputToken: TokenInfo,
    outputToken: TokenInfo,
    inputTheOutput: Boolean,
    title: @Composable () -> Unit,
    buttonContent: @Composable (Double) -> Unit,
    onDismiss: () -> Unit
) {
    var amount by remember { mutableStateOf("5") }
    val progress = remember { Animatable(0f) }
    var order: PendingOrder? by remember { mutableStateOf(null) }
    val inputAmount by remember { derivedStateOf { order?.let { it.inAmount.toDouble() / 10.0.pow(inputToken.decimals) } } }
    val outputAmount by remember { derivedStateOf { order?.let { it.outAmount.toDouble() / 10.0.pow(outputToken.decimals) } } }

    val coroutineScope = rememberCoroutineScope()
    var job: Job? = remember { null }

    LaunchedEffect(amount) {
        val amount = amount.toDoubleOrNull() ?: return@LaunchedEffect
        job?.cancel()
        job = coroutineScope.launch {
            while (isActive) {
                do {
                    order = getOrder(amount)
                    delay(500)
                } while(order == null)
                progress.snapTo(1f)
                val result = progress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 15000, easing = LinearEasing)
                )
                while (isActive && !result.endState.isFinished) {
                    delay(500)
                }
            }
        }
    }

    ModalBottomSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            title()
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Amount to spend") },
                prefix = { Text(if(inputTheOutput) outputToken.symbol else inputToken.symbol) }
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(
                    { progress.value },
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(16.dp))
                order?.let {
                    if(inputTheOutput) Text("$outputAmount ${outputToken.symbol} = $inputAmount ${inputToken.symbol}")
                    else Text("$inputAmount ${inputToken.symbol} = $outputAmount ${outputToken.symbol}")
                }
            }
            Spacer(Modifier.height(24.dp))
            Button({
                viewModel.placeOrder(order!!)
                onDismiss()
            }, Modifier.fillMaxWidth(), enabled = order != null) {
                buttonContent((if(inputTheOutput) inputAmount else outputAmount) ?: 0.0)
            }
        }
    }
}