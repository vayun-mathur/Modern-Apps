package com.vayunmathur.web.platform.shields

import android.content.Context
import com.vayunmathur.web.domain.EffectiveShields
import com.vayunmathur.web.domain.ShieldsSettings

/**
 * Whether to farble, resolved for every site up front.
 *
 * The document-start script runs before anything can tell it what page it is on, so the
 * decision has to travel with it: [enabled]/[aggressive] are the global defaults and
 * [overrides] carries the handful of hosts the user changed.
 */
data class FarblingConfig(
    val enabled: Boolean,
    val aggressive: Boolean,
    val overrides: Map<String, Pair<Boolean?, Boolean?>>,
) {
    fun toJson(): String = buildString {
        append("{\"enabled\":").append(enabled)
        append(",\"aggressive\":").append(aggressive)
        append(",\"overrides\":{")
        overrides.entries.forEachIndexed { index, (host, flags) ->
            if (index > 0) append(',')
            append(ShieldsInjection.jsString(host)).append(":{")
            append("\"enabled\":").append(flags.first?.toString() ?: "null")
            append(",\"aggressive\":").append(flags.second?.toString() ?: "null")
            append('}')
        }
        append("}}")
    }

    /** Shared constants and helpers for FarblingConfig. */
    companion object {
        /** Builds the config from the global defaults plus every per-site record. */
        fun of(global: ShieldsSettings, sites: Map<String, ShieldsSettings>): FarblingConfig {
            val effective = EffectiveShields.resolve(global)
            return FarblingConfig(
                enabled = effective.fingerprintProtection,
                aggressive = effective.aggressiveFingerprinting,
                overrides = sites.mapValues { (_, site) ->
                    val resolved = EffectiveShields.resolve(global, site)
                    resolved.fingerprintProtection to resolved.aggressiveFingerprinting
                },
            )
        }
    }
}

/**
 * Builds the JavaScript that shields inject into every page.
 *
 * Two things have to happen before the page's own scripts run: fingerprint farbling
 * (useless afterwards, since a script could have already read the true values) and the
 * cosmetic stylesheet (otherwise ad slots flash before being hidden). Both are packed
 * into a single document-start script.
 */
internal object ShieldsInjection {

    /** Name of the object [ShieldsWebViewClient] exposes for the second cosmetic pass. */
    const val COSMETIC_CHANNEL = "shieldsCosmetic"

    private const val FARBLE_ASSET = "shields/farble.js"

    @Volatile
    private var farbleSource: String? = null

    private fun farble(context: Context): String =
        farbleSource ?: synchronized(this) {
            farbleSource ?: context.applicationContext.assets
                .open(FARBLE_ASSET)
                .use { it.readBytes().decodeToString() }
                .also { farbleSource = it }
        }

    /**
     * The fingerprinting payload, registered once per WebView at creation.
     *
     * This has to be a document-start script and it has to be registered *before* the
     * navigation: `addDocumentStartJavaScript` only affects documents that start loading
     * after the call, so registering it from `onPageStarted` would leave the very first
     * page unprotected. That rules out baking the current URL into it, so the script
     * decides per origin at runtime from [config] and derives its own seed.
     */
    fun farbling(context: Context, config: FarblingConfig, sessionSalt: Int): String = buildString {
        append("(function(){")
        append("if(window.__shieldsFarbled)return;")
        append("var host=location.hostname||'';")
        append("var cfg=").append(config.toJson()).append(";")
        append("var o=cfg.overrides[host];")
        append("var on=(o&&typeof o.enabled==='boolean')?o.enabled:cfg.enabled;")
        append("if(!on)return;")
        append("window.__shieldsFarbled=true;")
        append("var agg=(o&&typeof o.aggressive==='boolean')?o.aggressive:cfg.aggressive;")
        // Seed = session salt mixed with the hostname, so it is stable for this site in
        // this session and unrelated to any other site or run.
        append("var h=").append(sessionSalt).append("|0;")
        append("for(var i=0;i<host.length;i++){h=(h*31+host.charCodeAt(i))|0;}")
        append("window.__shieldsSeed=h>>>0;window.__shieldsAggressive=agg;")
        append("try{").append(farble(context)).append("}catch(e){}")
        append("})();")
    }

    /**
     * The per-page cosmetic payload. Unlike farbling this depends on the URL, so it is
     * applied once the navigation is known; arriving a few milliseconds late only means a
     * hidden element may flash, never that protection is lost.
     */
    fun cosmetic(resources: CosmeticResources?): String {
        if (resources == null) return ""
        return buildString {
            append("(function(){if(window.__shieldsCosmetic)return;window.__shieldsCosmetic=true;")
            if (resources.hide.isNotEmpty()) {
                append("try{").append(hideCss(resources.hide)).append("}catch(e){}")
            }
            if (resources.script.isNotEmpty()) {
                append("try{").append(resources.script).append("}catch(e){}")
            }
            if (!resources.genericHide) {
                append("try{").append(observer(resources.exceptions)).append("}catch(e){}")
            }
            append("})();")
        }
    }

    /**
     * Injects `selectors { display: none !important }` as a stylesheet.
     *
     * Split into chunks because a single rule with tens of thousands of selectors is slow
     * to match and some engines cap selector-list length.
     */
    fun hideCss(selectors: List<String>): String {
        if (selectors.isEmpty()) return ""
        val rules = selectors.chunked(SELECTORS_PER_RULE).joinToString("\n") {
            it.joinToString(",") + "{display:none !important;}"
        }
        return buildString {
            append("(function(){var s=document.getElementById('__shields_css__');")
            append("if(!s){s=document.createElement('style');s.id='__shields_css__';")
            append("(document.head||document.documentElement).appendChild(s);}")
            append("s.appendChild(document.createTextNode(").append(jsString(rules)).append("));})();")
        }
    }

    private const val SELECTORS_PER_RULE = 128

    /**
     * Brave's second cosmetic pass: generic hide rules are far too numerous to inject
     * wholesale, so the page reports the classes and ids it actually uses and the engine
     * replies with only the rules that could match.
     */
    private fun observer(exceptions: List<String>): String = buildString {
        append("(function(){var ch=window.").append(COSMETIC_CHANNEL).append(";if(!ch)return;")
        append("var seenC={},seenI={},pending=false;")
        append("var exc=").append(jsonArray(exceptions)).append(";")
        append("function collect(root){")
        append("if(!root||root.nodeType!==1)return;")
        append("var all=root.querySelectorAll?root.querySelectorAll('[class],[id]'):[];")
        append("var nodes=[root];for(var i=0;i<all.length;i++)nodes.push(all[i]);")
        append("for(var n=0;n<nodes.length;n++){var el=nodes[n];")
        append("if(el.id&&!seenI[el.id])seenI[el.id]=1;")
        append("var cl=el.classList;if(cl)for(var c=0;c<cl.length;c++){if(!seenC[cl[c]])seenC[cl[c]]=1;}}")
        append("schedule();}")
        append("function schedule(){if(pending)return;pending=true;")
        // Batch a frame's worth of mutations into one JNI round trip.
        append("(window.requestIdleCallback||window.setTimeout)(function(){pending=false;")
        append("var c=Object.keys(seenC),i=Object.keys(seenI);seenC={};seenI={};")
        append("if(c.length||i.length)ch.postMessage(JSON.stringify({classes:c,ids:i,exceptions:exc}));")
        append("},1);}")
        append("ch.onmessage=function(e){try{var sel=JSON.parse(e.data);")
        append("if(sel.length)applyHide(sel);}catch(err){}};")
        append("function applyHide(sel){var s=document.getElementById('__shields_css2__');")
        append("if(!s){s=document.createElement('style');s.id='__shields_css2__';")
        append("(document.head||document.documentElement).appendChild(s);}")
        append("s.appendChild(document.createTextNode(sel.join(',')+'{display:none !important;}'));}")
        append("collect(document.documentElement);")
        append("new MutationObserver(function(muts){for(var m=0;m<muts.length;m++){")
        append("var added=muts[m].addedNodes;for(var a=0;a<added.length;a++)collect(added[a]);")
        append("if(muts[m].type==='attributes')collect(muts[m].target);}})")
        append(".observe(document.documentElement,{childList:true,subtree:true,")
        append("attributes:true,attributeFilter:['class','id']});})();")
    }

    private fun jsonArray(values: List<String>): String =
        values.joinToString(",", "[", "]") { jsString(it) }

    /** JS string literal. `<` is escaped so the payload survives being parsed inside a page. */
    internal fun jsString(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                '<' -> append("\\u003c")
                else -> append(c)
            }
        }
        append('"')
    }
}
