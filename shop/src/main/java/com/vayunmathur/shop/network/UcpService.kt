package com.vayunmathur.shop.network

internal data class UcpService(
    val version: String,
    val transport: String,
    val endpoint: String,
    val capabilities: Set<String>,
    val paymentHandlerIds: Set<String>,
)
