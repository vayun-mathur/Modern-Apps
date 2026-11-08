package com.vayunmathur.backup.data.backend

import android.net.Uri
import android.util.Base64
import com.vayunmathur.library.network.NetworkClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * [BackupBackend] over a WebDAV/Nextcloud remote, using the platform HTTP stack
 * ([NetworkClient], HttpURLConnection — including the reflection-based support for
 * the WebDAV verbs MKCOL/PROPFIND). Blobs are files under [baseUrl]; directories are
 * WebDAV collections. Authentication is HTTP Basic.
 *
 * Note: PUT/GET buffer a blob fully in memory (HttpURLConnection needs a known
 * content length), so very large full-data app blobs are best sent to a [SafBackend].
 */
class WebDavBackend(
    baseUrl: String,
    username: String,
    password: String,
    displayName: String? = null,
) : BackupBackend {

    private val base = baseUrl.trimEnd('/')
    private val authHeader =
        "Basic " + Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)

    override val displayName: String = displayName ?: (Uri.parse(base).host ?: "WebDAV")

    private fun url(path: String): String {
        val encoded = path.split('/').filter { it.isNotEmpty() }.joinToString("/") { Uri.encode(it) }
        return if (encoded.isEmpty()) base else "$base/$encoded"
    }

    private fun headers(vararg extra: Pair<String, String>) =
        mapOf("Authorization" to authHeader, *extra)

    override suspend fun ensureDir(path: String) {
        // MKCOL creates a single collection; create each ancestor progressively.
        val segments = path.split('/').filter { it.isNotEmpty() }
        var current = ""
        for (seg in segments) {
            current = if (current.isEmpty()) seg else "$current/$seg"
            val resp = NetworkClient.execute(url(current), method = "MKCOL", headers = headers())
            // 201 created; 405/301/200 tolerated as "already exists"; 409 is a
            // parent race and also tolerated. Anything else (401/403/500...) is
            // a real failure and must not be silently treated as success.
            if (resp.status !in intArrayOf(HTTP_CREATED, HTTP_METHOD_NOT_ALLOWED, HTTP_MOVED, HTTP_OK, HTTP_CONFLICT)) {
                throw IOException("WebDAV MKCOL $current failed: ${resp.status}")
            }
        }
    }

    override suspend fun write(path: String, writer: suspend (OutputStream) -> Unit) {
        val buffer = ByteArrayOutputStream()
        writer(buffer)
        val parent = path.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) ensureDir(parent)
        val resp = NetworkClient.execute(
            url(path),
            method = "PUT",
            headers = headers("Content-Type" to "application/octet-stream"),
            body = buffer.toByteArray(),
        )
        if (!resp.isSuccess) throw IOException("WebDAV PUT $path failed: ${resp.status}")
    }

    override suspend fun <T> read(path: String, reader: suspend (InputStream) -> T): T {
        val resp = NetworkClient.execute(url(path), method = "GET", headers = headers())
        if (resp.status == HTTP_NOT_FOUND) throw FileNotFoundException(path)
        if (!resp.isSuccess) throw IOException("WebDAV GET $path failed: ${resp.status}")
        return ByteArrayInputStream(resp.bytes).use { reader(it) }
    }

    override suspend fun list(dir: String): List<String> {
        val resp = NetworkClient.execute(
            url(dir),
            method = "PROPFIND",
            headers = headers("Depth" to "1", "Content-Type" to "application/xml"),
            body = PROPFIND_BODY,
        )
        if (resp.status == HTTP_NOT_FOUND) return emptyList()
        if (!resp.isSuccess && resp.status != HTTP_MULTI_STATUS) {
            throw IOException("WebDAV PROPFIND $dir failed: ${resp.status}")
        }
        val selfPath = Uri.parse(url(dir)).path?.trimEnd('/') ?: ""
        return HREF_REGEX.findAll(resp.text)
            .map { Uri.decode(it.groupValues[1]).trimEnd('/') }
            .filter { it.isNotEmpty() && it != selfPath }
            .map { it.substringAfterLast('/') }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    override suspend fun delete(path: String) {
        val resp = NetworkClient.execute(url(path), method = "DELETE", headers = headers())
        if (!resp.isSuccess && resp.status != HTTP_NOT_FOUND) {
            throw IOException("WebDAV DELETE $path failed: ${resp.status}")
        }
    }

    override suspend fun exists(path: String): Boolean {
        val resp = NetworkClient.execute(
            url(path),
            method = "PROPFIND",
            headers = headers("Depth" to "0"),
        )
        return resp.status == HTTP_MULTI_STATUS || resp.isSuccess
    }

    companion object {
        private const val HTTP_OK = 200
        private const val HTTP_CREATED = 201
        private const val HTTP_MOVED = 301
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_CONFLICT = 409
        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private const val HTTP_MULTI_STATUS = 207

        private const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
        private val HREF_REGEX = Regex("<[^>]*href[^>]*>([^<]+)</[^>]*href>", RegexOption.IGNORE_CASE)
    }
}
