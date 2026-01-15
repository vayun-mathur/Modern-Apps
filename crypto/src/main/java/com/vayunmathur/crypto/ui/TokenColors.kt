package com.vayunmathur.crypto.ui

import androidx.compose.ui.graphics.Color
import com.vayunmathur.crypto.token.TokenInfo

fun getTokenColor(tokenInfo: TokenInfo): Color {
    return when (tokenInfo.symbol) {
        "SOL" -> Color.Magenta
        "USDC" -> Color.Blue
        "EURC" -> Color.Cyan
        "USDT" -> Color.Green
        "USDS" -> Color.Yellow
        "USDG" -> Color.Green
        "wSOL" -> Color.Magenta
        "wETH" -> Color.Gray
        "wBTC" -> Color.Yellow
        "SPYx" -> Color.Red
        "QQQx" -> Color.Magenta
        "GOOGLx" -> Color.LightGray
        "AAPLx" -> Color.DarkGray
        "AMZNx" -> Color.Yellow
        "METAx" -> Color.Blue
        "NFLXx" -> Color.Red
        "TSLAx" -> Color.Red
        "NVDAx" -> Color.Green
        "MSFTx" -> Color.Blue
        "ORCLx" -> Color.Red
        "PLTRx" -> Color.Gray
        "GLDx" -> Color.Yellow
        "jlUSDC" -> Color.Blue
        "jlUSDT" -> Color.Green
        "jlWSOL" -> Color.Magenta
        "jlEURC" -> Color.Blue
        "jlUSDS" -> Color.Blue
        "jlUSDG" -> Color.Blue
        else -> Color.Black
    }
}
