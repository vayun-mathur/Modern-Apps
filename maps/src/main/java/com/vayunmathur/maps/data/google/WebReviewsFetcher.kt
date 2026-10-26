package com.vayunmathur.maps.data.google

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fetches a place's reviews through a hidden [WebView] — the keyless reviews source after Google
 * **deleted** the old `listentitiesreviews` endpoint (now HTTP 404) and moved reviews behind a
 * `batchexecute` RPC (`rpcids=T4jwAf`) whose request proto resisted capture.
 *
 * Rather than replay that RPC, we let Google's
 * own JS render the reviews (loading the place's canonical `?cid=` page, anonymous/no-login,
 * desktop UA) and read them back out of the DOM: per review the star rating, author, relative date,
 * text **and the reviewer's uploaded photos**. The page builds a JSON array over a JS bridge;
 * [ReviewsWebParser] turns it into [GoogleReview]s.
 *
 * Strictly best-effort + lazy: any failure/timeout returns empty and the place sheet just shows no
 * reviews. Serialized by a [Mutex] since the single WebView navigates per place.
 *
 * Only ever constructed inside [com.vayunmathur.maps.service.ReviewsScraperService]'s `:reviews`
 * process: loading Chromium pins ~100 MB that `WebView.destroy()` never unmaps, so it must live in
 * a process that can exit.
 */
class WebReviewsFetcher(private val context: Context) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val progress = ConcurrentHashMap<String, (Int) -> Unit>()
    private val partial = ConcurrentHashMap<String, (String) -> Unit>()
    private val seq = AtomicInteger()
    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var webView: WebView? = null

    private inner class Bridge {
        @JavascriptInterface
        fun onResult(id: String, payload: String) {
            pending.remove(id)?.complete(payload)
        }

        // Live "N reviews found so far" ticks from the scraper (the scrape runs ~10-40 s on busy
        // pages). Arrives on the WebView's JavaBridge thread — the callback must be thread-safe.
        @JavascriptInterface
        fun onProgress(id: String, n: Int) {
            progress[id]?.invoke(n)
        }

        // The accumulated reviews SO FAR, sent whenever the count grows — the sheet streams them
        // into the list under the progress bar instead of making the user stare at a bar for 30 s.
        // Same JavaBridge thread; the raw JSON is parsed by the client (the final onResult is
        // authoritative).
        @JavascriptInterface
        fun onPartial(id: String, payload: String) {
            partial[id]?.invoke(payload)
        }
    }

    /** Raw reviews JSON (see [ReviewsWebParser]) for [featureId] (`0x..:0x..`) — newest/most-relevant
     *  first as Google renders them, each with its uploaded photos — or null on any failure.
     *  [onProgress] streams the running count while the scrape is in flight; [onPartial] streams the
     *  accumulated reviews JSON (a growing prefix of the final result). Both are called off the main
     *  thread. */
    suspend fun fetch(
        featureId: String,
        onProgress: (Int) -> Unit = {},
        onPartial: (String) -> Unit = {},
    ): String? {
        val cid = cidOf(featureId) ?: return null
        return mutex.withLock { fetchLocked(cid, onProgress, onPartial) }
    }

    private suspend fun fetchLocked(
        cid: String,
        onProgress: (Int) -> Unit,
        onPartial: (String) -> Unit,
    ): String? {
        return run {
            val id = BRIDGE_ID_PREFIX + seq.incrementAndGet()
            val deferred = CompletableDeferred<String>()
            pending[id] = deferred
            progress[id] = onProgress
            partial[id] = onPartial
            val raw = try {
                withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
                    withContext(Dispatchers.Main) {
                        val wv = ensureWebView()
                        val ready = CompletableDeferred<Unit>()
                        wv.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                // Only google.com pages — a stray click on an external link (e.g. the
                                // place's website/menu action) must not navigate the hidden WebView away.
                                val u = request?.url ?: return false
                                val scheme = u.scheme
                                if (scheme != "https" && scheme != "http") return true
                                val host = u.host.orEmpty()
                                return !(host == "google.com" || host.endsWith(".google.com"))
                            }
                            override fun onPageFinished(view: WebView?, url: String?) {
                                main.postDelayed({ if (!ready.isCompleted) ready.complete(Unit) }, SETTLE_MS)
                            }
                        }
                        // Blank the PREVIOUS place's DOM before navigating — a slow load could otherwise
                        // let the MAX_LOAD fallback inject the scraper into the old page and return the
                        // previous place's reviews for THIS featureId (empty > wrong).
                        wv.evaluateJavascript("try{document.documentElement.innerHTML=''}catch(e){}", null)
                        wv.loadUrl("https://www.google.com/maps?cid=$cid&hl=en&gl=us")
                        // Proceed even if the SPA's onPageFinished is slow.
                        main.postDelayed({ if (!ready.isCompleted) ready.complete(Unit) }, MAX_LOAD_MS)
                        ready.await()
                        wv.evaluateJavascript(
                            extractScript(id, SCRAPE_REVIEW_CAP),
                            null,
                        )
                    }
                    deferred.await()
                }
            } finally {
                pending.remove(id)
                progress.remove(id)
                partial.remove(id)
            }
            raw?.ifEmpty { null }
        }
    }

    /** The Google "cid" = the LOW half of the `0xHIGH:0xLOW` feature id as an unsigned decimal — the
     *  canonical `maps.google.com?cid=` deep-link to a place. */
    private fun cidOf(featureId: String): String? {
        val low = featureId.substringAfter(":", "").removePrefix("0x").ifBlank { return null }
        return runCatching { BigInteger(low, CID_RADIX).toString() }.getOrNull()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val wv = WebView(context)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        // Desktop UA so Google serves the desktop web Maps (a mobile UA deep-links to intent://).
        wv.settings.userAgentString = GooglePoiDataSource.USER_AGENT
        wv.addJavascriptInterface(Bridge(), "ReviewsBridge")
        // Give the hidden (never-attached) WebView a REAL offscreen viewport. Google's reviews list is
        // virtualized + lazy-loaded off the scroll viewport; a 0×0 headless WebView renders the chrome
        // (rating histogram, topic filters) but NEVER the review cards. A tall explicit layout makes the
        // scroll pane real so the list renders + pages.
        wv.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(WV_WIDTH, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(WV_HEIGHT, android.view.View.MeasureSpec.EXACTLY),
        )
        wv.layout(0, 0, WV_WIDTH, WV_HEIGHT)
        // A never-attached WebView reads as a BACKGROUND page to Chromium: JS timers throttle
        // toward 1 Hz and rAF-driven rendering slows, which is why the visible Google Maps
        // WebView pages reviews near-instantly while this scrape crawled. Explicitly resume
        // the view + its timers so the renderer runs at foreground cadence.
        wv.onResume()
        wv.resumeTimers()
        webView = wv
        return wv
    }

    /** Self-polling DOM scraper: open the full reviews list, then scroll it a window at a time,
     *  ACCUMULATING each review card into a keyed set (Google virtualizes the panel — it recycles
     *  DOM nodes as you scroll, so any single snapshot holds only ~10 cards; the union across scroll
     *  positions is the full list). Bridges the accumulated JSON array back once the list is exhausted
     *  or the cap is hit. */
    private fun extractScript(id: String, cap: Int): String {
        val idj = "\"" + id.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return listOf(
            scraperHeader(idj, cap),
            scraperCardHelpers,
            scraperExtract,
            scraperDomUtils,
            scraperOpenFull,
            scraperTick,
            scraperFooter,
        ).joinToString("\n")
    }

    private fun scraperHeader(idj: String, cap: Int): String = """
            (function(){
              var ID=$idj, tries=0, opened=false, acc={}, accN=0, lastN=0, noGrow=0, atBottom=0;
              var openedAt=-1, lastRep=-1, openedBy='', sawEntry=false, everCards=false, btnReclicks=0;
              var CAP=$cap;
    """.trimIndent()

    private val scraperCardHelpers = """
              function num(s){ var m=(s||'').match(/([0-9.]+)\s*star/i); return m?Math.round(parseFloat(m[1])):0; }
              function t1(c,sel){ var e=c.querySelector(sel); return e?(e.textContent||'').trim():''; }
    """.trimIndent()

    private val scraperExtract = """
              function extract(){
                // Review cards are `.jJc9Ad`, each with a unique `data-review-id` — far more robust than the
                // old "div with one star + text" heuristic, which also matched the place header ("4.6 stars
                // (57,969)") and affiliate ticket cards, and missed most real reviews.
                var revs=[].slice.call(document.querySelectorAll('.jJc9Ad'));
                return revs.map(function(c){
                  var idEl=c.querySelector('[data-review-id]'); var rid=idEl?(idEl.getAttribute('data-review-id')||''):'';
                  var star=c.querySelector('[role="img"][aria-label*="star" i], span[aria-label*=" star" i]');
                  // author: Google's review name class, else pull the NAME out of a button aria — the
                  // name is always right before "'s review" (after a "Share "/"Photo N on " prefix) or
                  // after "Photo of ". (Class names rotate; the aria phrasing is stable + semantic.)
                  var author=t1(c,'.d4r55')||t1(c,'.Vpc5Fe')||t1(c,'.TSUbDb');
                  if(!author){ var bs=[].slice.call(c.querySelectorAll('button[aria-label],a[aria-label]'));
                    var strip=/^(?:Share|Like|Response from|Photo of|Photo\s*\d*\s*on|\+?\s*\d*\s*(?:more\s*)?photos?\s*on|\d+\s*photos?\s*on)\s+/i;
                    for(var i=0;i<bs.length;i++){ var a=bs[i].getAttribute('aria-label')||'';
                      var m=a.match(/^(.+?)'s review\b/); var cand=m?m[1]:(a.match(/^Photo of (.+)${'$'}/)||[])[1];
                      if(cand){ var nm=cand.replace(strip,'').trim(); if(nm){ author=nm; break; } } } }
                  // review text: the wiI7pd body, else the longest leaf span that isn't chrome.
                  var text=t1(c,'.wiI7pd');
                  if(!text){ var best=0; [].slice.call(c.querySelectorAll('span')).forEach(function(s){ if(s.childElementCount===0){ var tt=(s.textContent||'').trim(); if(tt.length>best && tt.length>12 && !/^(see more|more|like|share|response from|local guide)/i.test(tt) && !/\bstar/i.test(tt)){ best=tt.length; text=tt; } } }); }
                  // relative date. `.rsqaWe` is the date element when present; else scan leaf spans.
                  // The old fallback grabbed the FIRST span merely CONTAINING "ago" (tt<22, /\bago\b/) —
                  // which also matches a short sentence in the review body ("been pthere ago"… any body
                  // span with "ago") and only knew English "ago"/bare-year. Anchor to the full relative-
                  // date shape ("10 months ago", "a year ago", "Edited 2 weeks ago") or a lone year,
                  // skip owner "Response" lines, and skip spans whose text is part of the review body —
                  // so we pick the real date, not a phrase out of the prose.
                  var date=t1(c,'.rsqaWe');
                  if(!date){ var body=(text||'').toLowerCase();
                    var reRel=/^(?:edited\s+)?(?:an?|\d+)\s+(?:second|minute|hour|day|week|month|year)s?\s+ago${'$'}/i;
                    [].slice.call(c.querySelectorAll('span')).forEach(function(s){ if(date||s.childElementCount>0) return;
                      var tt=(s.textContent||'').trim();
                      if(tt.length<28 && (reRel.test(tt)||/^20\d\d${'$'}/.test(tt)) && !/^response/i.test(tt) && body.indexOf(tt.toLowerCase())<0) date=tt; }); }
                  var avatar=''; var ai=c.querySelector('img'); if(ai && /googleusercontent/.test(ai.src||'')) avatar=ai.src;
                  var photos=[];
                  function addPhoto(u){ if(u && u!==avatar && /googleusercontent/.test(u) && !/\/a[\/-]|ACg8oc|ALV-/.test(u) && photos.indexOf(u)<0) photos.push(u); }
                  // Uploaded review photos are a <button>/<a> with a background-image on most layouts, but
                  // some cards expose them as plain <img> tiles — collect BOTH (avatar-filtered) so a card
                  // whose photos aren't button-backed still gets a tappable, author·date-captioned strip.
                  // (Was button-background-only, which silently dropped the whole strip on those cards.)
                  [].slice.call(c.querySelectorAll('button,a')).forEach(function(b){
                    var bg=''; try{ bg=getComputedStyle(b).backgroundImage||''; }catch(e){}
                    var mm=bg.match(/url\(["']?(https:\/\/[^"')]+googleusercontent[^"')]+)/);
                    if(mm) addPhoto(mm[1]);
                  });
                  [].slice.call(c.querySelectorAll('img')).forEach(function(im){ addPhoto(im.src||''); });
                  return { rid:rid, r:num(star&&star.getAttribute('aria-label')), a:author.slice(0,80), d:date, t:text, av:avatar, p:photos.slice(0,10) };
                  // Require an AUTHOR (rid stays the de-dup key): the parser drops author-less entries
                  // anyway, so letting them through only wastes CAP slots on cards we can't render.
                }).filter(function(x){ return x.a; });
              }
    """.trimIndent()

    private val scraperDomUtils = """
              // Expand truncated review bodies. The class hook (`.w8nwRe` is the card's More toggle)
              // works in EVERY UI language; the label regex stays as a fallback for older layouts
              // (it only knows English, which silently skipped expansion under any other hl).
              function expand(){
                [].slice.call(document.querySelectorAll('button.w8nwRe')).forEach(function(b){ try{ b.click(); }catch(e){} });
                [].slice.call(document.querySelectorAll('button')).forEach(function(b){ var l=((b.getAttribute('aria-label')||b.textContent)||'').trim(); if(/^(see more|more)${'$'}/i.test(l)){ try{ b.click(); }catch(e){} } });
              }
              // De-dupe across scroll windows by the review's stable id (falls back to author+date+text).
              function key(x){ return x.rid || ((x.a||'')+'|'+(x.d||'')+'|'+((x.t||'').slice(0,48))); }
              // The accumulated reviews so far, capped — used for both the partial streams and the
              // final result, so a partial is always a prefix-consistent snapshot of the final list.
              function snap(){ var o=[]; for(var k in acc) o.push(acc[k]); return o.slice(0,CAP); }
              // Scroll each tall left-column panel down by ~80% of a viewport (windows overlap so no
              // card is skipped past). Returns true if anything actually moved (false ⇒ at the bottom).
              function scrollStep(){
                var moved=false;
                try{ [].slice.call(document.querySelectorAll('div')).forEach(function(d){
                  if(d.scrollHeight>d.clientHeight+200 && d.clientHeight>250 && d.getBoundingClientRect().left<640){
                    var before=d.scrollTop; d.scrollTop=Math.min(d.scrollHeight, d.scrollTop+Math.round(d.clientHeight*0.8));
                    if(d.scrollTop>before+5) moved=true;
                  }
                }); }catch(e){}
                return moved;
              }
    """.trimIndent()

    private val scraperOpenFull = """
              // Open the FULL reviews list. The canonical entry is the "Reviews" role=tab; fall back to a
              // "More reviews" button for layouts that only expose that. On busy pages (food/retail) the
              // tab's list can take ~8 s to render after the click — the idle-bail is gated on `sawCards`
              // below so we never quit during that blank window (that was the "only 3 reviews" bug).
              function openFull(){
                // Prefer the "Reviews" role=tab. Click it every tick UNTIL it actually reports selected —
                // a click on a not-yet-hydrated tab silently no-ops, so one-and-done can leave the list
                // unopened. Once selected we latch `opened` and STOP clicking: re-clicking a selected-but-
                // still-loading list restarts its ~8 s render (that regression turned busy pages back to 3).
                var ts=[].slice.call(document.querySelectorAll('[role="tab"]'));
                for(var i=0;i<ts.length;i++){
                  var tl=((ts[i].getAttribute('aria-label')||ts[i].textContent)||'').trim();
                  if(/^reviews\b/i.test(tl)){
                    sawEntry=true;
                    if((ts[i].getAttribute('aria-selected')||'')==='true'){ if(!opened){ opened=true; openedAt=tries; openedBy='tab'; } return; }
                    try{ ts[i].click(); }catch(e){}
                    return;
                  }
                }
                // No reviews tab in this layout — fall back to the "More reviews" button. Unlike the
                // tab there's no aria-selected to confirm the click took, so it's clicked once and
                // the tick loop re-arms it ONE time if nothing ever renders (a not-yet-hydrated
                // button silently no-ops, same as the tab).
                if(opened) return;
                var bs=[].slice.call(document.querySelectorAll('button'));
                for(var i=0;i<bs.length;i++){ var l=((bs[i].getAttribute('aria-label')||bs[i].textContent)||''); if(/more reviews/i.test(l)){ sawEntry=true; try{ bs[i].click(); }catch(e){} opened=true; openedAt=tries; openedBy='btn'; return; } }
              }
    """.trimIndent()

    private val scraperTick = """
              function tick(){
                tries++;
                // Let the SPA hydrate a beat before clicking the Reviews tab — clicking a not-yet-live
                // tab on tick 1 can silently no-op, and then the list only renders much later.
                if(tries>=2) openFull();
                expand();
                // ACCUMULATE this window's cards (don't replace) — the panel virtualizes, so each
                // scroll position exposes a fresh ~10 that would otherwise be lost when recycled.
                // Except the TEXT: a card is often harvested on the tick its More toggle was
                // clicked, before Google's async re-render swaps in the full body — so a longer
                // text for a known key REPLACES the stored entry (the "…"-truncated first capture
                // otherwise won forever; the ellipsis was in our data, not the UI).
                var revs=extract();
                for(var i=0;i<revs.length;i++){ var k=key(revs[i]); if(k.length>2){ if(!acc[k]){ acc[k]=revs[i]; accN++; } else if((revs[i].t||'').length>(acc[k].t||'').length){ acc[k]=revs[i]; } } }
                // Stream progress whenever the count grows: the running count (drives the "N of ~M"
                // bar) AND the accumulated reviews themselves, so the sheet fills in under the bar
                // while the scrape grinds instead of making the user stare at a bar for 30 s.
                if(accN!==lastRep){ lastRep=accN;
                  try{ ReviewsBridge.onProgress(ID, accN); }catch(e){}
                  try{ ReviewsBridge.onPartial(ID, JSON.stringify(snap())); }catch(e){}
                }
                // Are review cards rendered RIGHT NOW? On busy business pages (food, retail) the Reviews
                // tab's list can take ~8 s to populate after the click; until then the panel holds only
                // the rating histogram + topic chips, NOT the cards. This must be a per-tick check, not a
                // once-latched flag: the OVERVIEW's 3 preview cards render briefly before the tab click
                // blanks the panel, and a latch set by those let the idle-bail fire during the blank
                // window with exactly 3 accumulated (the "loaded 3 then stopped" bug).
                var cardsNow = document.querySelectorAll('.jJc9Ad').length>0;
                if(cardsNow) everCards=true;
                var moved=scrollStep();
                atBottom = moved ? 0 : atBottom+1;
                noGrow = (accN===lastN) ? noGrow+1 : 0;
                lastN=accN;
                // Button-path no-op retry: the click was fired blind (no aria-selected to confirm)
                // and nothing has rendered since — re-arm openFull once. Tab clicks self-retry.
                if(opened && openedBy==='btn' && !everCards && tries>=openedAt+18 && btnReclicks<1){ opened=false; openedAt=-1; btnReclicks++; }
                // Idle-bail ONLY while cards are actually on screen. When an entry (tab/button)
                // exists but hasn't opened yet, hold longer so a late-hydrating tab still gets its
                // click; a layout with NO entry at all (a tiny place whose full list IS the
                // overview) settles quickly — there's nothing more to open.
                var settled = cardsNow && (opened ? tries>=openedAt+13 : (sawEntry ? tries>=30 : tries>=17));
                // Zero-review places: no card will EVER render, so "settled" never fires — bail on
                // a generous empty deadline instead of grinding to the 60-tick hard stop (~33 s of
                // blank spinner on every review-less place).
                var emptyDone = !everCards && accN===0 && (opened ? tries>=openedAt+52 : tries>=65);
                // Idle patience: the OPENED full list pages over the network — Google's lazy-loader
                // routinely takes >2 s to fetch the next ~10 on a busy place, and 4 quiet ticks
                // (2.2 s) misread that as "done" (Taco Bell returned ~15 of 612). The unopened
                // overview has nothing to page, so it keeps the short fuse.
                var idle = opened ? (atBottom>=13 && noGrow>=18) : (atBottom>=9 && noGrow>=9);
                // Done: cap hit, OR settled at the bottom with no new reviews, OR provably empty,
                // OR ran long.
                if( accN>=CAP || (settled && idle) || emptyDone || tries>130 ){
                  try{ ReviewsBridge.onResult(ID, JSON.stringify(snap())); }catch(e){ try{ ReviewsBridge.onResult(ID,'[]'); }catch(e2){} }
                  return;
                }
                setTimeout(tick, 250);
              }
    """.trimIndent()

    private val scraperFooter = """
              tick();
            })();
    """.trimIndent()

    private companion object {
        // Must outlast the script's own hard stop (130 ticks × 250 ms ≈ 33 s + page load) — if Kotlin
        // times out first we return EMPTY, which is worse than few. Lazy + best-effort as ever.
        const val TOTAL_TIMEOUT_MS = 45_000L
        const val SETTLE_MS = 150L
        const val MAX_LOAD_MS = 7_000L
        // Offscreen viewport for the headless WebView — tall so the virtualized review list renders a
        // healthy batch per scroll position.
        const val WV_WIDTH = 1200
        const val WV_HEIGHT = 6000
        // JS bridge request ids and the scraper's own review cap (was a `var CAP=50` literal).
        const val BRIDGE_ID_PREFIX = "r"
        const val SCRAPE_REVIEW_CAP = 50
        // The Google "cid" is the LOW half of `0xHIGH:0xLOW` as hex.
        const val CID_RADIX = 16
    }
}
