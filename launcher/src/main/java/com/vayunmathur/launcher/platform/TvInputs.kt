package com.vayunmathur.launcher.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager

/** One HDMI input tile on the bottom row. */
data class TvHdmiInput(
    val id: String,
    val label: String,
)

/**
 * The TV's own HDMI inputs (not devices behind them), in order, skipping hidden ones
 * (the tv-detox `hdmiInputs`/`inputLabel` logic).
 */
object TvInputs {
    fun list(context: Context, max: Int): List<TvHdmiInput> {
        val manager = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
            ?: return emptyList()
        return runCatching { manager.tvInputList }.getOrDefault(emptyList())
            .filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId == null && !it.isHidden(context) }
            .sortedBy { it.id }
            .take(max)
            .map { TvHdmiInput(it.id, labelOf(context, it)) }
    }

    private fun labelOf(context: Context, input: TvInputInfo): String {
        val custom = runCatching { input.loadCustomLabel(context) }.getOrNull()
        if (!custom.isNullOrEmpty()) return custom.toString()
        val label = runCatching { input.loadLabel(context) }.getOrNull()
        return if (!label.isNullOrEmpty()) label.toString() else "HDMI"
    }

    /**
     * Opens an HDMI input: Google TV's input player first when the TV itself routes HDMI
     * through it (some Google TVs ship the stock Live TV app as a stub that starts fine but
     * only says hello), then the stock and Droidlogic Live TV apps, then whatever the TV picks.
     */
    fun open(context: Context, inputId: String) {
        val pm = context.packageManager
        val view = Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId))
        val tries = mutableListOf<Intent>()
        val def = runCatching { pm.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY) }.getOrNull()
        if (def?.activityInfo != null && def.activityInfo.packageName == INPUT_PLAYER) {
            tries.add(Intent(view).setClassName(INPUT_PLAYER, def.activityInfo.name))
        }
        for ((pkg, cls) in LIVE_TV) {
            val t = Intent(view).setClassName(pkg, cls)
            if (t.resolveActivity(pm) != null) tries.add(t)
        }
        tries.add(view)
        for (t in tries) {
            t.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(t) }.isSuccess) return
        }
    }

    private const val INPUT_PLAYER = "com.google.android.tv.inputplayer"

    /** Live TV apps an HDMI tile can open, in order (the tv-detox `LIVE_TV` table). */
    private val LIVE_TV = listOf(
        "com.android.tv" to "com.android.tv.MainActivity",
        "com.droidlogic.android.tv" to "com.android.tv.TvActivity",
    )
}

