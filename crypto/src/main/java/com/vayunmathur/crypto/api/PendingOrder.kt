package com.vayunmathur.crypto.api

import kotlinx.serialization.Serializable

@Serializable
data class PendingOrder(val inAmount: Long, val outAmount: Long, val inputMint: String, val outputMint: String, val transaction: String)
