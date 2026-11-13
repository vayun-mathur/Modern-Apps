package com.vayunmathur.games.voxels.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.voxels.R
import com.vayunmathur.games.voxels.platform.SoundFx
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.voxels.util.VoxelsNative
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text

private data class Trade(
    val cost: Int, val costN: Int,
    val cost2: Int, val cost2N: Int,
    val give: Int, val giveN: Int,
    val level: Int,
)

private data class Stall(
    val profession: String,
    val level: Int,
    val maxLevel: Int,
    val done: Int,
    val nextAt: Int?,
    val trades: List<Trade>,
)

private fun parseStall(json: String): Stall {
    return try {
        val o = org.json.JSONObject(json)
        val arr = o.optJSONArray("trades")
        val trades = (0 until (arr?.length() ?: 0)).map { i ->
            val t = arr!!.getJSONObject(i)
            Trade(
                t.getInt("cost"), t.getInt("costN"),
                t.optInt("cost2"), t.optInt("cost2N"),
                t.getInt("give"), t.getInt("giveN"),
                t.optInt("level", 1),
            )
        }
        Stall(
            o.optString("prof", ""), o.optInt("level", 1), o.optInt("maxLevel", 1),
            o.optInt("done"), if (o.isNull("nextAt")) null else o.optInt("nextAt"), trades,
        )
    } catch (_: Exception) {
        Stall("", 1, 1, 0, null, emptyList())
    }
}

@Composable
fun TradeOverlay(tradesJson: String, onClose: () -> Unit) {
    // Trading can level the villager up mid-session, which appends new offers, so the list is
    // re-read from the engine after every successful exchange rather than cached at open.
    var json by remember(tradesJson) { mutableStateOf(tradesJson) }
    val stall = remember(json) { parseStall(json) }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth(0.7f).fillMaxHeight(0.8f).clip(RoundedCornerShape(14.dp)).background(Color(0xF01A1E1A))
                .pointerInput(Unit) { detectTapGestures { } }.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (stall.profession.isEmpty()) stringResource(R.string.villager_trades) else stall.profession,
                color = Color.White, fontSize = 18.sp,
            )
            Text(
                stringResource(R.string.villager_level, stall.level, stall.maxLevel),
                color = Color(0xFF9CD08A), fontSize = 12.sp,
            )
            val nextAt = stall.nextAt
            Text(
                if (nextAt != null) stringResource(R.string.villager_next_tier, nextAt - stall.done)
                else stringResource(R.string.tap_a_trade_to_exchange_need_the_cost_it),
                color = Color.White.copy(0.6f), fontSize = 12.sp,
            )
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                stall.trades.forEachIndexed { i, t ->
                    if (i == 0 || stall.trades[i - 1].level != t.level) {
                        item(key = "tier${t.level}") {
                            Text(
                                stringResource(R.string.villager_tier, t.level),
                                color = Color.White.copy(0.4f), fontSize = 11.sp,
                            )
                        }
                    }
                    item(key = "trade$i") {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White.copy(0.07f))
                                .clickable {
                                    try {
                                        if (VoxelsNative.trade(i)) {
                                            SoundFx.playPlace()
                                            json = VoxelsNative.getTradesJson()
                                        }
                                    } catch (_: Exception) {}
                                }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ItemChip(t.cost, t.costN)
                            if (t.cost2 != 0) {
                                Text("+", color = Color.White.copy(0.8f), fontSize = 16.sp)
                                ItemChip(t.cost2, t.cost2N)
                            }
                            Text("→", color = Color.White.copy(0.8f), fontSize = 20.sp)
                            ItemChip(t.give, t.giveN)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF3A6B3A)).clickable { onClose() }.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(stringResource(UiR.string.close), color = Color.White)
            }
        }
    }
}

@Composable
private fun ItemChip(id: Int, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val icon = rememberBlockIcon(id)
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(0.4f)), contentAlignment = Alignment.Center) {
            if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(28.dp), filterQuality = FilterQuality.None)
        }
        Text("${count}× ${blockName(id) ?: id}", color = Color.White, fontSize = 12.sp)
    }
}
