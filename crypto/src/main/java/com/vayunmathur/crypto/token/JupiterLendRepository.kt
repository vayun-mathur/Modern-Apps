package com.vayunmathur.crypto.token

import kotlinx.serialization.Serializable

object JupiterLendRepository : Repository<JupiterLendRepository.JLTokenData>(JLTokenData.serializer()) {
    @Serializable
    data class JLTokenData(val apy: Double, val underlyingToken: TokenInfo)

    override val sharedPreferencesName = "jupiter_lend_repo"

    override suspend fun getData(): Map<String, JLTokenData> {
        return mapOf(
            // usdc
            "9BEcn9aPEmhSPbPQeFGjidRiEKki46fVQDyPpSQXPA2D" to JLTokenData(0.0529, TokenInfo.USDC),
            // usdt
            "Cmn4v2wipYV41dkakDvCgFJpxhtaaKt11NyWV8pjSE8A" to JLTokenData(0.0507, TokenInfo.TOKEN_LIST.find { it.symbol == "USDT" }!!),
            // wsol
            "2uQsyo1fXXQkDtcpXnLofWy88PxcvnfH2L8FPSE62FVU" to JLTokenData(0.034, TokenInfo.TOKEN_LIST.find { it.symbol == "wSOL" }!!),
            // eurc
            "GcV9tEj62VncGithz4o4N9x6HWXARxuRgEAYk9zahNA8" to JLTokenData(0.0492, TokenInfo.TOKEN_LIST.find { it.symbol == "EURC" }!!),
            // usds
            "j14XLJZSVMcUYpAfajdZRpnfHUpJieZHS4aPektLWvh" to JLTokenData(0.0687, TokenInfo.TOKEN_LIST.find { it.symbol == "USDS" }!!),
            // usdg
            "9fvHrYNw1A8Evpcj7X2yy4k4fT7nNHcA9L6UsamNHAif" to JLTokenData(0.0571, TokenInfo.TOKEN_LIST.find { it.symbol == "USDG" }!!),
        )
    }
}
