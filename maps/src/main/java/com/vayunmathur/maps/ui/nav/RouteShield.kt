package com.vayunmathur.maps.ui.nav
import com.vayunmathur.maps.data.string

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.Text

/** A parsed road shield reference and its rendering style. */
data class RoadShield(val ref: String, val kind: ShieldKind)

/** Kinds of shield kind. */
enum class ShieldKind { INTERSTATE, US_HIGHWAY, STATE, GENERIC }

/**
 * Derive a US/European route shield from a road name or instruction string
 * (Decision D10 — no dedicated ref field in the graph, so parse it). Matches
 * the common ref forms embedded in the templated instruction text, e.g.
 * "Continue on I-280", "Turn right onto US 101", "Merge onto CA-85", "A4".
 * Returns `null` when no ref token is present (a named local street).
 */
fun roadShieldFrom(text: String?): RoadShield? {
    if (text.isNullOrBlank()) return null

    Regex("""\bI[- ]?(\d{1,3})\b""").find(text)?.let {
        return RoadShield("I-${it.groupValues[1]}", ShieldKind.INTERSTATE)
    }
    Regex("""\bUS[- ]?(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(text)?.let {
        return RoadShield("US ${it.groupValues[1]}", ShieldKind.US_HIGHWAY)
    }
    // State routes like "CA-85", "NY 17", "SR-237".
    Regex("""\b([A-Z]{2})[- ]?(\d{1,3})\b""").find(text)?.let {
        val prefix = it.groupValues[1]
        if (prefix != "US") return RoadShield("$prefix-${it.groupValues[2]}", ShieldKind.STATE)
    }
    // European style motorway/A-road refs: "A4", "M1", "B500".
    Regex("""\b([AMB])(\d{1,4})\b""").find(text)?.let {
        return RoadShield("${it.groupValues[1]}${it.groupValues[2]}", ShieldKind.GENERIC)
    }
    return null
}

/**
 * Render a route shield badge. Colors follow the familiar US conventions
 * (Interstate blue, US-route white, state green) with a neutral fallback.
 * Brand-style colors are intentionally fixed (non-translatable UI chrome).
 */
@Composable
fun RouteShield(shield: RoadShield, modifier: Modifier = Modifier) {
    val bg: Color
    val fg: Color
    val borderColor: Color
    when (shield.kind) {
        ShieldKind.INTERSTATE -> {
            bg = Color(0xFF003F87); fg = Color.White; borderColor = Color.White
        }
        ShieldKind.US_HIGHWAY -> {
            bg = Color.White; fg = Color(0xFF111111); borderColor = Color(0xFF111111)
        }
        ShieldKind.STATE -> {
            bg = Color(0xFF1B5E20); fg = Color.White; borderColor = Color.White
        }
        ShieldKind.GENERIC -> {
            bg = Color(0xFF37474F); fg = Color.White; borderColor = Color(0xFFB0BEC5)
        }
    }
    Box(
        modifier
            .background(bg, RoundedCornerShape(6.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            shield.ref,
            color = fg,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}
