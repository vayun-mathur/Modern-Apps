package com.vayunmathur.share.platform

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.vayunmathur.share.R
import com.vayunmathur.share.ui.SharePopupActivity

/**
 * The app's single Direct Share shortcut.
 *
 * Unlike a messenger there is no person or conversation to target: the nearby
 * device is only chosen after the share starts (discovery happens in the
 * popup), so one long-lived shortcut for the popup itself is the whole surface.
 * It carries [CATEGORY], which must match the `<share-target>` in
 * `res/xml/shortcuts.xml`; tapping it in the share sheet's direct-share row
 * launches [SharePopupActivity] with the original SEND intent, which
 * `ShareViewModel.handleShareIntent` already consumes.
 *
 * The nearby *receive* flow is deliberately not a target: it has no screen
 * (QS tile + notifications, see ShareSendPage) and no activity accepts a
 * share intent for it, so there is nothing a share-target could launch.
 */
object ShareShortcuts {
    /** Stable id of the one published shortcut. */
    const val SHORTCUT_ID = "nearby_transfer"

    /** Direct Share category; must match `res/xml/shortcuts.xml`. */
    const val CATEGORY = "com.vayunmathur.share.category.DIRECT_SHARE"

    /** Publish/refresh the shortcut. Idempotent; safe from any entry point. */
    fun publish(context: Context) {
        val intent = Intent(context, SharePopupActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("*/*")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
            .setShortLabel(context.getString(R.string.share_direct_shortcut_label))
            .setLongLabel(context.getString(R.string.share_direct_shortcut_label))
            .setLongLived(true)
            .setCategories(setOf(CATEGORY))
            .setIntent(intent)
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
    }
}
