package de.sonoss1.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

data class HttpResponse(
    val code: Int,
    val finalUrl: String,
    /** Header names are lower-cased. */
    val headers: Map<String, String>,
    val body: String,
) {
    val contentType: String? get() = headers["content-type"]?.substringBefore(';')?.trim()?.lowercase()
}

/** Small HTTP abstraction so the resolver and the Sonos client can be tested without a network. */
interface HttpFetcher {
    /** GET a document (HTML, feed, playlist) and return its body, following redirects. */
    fun get(url: String, maxBytes: Int = 3_000_000): HttpResponse

    /** Open [url], follow redirects and return only the headers (body is empty). Works for endless streams. */
    fun probe(url: String): HttpResponse

    fun post(url: String, headers: Map<String, String>, body: String): HttpResponse
}

class UrlConnectionFetcher(
    private val connectTimeoutMs: Int = 6_000,
    private val readTimeoutMs: Int = 10_000,
) : HttpFetcher {

    override fun get(url: String, maxBytes: Int): HttpResponse =
        request(url, "GET", emptyMap(), null, readBody = true, maxBytes = maxBytes)

    override fun probe(url: String): HttpResponse =
        request(url, "GET", mapOf("Range" to "bytes=0-1", "Icy-MetaData" to "1"), null, readBody = false, maxBytes = 0)

    override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse =
        request(url, "POST", headers, body, readBody = true, maxBytes = 2_000_000)

    private fun request(
        startUrl: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        readBody: Boolean,
        maxBytes: Int,
    ): HttpResponse {
        var url = startUrl
        repeat(MAX_REDIRECTS) {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs
                conn.requestMethod = method
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept-Language", "de-DE,de;q=0.9,en;q=0.5")
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                if (body != null) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val responseHeaders = conn.headerFields
                    .filterKeys { it != null }
                    .mapKeys { it.key.lowercase() }
                    .mapValues { it.value.joinToString(", ") }
                if (code in 300..399 && responseHeaders["location"] != null) {
                    url = URI(url).resolve(responseHeaders["location"]!!.trim().replace(" ", "%20")).toString()
                    return@repeat
                }
                val text = if (readBody) {
                    val stream = if (code >= 400) conn.errorStream else conn.inputStream
                    val bytes = stream?.use { readLimited(it, maxBytes) } ?: ByteArray(0)
                    String(bytes, charsetOf(responseHeaders["content-type"]))
                } else ""
                return HttpResponse(code, url, responseHeaders, text)
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Zu viele Weiterleitungen: $startUrl")
    }

    private fun readLimited(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < maxBytes) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun charsetOf(contentType: String?): java.nio.charset.Charset {
        val name = contentType?.substringAfter("charset=", "")?.trim()?.trim('"')
        return runCatching { if (name.isNullOrEmpty()) Charsets.UTF_8 else charset(name) }.getOrDefault(Charsets.UTF_8)
    }

    private companion object {
        const val MAX_REDIRECTS = 8
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    }
}
