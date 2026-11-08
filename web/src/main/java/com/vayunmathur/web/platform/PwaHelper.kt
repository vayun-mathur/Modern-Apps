package com.vayunmathur.web.platform

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import com.vayunmathur.library.log.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.vayunmathur.library.image.ImageLoader
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.ImageResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PwaInfo(
    val name: String,
    val shortName: String = "",
    val iconUrl: String? = null,
    val faviconUrl: String? = null,
    val themeColor: String? = null,
    val backgroundColor: String? = null,
    val displayMode: String = "standalone",
    val startUrl: String? = null,
    val origin: String,
    val manifestUrl: String? = null,
    val hasManifest: Boolean = false,
)

object PwaHelper {
    private const val TAG = "PwaHelper"

    /** Display titles are truncated so shortcut labels and dialog rows stay on one line. */
    private const val MAX_DISPLAY_TITLE_LENGTH = 48

    /** Icon bitmap edge length requested from the image loader. */
    private const val ICON_BITMAP_SIZE = 192

    /** Shortcut short labels are truncated so launchers do not ellipsize them. */
    private const val SHORT_LABEL_LENGTH = 10

    // JS probe - intentionally avoids Kotlin ${} templates by using string concatenation only.
    const val MANIFEST_PROBE_JS = "(function(){" +
            "try{" +
            "var origin=location.origin;" +
            "var title='';" +
            "try{var og=document.querySelector('meta[property=\"og:title\"]');" +
            "title=(og&&og.content)?og.content.trim():(document.title||'').trim();}" +
            "catch(e){title=document.title||'';}" +
            "var iconUrl=null,faviconUrl=null,themeColor=null,bgColor=null," +
            "displayMode='browser',startUrl=location.href,manifestUrl=null,hasManifest=false;" +
            "try{var tm=document.querySelector('meta[name=\"theme-color\"]');" +
            "if(tm)themeColor=tm.content||null;}catch(e){}" +
            "try{var ms=document.querySelector('meta[name=\"msapplication-TileColor\"]');" +
            "if(ms)bgColor=ms.content||null;}catch(e){}" +
            "try{var ml=document.querySelector('link[rel=\"manifest\"]');" +
            "if(ml){manifestUrl=ml.href;hasManifest=!!manifestUrl;}}catch(e){}" +
            "function bestIcon(){" +
            "try{" +
            "var cands=[];var ls=document.querySelectorAll('link[rel*=\"icon\"]');" +
            "for(var i=0;i<ls.length;i++){var l=ls[i];var href=l.href;if(!href)continue;" +
            "var rel=(l.rel||'').toLowerCase();" +
            "var sizes=(l.getAttribute('sizes')||'').toLowerCase();var score=0;" +
            "if(rel.indexOf('apple-touch-icon')>=0)score=90;" +
            "if(rel==='icon'||rel.indexOf('icon')>=0)score=Math.max(score,70);" +
            "if(sizes.indexOf('192')>=0||sizes.indexOf('512')>=0)score+=40;" +
            "if(sizes.indexOf('180')>=0)score+=20;" +
            "if(href.indexOf('192')>=0||href.indexOf('512')>=0)score+=25;" +
            "if(href.endsWith('.png'))score+=10;" +
            "cands.push({href:href,score:score});}" +
            "if(cands.length===0)return null;" +
            "cands.sort(function(a,b){return b.score-a.score;});return cands[0].href;" +
            "}catch(e){return null;}}" +
            "try{var il=document.querySelector('link[rel=\"icon\"], link[rel=\"shortcut icon\"]');" +
            "if(il)faviconUrl=il.href||null;}catch(e){}" +
            "var best=bestIcon();if(best)iconUrl=best;if(!iconUrl&&faviconUrl)iconUrl=faviconUrl;" +
            "return JSON.stringify({title:title,name:'',shortName:'',iconUrl:iconUrl," +
            "faviconUrl:faviconUrl,themeColor:themeColor,bgColor:bgColor," +
            "displayMode:displayMode,startUrl:startUrl,manifestUrl:manifestUrl," +
            "hasManifest:hasManifest,origin:origin});" +
            "}catch(e){try{return JSON.stringify({title:document.title||''," +
            "origin:location.origin||''});}catch(e2){return null;}}})();"

    fun parseProbeJson(escapedJson: String?): PwaInfo? {
        val s = unescapeProbeJson(escapedJson) ?: return null
        val fields = ProbeFields(
            title = s.strField("title") ?: "",
            name = s.strField("name"),
            shortName = s.strField("shortName") ?: "",
            iconUrl = s.strField("iconUrl"),
            faviconUrl = s.strField("faviconUrl"),
            themeColor = s.strField("themeColor"),
            backgroundColor = s.strField("bgColor"),
            displayMode = s.strField("displayMode") ?: "browser",
            startUrl = s.strField("startUrl"),
            manifestUrl = s.strField("manifestUrl"),
            origin = s.strField("origin") ?: "",
            hasManifest = s.hasTrueFlag("hasManifest"),
        )
        if (!fields.hasIdentity) return null
        return fields.toPwaInfo()
    }

    /** Raw probe payload with WebView's JS-string escaping removed, or null when empty. */
    private fun unescapeProbeJson(escapedJson: String?): String? {
        if (escapedJson.isNullOrBlank()) return null
        var s = escapedJson.trim()
        if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
            s = s.substring(1, s.length - 1).unescapeJson()
        }
        return s.takeIf { it.isNotBlank() && it != "null" }
    }

    /**
     * Single-pass JSON/JS-string unescaper. The old chained
     * `replace("\\\"", ...).replace("\\\\", ...).replace("\\/", ...)` re-scanned
     * already-produced output: after step 2 turned `\\/` into `\/`, step 3 ate
     * the remaining backslash, so a title like `p\/q` decoded to `p/q`.
     * Walking once keeps each escape pair intact (`\\/` → `\` + `/`).
     */
    private fun String.unescapeJson(): String {
        val out = StringBuilder(length)
        var i = 0
        while (i < length) {
            val c = this[i]
            if (c != '\\' || i + 1 >= length) {
                out.append(c)
                i++
                continue
            }
            when (val e = this[i + 1]) {
                '"', '\\', '/' -> {
                    out.append(e)
                    i += 2
                }
                'n' -> {
                    out.append('\n')
                    i += 2
                }
                'r' -> {
                    out.append('\r')
                    i += 2
                }
                't' -> {
                    out.append('\t')
                    i += 2
                }
                'b' -> {
                    out.append('\b')
                    i += 2
                }
                'f' -> {
                    out.append('\u000C')
                    i += 2
                }
                'u' -> {
                    val hex = if (i + 5 < length) substring(i + 2, i + 6) else null
                    val code = hex?.toIntOrNull(16)
                    if (code != null) {
                        out.append(code.toChar())
                        i += 6
                    } else {
                        out.append('\\')
                        i++
                    }
                }
                else -> {
                    out.append(e)
                    i += 2
                }
            }
        }
        return out.toString()
    }

    private fun String.strField(key: String): String? {
        val pattern = "\"" + key + "\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\""
        val match = Regex(pattern).find(this) ?: return null
        return match.groupValues[1].unescapeJson()
    }

    private fun String.hasTrueFlag(key: String): Boolean =
        Regex("\"" + key + "\"\\s*:\\s*(true|false)").find(this)?.groupValues?.get(1) == "true"

    /**
     * The probe fields that identify a page as installable. Kept together so the
     * emptiness check stays in one place.
     */
    private class ProbeFields(
        val title: String,
        name: String?,
        val shortName: String,
        val iconUrl: String?,
        val faviconUrl: String?,
        val themeColor: String?,
        val backgroundColor: String?,
        val displayMode: String,
        val startUrl: String?,
        val manifestUrl: String?,
        val origin: String,
        val hasManifest: Boolean,
    ) {
        val name: String = name?.ifBlank { null } ?: title

        /** False when the probe carried nothing usable — title, name, origin and icon all missing. */
        val hasIdentity: Boolean
            get() = origin.isNotBlank() || name.isNotBlank() || title.isNotBlank() || iconUrl != null

        fun toPwaInfo(): PwaInfo = PwaInfo(
            name = name.ifBlank { title },
            shortName = shortName,
            iconUrl = iconUrl,
            faviconUrl = faviconUrl,
            themeColor = themeColor,
            backgroundColor = backgroundColor,
            displayMode = displayMode,
            startUrl = startUrl,
            origin = origin.ifBlank { startUrl ?: "" },
            manifestUrl = manifestUrl,
            hasManifest = hasManifest,
        )
    }

    fun displayTitle(info: PwaInfo?, fallbackTitle: String, fallbackUrl: String): String {
        val n = info?.name?.ifBlank { null } ?: info?.shortName?.ifBlank { null }
        val t = fallbackTitle.ifBlank { null }
        val host = runCatching { BrowserUtils.hostFromUrl(fallbackUrl) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        return (n ?: t ?: host ?: "Site").take(MAX_DISPLAY_TITLE_LENGTH)
    }

    fun isPinSupported(context: Context): Boolean =
        ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    fun shortcutId(url: String): String {
        val origin = BrowserUtils.originFromUrl(url)
        return "pwa_" + origin.hashCode() + "_" + url.hashCode()
    }

    fun createPwaIntent(context: Context, url: String, title: String?): Intent {
        return Intent(context, com.vayunmathur.web.platform.PwaActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(url)
            putExtra(com.vayunmathur.web.platform.PwaActivity.EXTRA_URL, url)
            putExtra(com.vayunmathur.web.platform.PwaActivity.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }
    }

    suspend fun loadIconBitmap(context: Context, iconUrl: String?): Bitmap? {
        if (iconUrl.isNullOrBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val loader = ImageLoader.get(context)
                val req = ImageRequest.Builder(context)
                    .data(iconUrl)
                    .allowHardware(false)
                    .size(ICON_BITMAP_SIZE)
                    .build()
                val result = loader.execute(req)
                val bitmap = (result as? ImageResult.Success)?.bitmap ?: return@runCatching null
                bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
            }.onFailure { e ->
                Log.status(TAG, "loadIconBitmap failed $iconUrl", e)
            }.getOrNull()
        }
    }

    fun textIconBitmap(title: String, sizePx: Int = ICON_BITMAP_SIZE): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val hash = title.hashCode()
        val rr = 80 + (hash shr 16 and 0x7F)
        val gg = 80 + (hash shr 8 and 0x7F)
        val bb = 80 + (hash and 0x7F)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(rr, gg, bb)
        }
        val radius = sizePx * 0.28f
        canvas.drawRoundRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), radius, radius, bg)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = sizePx * 0.52f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val letter = title.trim().firstOrNull()?.uppercase() ?: "W"
        val x = sizePx / 2f
        val y = sizePx / 2f - (tp.descent() + tp.ascent()) / 2f
        canvas.drawText(letter, x, y, tp)
        return bmp
    }

    suspend fun requestPinShortcut(
        context: Context,
        url: String,
        title: String,
        iconUrl: String?,
        faviconUrl: String? = null,
    ): Boolean {
        if (!isPinSupported(context)) return false
        return withContext(Dispatchers.Main) {
            runCatching {
                val id = shortcutId(url)
                val intent = createPwaIntent(context, url, title)
                val shortLabel = title.takeIf { it.isNotBlank() }?.take(SHORT_LABEL_LENGTH)
                    ?: BrowserUtils.hostFromUrl(url).take(SHORT_LABEL_LENGTH)
                val longLabel = title.ifBlank { url }.take(MAX_DISPLAY_TITLE_LENGTH)

                var bmp = loadIconBitmap(context, iconUrl)
                if (bmp == null && !faviconUrl.isNullOrBlank()) {
                    bmp = loadIconBitmap(context, faviconUrl)
                }
                if (bmp == null) {
                    val origin = BrowserUtils.originFromUrl(url)
                    bmp = loadIconBitmap(context, origin + "/favicon.ico")
                }
                val finalBmp = bmp ?: textIconBitmap(title)
                val icon = IconCompat.createWithAdaptiveBitmap(finalBmp)

                val info = ShortcutInfoCompat.Builder(context, id)
                    .setShortLabel(shortLabel)
                    .setLongLabel(longLabel)
                    .setIntent(intent)
                    .setIcon(icon)
                    .build()

                ShortcutManagerCompat.requestPinShortcut(context, info, null)
            }.onFailure { e ->
                Log.error(TAG, "requestPinShortcut failed", e)
            }.getOrDefault(false)
        }
    }
}
