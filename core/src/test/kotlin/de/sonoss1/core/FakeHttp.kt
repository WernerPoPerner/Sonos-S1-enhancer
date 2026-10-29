package de.sonoss1.core

class FakeHttp : HttpFetcher {
    val pages = mutableMapOf<String, HttpResponse>()
    val posts = mutableListOf<Triple<String, Map<String, String>, String>>()
    var postResponder: (String, String) -> HttpResponse = { url, _ -> HttpResponse(200, url, emptyMap(), "<ok/>") }

    fun page(url: String, body: String, type: String = "text/html; charset=utf-8", headers: Map<String, String> = emptyMap()) {
        pages[url] = HttpResponse(200, url, mapOf("content-type" to type) + headers, body)
    }

    override fun get(url: String, maxBytes: Int) = pages[url] ?: HttpResponse(404, url, emptyMap(), "")
    override fun probe(url: String) = (pages[url] ?: HttpResponse(404, url, emptyMap(), "")).copy(body = "")
    override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
        posts += Triple(url, headers, body)
        return postResponder(url, body)
    }
}
