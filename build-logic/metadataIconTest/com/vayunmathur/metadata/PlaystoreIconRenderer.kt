package com.vayunmathur.metadata

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders the app's own launcher icon (the adaptive `ic_launcher.xml`) to a 512x512
 * Play Store PNG on-device. Adaptive icons reference vectors/colors that can't easily be
 * rasterized on the host, so we composite the background + foreground layers here where
 * the platform can inflate them.
 *
 * This source set is shared into every module's androidTest by the
 * `common-conventions-metadata` plugin, so it runs as part of every `:module:metadata`.
 * The task then pulls the PNG into `<module>/src/main/ic_launcher-playstore.png`.
 */
@RunWith(AndroidJUnit4::class)
class PlaystoreIconRenderer {

    @Test
    fun renderPlaystoreIcon() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val size = 512
        val icon = ctx.packageManager.getApplicationIcon(ctx.packageName)

        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (icon is AdaptiveIconDrawable) {
            // Background fills the whole square.
            icon.background?.apply { setBounds(0, 0, size, size); draw(canvas) }
            // Adaptive-icon foregrounds reserve an ~18dp safe-zone margin on each side
            // of the 108dp layer (real content lives in the center 72dp). Scale the
            // foreground up so that safe zone fills the frame — otherwise the glyph
            // renders small with lots of padding. 108/72 = 1.5x, centered.
            val inset = (size * 0.25f).toInt()
            icon.foreground?.apply {
                setBounds(-inset, -inset, size + inset, size + inset)
                draw(canvas)
            }
        } else {
            icon.setBounds(0, 0, size, size)
            icon.draw(canvas)
        }

        val dir = File(ctx.getExternalFilesDir(null), "metadata_icon").apply {
            deleteRecursively()
            mkdirs()
        }
        File(dir, "ic_launcher-playstore.png").outputStream().use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
