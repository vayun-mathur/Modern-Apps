package com.vayunmathur.youpipe.util.sabr

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrProtocolException
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrScriptPolicy
import java.util.WeakHashMap

internal object QuickJsSabrRuntime {
    private const val MAX_RESULT_CHARS = 512 * 1024
    private const val MEMORY_LIMIT_BYTES = 32L * 1024 * 1024
    private const val MAX_STACK_BYTES = 512L * 1024

    private val quickJs = QuickJs.create(Dispatchers.Unconfined)
    private val compiledPolicies = WeakHashMap<SabrScriptPolicy, ByteArray>()
    private var inputJson = "{}"
    private var methodName = "describe"
    private var activeSessionId = 0
    private var nextSessionId = 1
    private val invokeBytecode: ByteArray
    private val closeBytecode: ByteArray

    init {
        quickJs.memoryLimit = MEMORY_LIMIT_BYTES
        quickJs.maxStackSize = MAX_STACK_BYTES
        quickJs.function<String>("__hostInput") { inputJson }
        quickJs.function<String>("__hostMethod") { methodName }
        quickJs.function<Int>("__hostSession") { activeSessionId }
        runBlocking {
            quickJs.evaluate<Any?>(RUNTIME_PRELUDE + "var __sabrPolicies=Object.create(null);")
        }
        invokeBytecode = quickJs.compile(
            "(function(){var p=__sabrPolicies[__hostSession()];" +
                "if(!p)throw Error('closed SABR policy');var m=__hostMethod(),f=p[m];" +
                "if(typeof f!=='function')throw Error('missing method '+m);" +
                "return JSON.stringify(f.call(p,JSON.parse(__hostInput())))})()",
        )
        closeBytecode = quickJs.compile("delete __sabrPolicies[__hostSession()]")
    }

    @Synchronized
    fun createSession(script: SabrScriptPolicy): Int {
        val sessionId = nextSessionId++
        if (sessionId <= 0) throw SabrProtocolException("SABR QuickJS session limit reached")
        activeSessionId = sessionId
        try {
            val bootstrap = compiledPolicies[script] ?: quickJs.compile(
                "(function(id){\n" + script.source +
                    "\n;if(typeof createSabrPolicy!=='function')" +
                    "throw Error('missing createSabrPolicy');" +
                    "var p=createSabrPolicy(sabr);" +
                    "if(!p)throw Error('createSabrPolicy returned no policy');" +
                    "__sabrPolicies[id]=p;})(__hostSession())",
            ).also { compiledPolicies[script] = it }
            runBlocking { quickJs.evaluate<Any?>(bootstrap) }
            return sessionId
        } catch (error: Exception) {
            throw SabrProtocolException("Could not initialize SABR QuickJS policy", error)
        }
    }

    @Synchronized
    fun invoke(sessionId: Int, method: String, input: JsonObject): JsonObject {
        activeSessionId = sessionId
        methodName = method
        inputJson = JsonWriter.string(input)
        return try {
            val result = runBlocking { quickJs.evaluate<String>(invokeBytecode) }
            if (result.length > MAX_RESULT_CHARS) {
                throw SabrProtocolException("SABR QuickJS result exceeded limit")
            }
            JsonParser.`object`().from(result)
        } catch (error: SabrProtocolException) {
            throw error
        } catch (error: Exception) {
            throw SabrProtocolException("SABR QuickJS method failed: $method", error)
        }
    }

    @Synchronized
    fun closeSession(sessionId: Int) {
        activeSessionId = sessionId
        runBlocking { quickJs.evaluate<Any?>(closeBytecode) }
        quickJs.gc()
    }

    private const val RUNTIME_PRELUDE =
        "var sabr=(function(){'use strict';" +
            "var A='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';" +
            "function b64d(s){var o=[],v=0,n=0,i,c;for(i=0;i<s.length;i++){c=A.indexOf(s.charAt(i));" +
            "if(c<0)continue;v=(v<<6)|c;n+=6;if(n>=8){n-=8;o.push((v>>n)&255)}}return o}" +
            "function b64e(a){var o='',i,v;for(i=0;i<a.length;i+=3){v=(a[i]&255)<<16;" +
            "if(i+1<a.length)v|=(a[i+1]&255)<<8;if(i+2<a.length)v|=a[i+2]&255;" +
            "o+=A[(v>>18)&63]+A[(v>>12)&63]+(i+1<a.length?A[(v>>6)&63]:'=')" +
            "+(i+2<a.length?A[v&63]:'=')}return o}" +
            "function varint(a,p){var v=0,m=1,b;do{if(p.i>=a.length)throw Error('truncated varint');" +
            "b=a[p.i++];v+=(b&127)*m;m*=128}while(b&128);return v}" +
            "function dec(a){var p={i:0},o=[],k,n,w,l,s;while(p.i<a.length){k=varint(a,p);" +
            "n=Math.floor(k/8);w=k%8;if(!n)throw Error('field zero');if(w===0)o.push({n:n,w:w,v:varint(a,p)});" +
            "else if(w===1){s=a.slice(p.i,p.i+8);p.i+=8;o.push({n:n,w:w,b:s})}" +
            "else if(w===2){l=varint(a,p);s=a.slice(p.i,p.i+l);p.i+=l;o.push({n:n,w:w,b:s})}" +
            "else if(w===5){s=a.slice(p.i,p.i+4);p.i+=4;o.push({n:n,w:w,b:s})}" +
            "else throw Error('wire '+w)}return o}" +
            "function vi(o,v){v=Math.floor(v);while(v>127){o.push((v%128)|128);v=Math.floor(v/128)}o.push(v)}" +
            "function enc(fs){var o=[],i,f,b;for(i=0;i<fs.length;i++){f=fs[i];vi(o,f.n*8+f.w);" +
            "if(f.w===0)vi(o,f.v);else{b=f.b||[];if(f.w===2)vi(o,b.length);o=o.concat(b)}}return o}" +
            "return{base64:{decode:b64d,encode:b64e},proto:{decode:dec,encode:enc}}})();"
}
