package com.vayunmathur.everysync.remote

import android.util.Log
import com.vayunmathur.everysync.auth.DavCredentials
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.SimpleResponse
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.encoding.Base64

/** A single WebDAV resource (vCard / iCalendar object) with its ETag. */
data class DavResource(
    val href: String,
    val etag: String?,
    val data: String? = null,
)

/** A discovered DAV collection (addressbook or calendar). */
data class DavCollection(
    val url: String,
    val displayName: String,
    val ctag: String? = null,
    val color: Int? = null,
)

/**
 * WebDAV client for CalDAV (RFC 4791) and CardDAV (RFC 6352) over the shared
 * ktor [NetworkClient], using the custom methods PROPFIND / REPORT / PUT / DELETE.
 * Change detection uses collection ctags and per-resource ETags; the caller
 * decides what to re-fetch.
 */
class DavClient(private val authHeader: () -> String) {

    /** Basic-auth client for username/password DAV servers (iCloud, generic). */
    constructor(creds: DavCredentials) : this({
        "Basic " + Base64.encode("${creds.username}:${creds.password}".toByteArray())
    })

    private fun authHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> =
        mapOf("Authorization" to authHeader()) + extra

    private suspend fun request(url: String, method: String, headers: Map<String, String>, body: String?): SimpleResponse =
        NetworkClient.performRequest(url, method, headers, body)

    /**
     * Discover addressbook or calendar collections for the account. Follows the
     * RFC 6764 well-known flow: current-user-principal → {calendar,addressbook}-
     * home-set → depth-1 listing of the home collection. Falls back to treating
     * [baseUrl] itself as a collection host (for generic servers where the user
     * entered a collection/home URL directly).
     */
    suspend fun discoverCollections(baseUrl: String, isCalendar: Boolean): List<DavCollection> {
        val out = mutableListOf<DavCollection>()
        try {
            // 1. Resolve the current-user-principal (try the base, then well-known).
            val principal = findPrincipal(baseUrl, isCalendar)
            // 2. Resolve the home-set (calendars/addressbooks live under it).
            val homeSet = principal?.let { findHomeSet(it, isCalendar) }
            // 3. List collections under the home-set; fall back to the base URL.
            homeSet?.let { out += listCollectionsUnder(it, isCalendar) }
            if (out.isEmpty()) out += listCollectionsUnder(baseUrl, isCalendar)
        } catch (e: Exception) {
            Log.e(TAG, "discoverCollections failed", e)
        }
        Log.i(TAG, "discoverCollections(isCalendar=$isCalendar) base=$baseUrl -> ${out.size} collection(s): ${out.map { it.url }}")
        return out
    }

    /** PROPFIND depth-0 for current-user-principal, trying the base then well-known. */
    private suspend fun findPrincipal(baseUrl: String, isCalendar: Boolean): String? {
        val body = """<?xml version="1.0"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:current-user-principal/></d:prop></d:propfind>"""
        val wellKnown = origin(baseUrl) + if (isCalendar) "/.well-known/caldav" else "/.well-known/carddav"
        for (url in listOf(baseUrl, wellKnown)) {
            val href = firstHref(url, "0", body, "current-user-principal")
            if (href != null) return href
        }
        return null
    }

    /** PROPFIND depth-0 for the calendar/addressbook home-set on the principal. */
    private suspend fun findHomeSet(principalUrl: String, isCalendar: Boolean): String? {
        val body = if (isCalendar) {
            """<?xml version="1.0"?>
               <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                 <d:prop><c:calendar-home-set/></d:prop></d:propfind>"""
        } else {
            """<?xml version="1.0"?>
               <d:propfind xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                 <d:prop><card:addressbook-home-set/></d:prop></d:propfind>"""
        }
        val prop = if (isCalendar) "calendar-home-set" else "addressbook-home-set"
        return firstHref(principalUrl, "0", body, prop)
    }

    /** Depth-1 PROPFIND that returns every calendar/addressbook collection under [url]. */
    private suspend fun listCollectionsUnder(url: String, isCalendar: Boolean): List<DavCollection> {
        val propBody = if (isCalendar) {
            """<?xml version="1.0"?>
               <d:propfind xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:ic="http://apple.com/ns/ical/">
                 <d:prop><d:resourcetype/><d:displayname/><cs:getctag/><ic:calendar-color/></d:prop>
               </d:propfind>"""
        } else {
            """<?xml version="1.0"?>
               <d:propfind xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
                 <d:prop><d:resourcetype/><d:displayname/><cs:getctag/></d:prop>
               </d:propfind>"""
        }
        val collectionTag = if (isCalendar) "calendar" else "addressbook"
        val out = mutableListOf<DavCollection>()
        try {
            val resp = request(url, "PROPFIND", authHeaders(mapOf("Depth" to "1", "Content-Type" to "application/xml")), propBody)
            for (r in parseResponses(resp.body)) {
                if (collectionTag in r.resourceTypes) {
                    out += DavCollection(
                        url = resolve(url, r.href),
                        displayName = r.displayName ?: r.href.trimEnd('/').substringAfterLast('/'),
                        ctag = r.ctag,
                        color = r.color,
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "listCollectionsUnder failed", e)
        }
        return out
    }

    /** PROPFIND [url] and return the first `<href>` nested inside property [propLocalName]. */
    private suspend fun firstHref(url: String, depth: String, body: String, propLocalName: String): String? {
        return try {
            val resp = request(url, "PROPFIND", authHeaders(mapOf("Depth" to depth, "Content-Type" to "application/xml")), body)
            if (resp.body.isBlank()) return null
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(resp.body.toByteArray()))
            val props = doc.getElementsByTagNameNS("*", propLocalName)
            if (props.length == 0) return null
            val hrefs = (props.item(0) as Element).getElementsByTagNameNS("*", "href")
            if (hrefs.length == 0) return null
            hrefs.item(0).textContent?.trim()?.ifBlank { null }?.let { resolve(url, it) }
        } catch (e: Exception) {
            Log.e(TAG, "firstHref($propLocalName) failed", e); null
        }
    }

    private fun origin(url: String): String = try {
        val u = URI(url)
        val port = if (u.port != -1) ":${u.port}" else ""
        "${u.scheme}://${u.host}$port"
    } catch (_: Exception) { url.trimEnd('/') }

    /** List resources (href + etag only) in a collection. */
    suspend fun listResources(collectionUrl: String): List<DavResource> {
        val body = """<?xml version="1.0"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:getetag/></d:prop></d:propfind>"""
        return try {
            val resp = request(collectionUrl, "PROPFIND", authHeaders(mapOf("Depth" to "1", "Content-Type" to "application/xml")), body)
            parseResponses(resp.body)
                .filter { !it.href.trimEnd('/').equals(URI(collectionUrl).path.trimEnd('/')) && it.etag != null }
                .map { DavResource(resolve(collectionUrl, it.href), it.etag) }
        } catch (e: Exception) {
            Log.e(TAG, "listResources failed", e); emptyList()
        }
    }

    /** Fetch full object bodies (address-data / calendar-data) for the given hrefs. */
    suspend fun multiget(collectionUrl: String, hrefs: List<String>, isCalendar: Boolean): List<DavResource> {
        if (hrefs.isEmpty()) return emptyList()
        val ns = if (isCalendar) "urn:ietf:params:xml:ns:caldav" else "urn:ietf:params:xml:ns:carddav"
        val reportName = if (isCalendar) "c:calendar-multiget" else "c:addressbook-multiget"
        val dataElem = if (isCalendar) "c:calendar-data" else "c:address-data"
        val hrefXml = hrefs.joinToString("") { "<d:href>${URI(it).path}</d:href>" }
        val body = """<?xml version="1.0"?>
            <$reportName xmlns:d="DAV:" xmlns:c="$ns">
              <d:prop><d:getetag/><$dataElem/></d:prop>
              $hrefXml
            </$reportName>"""
        return try {
            val resp = request(collectionUrl, "REPORT", authHeaders(mapOf("Depth" to "1", "Content-Type" to "application/xml")), body)
            parseResponses(resp.body).map { DavResource(resolve(collectionUrl, it.href), it.etag, it.data) }
        } catch (e: Exception) {
            Log.e(TAG, "multiget failed", e); emptyList()
        }
    }

    /** PUT an object, returning the new ETag if the server supplied one. */
    suspend fun put(url: String, contentType: String, body: String, ifMatch: String? = null): String? {
        val headers = authHeaders(buildMap {
            put("Content-Type", contentType)
            if (ifMatch != null) put("If-Match", ifMatch)
        })
        return try {
            val resp = request(url, "PUT", headers, body)
            resp.headers["ETag"]?.firstOrNull() ?: resp.headers["Etag"]?.firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "put failed", e); null
        }
    }

    suspend fun delete(url: String, ifMatch: String? = null) {
        val headers = authHeaders(if (ifMatch != null) mapOf("If-Match" to ifMatch) else emptyMap())
        try {
            request(url, "DELETE", headers, null)
        } catch (e: Exception) {
            Log.e(TAG, "delete failed", e)
        }
    }

    // --- XML parsing ---

    private data class ParsedResponse(
        val href: String,
        val etag: String?,
        val data: String?,
        val displayName: String?,
        val ctag: String?,
        val color: Int?,
        val resourceTypes: Set<String>,
    )

    private fun parseResponses(xml: String): List<ParsedResponse> {
        if (xml.isBlank()) return emptyList()
        val out = mutableListOf<ParsedResponse>()
        try {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
            val responses = doc.getElementsByTagNameNS("*", "response")
            for (i in 0 until responses.length) {
                val resp = responses.item(i) as? Element ?: continue
                val href = firstText(resp, "href") ?: continue
                out += ParsedResponse(
                    href = href,
                    etag = firstText(resp, "getetag")?.trim('"'),
                    data = firstText(resp, "address-data") ?: firstText(resp, "calendar-data"),
                    displayName = firstText(resp, "displayname"),
                    ctag = firstText(resp, "getctag"),
                    color = firstText(resp, "calendar-color")?.let { parseColor(it) },
                    resourceTypes = resourceTypes(resp),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseResponses failed", e)
        }
        return out
    }

    private fun firstText(scope: Element, localName: String): String? {
        val nodes = scope.getElementsByTagNameNS("*", localName)
        return if (nodes.length > 0) nodes.item(0).textContent?.trim()?.ifBlank { null } else null
    }

    private fun resourceTypes(resp: Element): Set<String> {
        val types = mutableSetOf<String>()
        val rt = resp.getElementsByTagNameNS("*", "resourcetype")
        if (rt.length > 0) {
            val children = (rt.item(0) as Element).childNodes
            for (i in 0 until children.length) {
                (children.item(i) as? Element)?.localName?.let { types += it.lowercase() }
            }
        }
        return types
    }

    private fun parseColor(value: String): Int? = try {
        val hex = value.trim().removePrefix("#").take(6)
        (0xFF000000.toInt()) or hex.toInt(16)
    } catch (_: Exception) { null }

    private fun resolve(base: String, href: String): String = try {
        URI(base).resolve(href).toString()
    } catch (_: Exception) { href }

    companion object {
        private const val TAG = "DavClient"
    }
}
