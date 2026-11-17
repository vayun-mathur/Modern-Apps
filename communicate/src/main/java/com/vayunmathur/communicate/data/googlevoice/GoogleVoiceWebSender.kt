package com.vayunmathur.communicate.data.googlevoice

import androidx.core.net.toUri
import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import com.vayunmathur.library.log.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.vayunmathur.communicate.data.CommunicateAttachment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sends a Google Voice SMS by borrowing Google's own bot-defense token, invisibly.
 *
 * `api2thread/sendsms` requires a `"!…"` WAA/botguard token that only Google's obfuscated JS can
 * mint. We therefore run the real web app in a **1×1, fully transparent, offscreen** WebView, let
 * its JS build the send request (which mints the token), **intercept and block** that request so
 * the WebView never actually sends, capture the full token-bearing body, and **replay it from the
 * app's native HTTP path** ([GoogleVoiceClient.sendPreparedSms]). Nothing is shown to the user.
 *
 * The composer DOM is obfuscated, so the injected automation logs each step to Logcat (`GVAUTO`).
 */
object GoogleVoiceWebSender {

    private const val TAG = "GVAUTO"

    /**
     * Mint the bot-defense token by letting the GV web app build a `sendsms` request in an
     * offscreen WebView, intercepting + blocking that request, and returning just the `"!…"`
     * token. The actual send is then performed natively by the app (see [CommunicateRepository]).
     */
    suspend fun mintToken(activity: Activity, recipient: String, text: String): String? {
        val body = mintBody(activity, recipient, text) ?: return null
        return GoogleVoiceParser.extractBotToken(body)
    }

    /** Return the full web-constructed sendsms body, including media upload metadata. */
    suspend fun mintPreparedBody(
        activity: Activity,
        recipient: String,
        text: String,
        attachments: List<CommunicateAttachment>,
    ): String? = mintBody(
        activity = activity,
        recipient = recipient,
        text = text,
        attachments = attachments.map { it.contentUri.toUri() },
    )

    /** Drive the offscreen WebView to produce the exact sendsms body (with token). */
    private suspend fun mintBody(
        activity: Activity,
        recipient: String,
        text: String,
        attachments: List<Uri> = emptyList(),
    ): String? {
        // One session per mint: the WebView (and the Activity it is created
        // with) is held only by this call frame and destroyed in the finally
        // below, never by the singleton.
        val session = MintSession(recipient, text, attachments)
        try {
            withContext(Dispatchers.Main) {
                val wv = session.ensureWebView(activity)
                wv.loadUrl("https://voice.google.com/u/0/messages")
            }
            return withTimeoutOrNull(if (attachments.isEmpty()) 60_000 else 120_000) {
                session.deferred.await()
            }
        } finally {
            withContext(Dispatchers.Main) { session.disposeWebView() }
        }
    }

    /** Per-mint WebView state; see [mintBody]. */
    private class MintSession(
        val recipient: String,
        val text: String,
        val attachments: List<Uri>,
    ) {
        val deferred = CompletableDeferred<String>()
        var webView: WebView? = null

        fun disposeWebView() {
            val old = webView ?: return
            runCatching { old.stopLoading() }
            runCatching { (old.parent as? ViewGroup)?.removeView(old) }
            runCatching { old.destroy() }
            webView = null
        }

        @SuppressLint("SetJavaScriptEnabled")
        fun ensureWebView(activity: Activity): WebView {
        webView?.let { return it }
        CookieManager.getInstance().setAcceptCookie(true)
        val wv = WebView(activity)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36"
        wv.addJavascriptInterface(Bridge(deferred), "AndroidGV")
        // Install the fetch/XHR hook BEFORE any page script runs, so the GV app can't capture the
        // original fetch first (that's why onPageFinished injection missed the send).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(wv, hookScript(), setOf("https://voice.google.com"))
            }
        }
        wv.webViewClient = object : WebViewClient() {
            private var injected = false
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Fallback hook injection for devices without DOCUMENT_START_SCRIPT.
                view?.evaluateJavascript(hookScript(), null)
            }
            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?,
            ): Boolean {
                // Renderer died (crash/OOM): fail the pending mint instead of
                // taking the whole process down with the WebView.
                deferred.complete("")
                disposeWebView()
                return true
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url == null || !url.contains("voice.google.com/u/")) return
                if (injected) return
                injected = true
                view?.evaluateJavascript(
                    automationScript(recipient, text, hasAttachments = attachments.isNotEmpty()),
                    null,
                )
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                Log.dev(TAG, "file chooser requested for ${attachments.size} attachment(s)")
                filePathCallback?.onReceiveValue(attachments.toTypedArray())
                return true
            }
        }
        // Place the WebView BEHIND the app's opaque UI: it must lay out on-screen for GV's CDK
        // autocomplete/overlay (recipient chip) to work, but the opaque Compose content covers it
        // (invisible) and receives all touches, so it stays hidden and non-interactive.
        val decor = activity.window.decorView as ViewGroup
        decor.addView(
            wv,
            0,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        webView = wv
        return wv
    }

        private class Bridge(
            private val deferred: CompletableDeferred<String>,
        ) {
            @JavascriptInterface
            fun onBody(body: String) {
                Log.dev(TAG, "captured sendsms body (${body.length} bytes)")
                deferred.complete(body)
            }

            @JavascriptInterface
            fun log(msg: String) {
                Log.dev(TAG, msg)
            }
        }
    }

    /** Installed at document-start: hook fetch + XHR to capture & block the sendsms request. */
    private fun hookScript(): String = """
        (function(){
          if(window.__gvHook) return; window.__gvHook=1;
          function log(m){ try{ AndroidGV.log(""+m);}catch(e){} }
          var of = window.fetch;
          window.fetch = function(input, init){
            try{
              var url = (typeof input==='string')?input:(input&&input.url);
              if(url && url.indexOf('api2thread/sendsms')>=0){
                var b = init && init.body;
                if(typeof b==='string'){ AndroidGV.onBody(b); log('blocked fetch sendsms');
                  return Promise.resolve(new Response('[]',{status:200,headers:{'Content-Type':'application/json+protobuf'}}));
                }
              }
            }catch(e){ log('fetch hook err '+e); }
            return of.apply(this, arguments);
          };
          var ou = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.open=function(m,u){ this.__u=u; return ou.apply(this,arguments); };
          XMLHttpRequest.prototype.send=function(b){
            try{
              if(this.__u && this.__u.indexOf('api2thread/sendsms')>=0 && typeof b==='string'){
                AndroidGV.onBody(b); log('blocked xhr sendsms');
                var self=this;
                Object.defineProperty(self,'readyState',{value:4,configurable:true});
                Object.defineProperty(self,'status',{value:200,configurable:true});
                Object.defineProperty(self,'responseText',{value:'[]',configurable:true});
                if(self.onreadystatechange) self.onreadystatechange();
                if(self.onload) self.onload();
                return;
              }
            }catch(e){ log('xhr hook err '+e); }
            return os.apply(this, arguments);
          };
          log('hooks installed (doc-start)');
        })();
    """.trimIndent()

    /**
     * Runs after load: automate the composer to make the page build (and thus mint the token for)
     * the sendsms request. Sequence: open composer → fill recipient → fill message → click the real
     * "Send message" button (NOT the "Send new message" FAB). Dumps DOM to Logcat for tuning.
     */
    private fun automationScript(recipient: String, text: String, hasAttachments: Boolean): String {
        val num = recipient.replace("\"", "")
        val body = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val wantsAttachment = if (hasAttachments) "true" else "false"
        return automationScriptHead(num, body, wantsAttachment) + automationScriptTail()
    }

    /** First half of the WebView automation JS (helpers + composer dump). */
    private fun automationScriptHead(num: String, body: String, wantsAttachment: String): String {
        return """
            (function(){
              function log(m){ try{ AndroidGV.log(""+m);}catch(e){} }
              var NUM="$num", TEXT="$body", WANT_ATTACH=$wantsAttachment;
              function qa(s){ return Array.prototype.slice.call(document.querySelectorAll(s)); }
              function q(s){ return document.querySelector(s); }
              function lbl(e){ return (e.getAttribute('aria-label')||'').toLowerCase(); }
              function ph(e){ return (e.getAttribute('placeholder')||'').toLowerCase(); }
              function vis(e){ return !!(e && e.offsetParent!==null); }
              function fld(e){ return e.tagName+'|al='+lbl(e)+'|ph='+ph(e)+'|vis='+vis(e)+'|t='+((e.type||'').toLowerCase()); }
              function dumpFields(tag){ log(tag+' '+JSON.stringify(qa('input,textarea,[contenteditable="true"]').map(fld)).slice(0,1500)); }
              function byLabel(p){ p=p.toLowerCase(); return qa('[aria-label]').filter(function(e){return lbl(e).indexOf(p)>=0;}); }
              function labels(){ return qa('[aria-label]').map(function(e){return e.tagName+'#'+(e.getAttribute('aria-label')||'')+(e.getAttribute('aria-disabled')?('/dis='+e.getAttribute('aria-disabled')):'');}); }
              function setNative(el,val){ try{ var proto=el.tagName==='TEXTAREA'?window.HTMLTextAreaElement.prototype:window.HTMLInputElement.prototype; Object.getOwnPropertyDescriptor(proto,'value').set.call(el,val);}catch(e){ el.value=val; } el.dispatchEvent(new Event('input',{bubbles:true})); el.dispatchEvent(new Event('change',{bubbles:true})); }
              function type(el,val){ el.focus(); var ok=false; try{ ok=document.execCommand('insertText',false,val);}catch(e){} if(!ok){ if(el.tagName==='TEXTAREA'||el.tagName==='INPUT'){ setNative(el,val);} else { el.textContent=val; el.dispatchEvent(new InputEvent('input',{bubbles:true,data:val,inputType:'insertText'})); } } }
              function typeReal(el,val){ el.focus(); try{ el.value=''; }catch(e){} for(var i=0;i<val.length;i++){ var ch=val.charAt(i); el.dispatchEvent(new KeyboardEvent('keydown',{bubbles:true,key:ch})); try{ el.value=(el.value||'')+ch; }catch(e){} el.dispatchEvent(new InputEvent('input',{bubbles:true,data:ch,inputType:'insertText'})); el.dispatchEvent(new KeyboardEvent('keyup',{bubbles:true,key:ch})); } el.dispatchEvent(new Event('change',{bubbles:true})); }
              function enter(el){ ['keydown','keypress','keyup'].forEach(function(t){ el.dispatchEvent(new KeyboardEvent(t,{bubbles:true,key:'Enter',code:'Enter',keyCode:13,which:13})); }); }
              function visibleButtons(){ return qa('button,[role="button"],[aria-label]').filter(vis); }
              function findAttach(){ var bs=visibleButtons(); for(var i=0;i<bs.length;i++){ var l=lbl(bs[i]); if(l.indexOf('attach')>=0||l.indexOf('photo')>=0||l.indexOf('image')>=0||l.indexOf('media')>=0||l.indexOf('mms')>=0) return bs[i]; } var file=q('input[type="file"]'); if(file) return file; return null; }
              var B=document.body;
              var step=0;
              var timer=setInterval(function(){
                step++; if(step>70){ clearInterval(timer); log('giveup'); return; }
                try{
                  if(step===1){ log('DUMP ta='+qa('textarea').length+' ce='+qa('[contenteditable="true"]').length+' in='+qa('input').length); log('DUMP '+JSON.stringify(labels()).slice(0,1600)); }

                  // 1) Open the composer first. A label-less textarea on the inbox page is NOT the
                  //    message box, so gate everything on having clicked "Send new message".
                  if(!B.dataset.gvOpen){
                    var fab=byLabel('send new message')[0]||byLabel('new conversation')[0]||byLabel('start a conversation')[0];
                    if(fab){ B.dataset.gvOpen='1'; B.dataset.gvOpenStep=String(step); log('open composer'); fab.click(); }
                    else log('await fab '+step);
                    return;
                  }
                  // Dump the composer DOM once shortly after opening.
                  if(!B.dataset.gvDump2 && (step-(parseInt(B.dataset.gvOpenStep)||0))>=2){ B.dataset.gvDump2='1'; log('DUMP2 '+JSON.stringify(labels()).slice(0,1200)); dumpFields('FIELDS'); }
            """.trimIndent()
    }

    /** Second half of the WebView automation JS (recipient/message/send + poller). */
    private fun automationScriptTail(): String {
        return """
                  // 2) Recipient input: prefer placeholder/label mentioning name/phone; exclude Search.
                  function findRecip(){ var ins=qa('input,textarea').filter(vis); for(var i=0;i<ins.length;i++){ var s=lbl(ins[i])+' '+ph(ins[i]); if(s.indexOf('search')<0 && (s.indexOf('name')>=0||s.indexOf('phone')>=0||s.indexOf('recipient')>=0)) return ins[i]; } for(var j=0;j<ins.length;j++){ var t=(ins[j].type||'text').toLowerCase(); if((t==='text'||t==='tel') && (lbl(ins[j])+ph(ins[j])).indexOf('search')<0) return ins[j]; } return null; }
                  // 3) Message box: a visible textarea/contenteditable, preferring message/text hints.
                  function findMsg(){ var cs=qa('textarea,div[contenteditable="true"]').filter(vis); for(var i=0;i<cs.length;i++){ var s=lbl(cs[i])+' '+ph(cs[i]); if(s.indexOf('message')>=0||s.indexOf('text')>=0) return cs[i]; } return cs.length?cs[cs.length-1]:null; }
                  var recip=findRecip();
                  var msg=findMsg();
                  // 4) Real send button: a BUTTON labelled exactly "Send message" (NOT the mat-label
                  //    "Select recipients to send a message to", and not "Send new message").
                  function findSend(){ var bs=qa('button[aria-label]'); var best=null; for(var i=0;i<bs.length;i++){ var l=lbl(bs[i]); if(l.indexOf('new')>=0||l.indexOf('select')>=0) continue; if(l==='send message'||l==='send sms'||l==='send') return bs[i]; if(l.indexOf('send')>=0&&l.indexOf('message')>=0&&!best) best=bs[i]; } return best; }
                  var send=findSend();

                  if(recip && !B.dataset.gvRecip){ B.dataset.gvRecip='1'; log('fill recipient ['+fld(recip)+']');
                    recip.focus(); var ok=false; try{ ok=document.execCommand('insertText',false,NUM);}catch(e){} if(!ok){ typeReal(recip,NUM); }
                    var digits=NUM.replace(/[^0-9]/g,'');
                    var tries=0; var st=setInterval(function(){ tries++;
                      var opts=qa('[role="option"],mat-option,.mat-mdc-option,[role="listbox"] li,ul[role="listbox"] *');
                      var textMatches=qa('div,span,li,button,a,[jsaction]').filter(function(e){ return e.children.length===0 && (e.textContent||'').replace(/[^0-9]/g,'').indexOf(digits)>=0; });
                      if(tries%3===1){ log('SUG '+tries+' opt='+opts.length+' txt='+textMatches.length); }
                      if(tries===2){ log('SUGDUMP '+JSON.stringify(opts.slice(0,6).map(function(e){return e.tagName+':'+(e.textContent||'').slice(0,24);})).slice(0,500)); log('TXTDUMP '+JSON.stringify(textMatches.slice(0,6).map(function(e){return e.tagName+':'+(e.textContent||'').slice(0,24);})).slice(0,500)); }
                      var pick=opts[0]||textMatches[0];
                      if(pick){ log('pick ['+pick.tagName+':'+(pick.textContent||'').slice(0,24)+']'); pick.click(); B.dataset.gvRecipDone='1'; clearInterval(st); }
                      else if(tries>=18){ clearInterval(st); log('commit fallback (enter/comma/blur)'); recip.focus(); enter(recip);
                        recip.dispatchEvent(new KeyboardEvent('keydown',{bubbles:true,key:',',keyCode:188,which:188}));
                        recip.dispatchEvent(new KeyboardEvent('keyup',{bubbles:true,key:',',keyCode:188,which:188}));
                        try{ recip.blur(); }catch(e){}
                        B.dataset.gvRecipDone='1'; }
                    }, 500);
                    return;
                  }
                  if(!B.dataset.gvRecip){ log('await recip '+step); return; }
                  if(!B.dataset.gvRecipDone){ log('await recip commit '+step); return; }
                  // Fill the message once the recipient chip is committed. MMS can be media-only,
                  // but entering text when present helps the web composer enable its final send path.
                  if(msg && !B.dataset.gvMsg){ B.dataset.gvMsg='1'; log('fill message ['+fld(msg)+']'); if(TEXT.length>0) type(msg,TEXT); return; }
                  if(!B.dataset.gvMsg){ if(step%4===0) dumpFields('FIELDS@'+step); log('await msg '+step); return; }
                  if(WANT_ATTACH && !B.dataset.gvAttach){
                    var attach=findAttach();
                    if(attach){ B.dataset.gvAttach='1'; log('click attach ['+attach.tagName+' '+lbl(attach)+']'); attach.click(); return; }
                    log('await attach '+step+' labels='+JSON.stringify(labels()).slice(0,900)); return;
                  }
                  if(WANT_ATTACH && !B.dataset.gvAttachWait){ B.dataset.gvAttachWait=String(step); return; }
                  if(WANT_ATTACH && (step-(parseInt(B.dataset.gvAttachWait)||step))<6){ log('wait upload '+step); return; }
                  if(send){ var dis=(send.getAttribute('aria-disabled')==='true'||send.disabled); if(!dis){ log('click SEND ['+lbl(send)+']'); send.click(); clearInterval(timer); log('sent-clicked'); return; } else { log('send disabled '+step+' recipVal='+((recip&&(recip.value||recip.textContent))||'')); return; } }
                  log('await send '+step+' recip='+!!recip+' msg='+!!msg);
                }catch(e){ log('auto err '+e); }
              }, 800);
            })();
        """.trimIndent()
    }
}
