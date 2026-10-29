package de.sonoss1.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.w3c.dom.Element
import java.net.URI
import java.net.URLDecoder

/**
 * Turns whatever another app shares (a web link, a podcast link, a stream URL, plain text)
 * into one or more playable audio URLs.
 */
class StreamResolver(private val http: HttpFetcher) {

    fun resolve(sharedText: String, subject: String? = null): ResolveResult {
        val urls = extractUrls(sharedText)
        val fallbackTitle = subject?.trim()?.takeIf { it.isNotEmpty() }
            ?: urls.fold(sharedText) { acc, u -> acc.replace(u, " ") }.trim().lines().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        if (urls.isEmpty()) {
            Stations.forText(sharedText)?.let { return ResolveResult.Found(listOf(it.toItem())) }
            return ResolveResult.Failed("Im geteilten Inhalt wurde kein Link gefunden.")
        }

        var lastError: String? = null
        for (url in urls) {
            val drm = drmMessage(url)
            if (drm != null) {
                lastError = drm
                continue
            }
            try {
                val items = resolveUrl(url, fallbackTitle, depth = 0)
                if (items.isNotEmpty()) return ResolveResult.Found(items.distinctBy { it.url }.take(MAX_ITEMS))
            } catch (e: Exception) {
                lastError = "Link konnte nicht geladen werden: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        Stations.forText(sharedText)?.let { return ResolveResult.Found(listOf(it.toItem())) }
        return ResolveResult.Failed(lastError ?: "Auf der geteilten Seite wurde keine Audiodatei gefunden.")
    }

    private fun resolveUrl(url: String, title: String?, depth: Int): List<PlayableItem> {
        if (depth > 3) return emptyList()
        val host = hostOf(url)
        if (host.endsWith("podcasts.apple.com") || host.endsWith("itunes.apple.com")) {
            applePodcast(url)?.let { return it }
        }

        val ext = extensionOf(url)
        if (ext in PLAYLIST_EXTENSIONS && ext != "m3u8") {
            return resolvePlaylist(http.get(url, 200_000).body, url, title, depth)
        }

        val head = runCatching { http.probe(url) }.getOrNull()
        val type = head?.contentType.orEmpty()
        val finalUrl = head?.finalUrl ?: url

        if (head == null || head.code >= 400) {
            if (ext in AUDIO_EXTENSIONS) return listOf(audioItem(url, title, null))
            if (head == null) throw java.io.IOException("keine Verbindung zu $host")
        }
        if (type.startsWith("audio/") || type == "application/ogg" || type in HLS_TYPES || ext in AUDIO_EXTENSIONS) {
            if (type in PLAYLIST_TYPES || (ext in PLAYLIST_EXTENSIONS && ext != "m3u8")) {
                val body = http.get(finalUrl, 200_000).body
                if (!body.contains("#EXT-X-")) return resolvePlaylist(body, finalUrl, title, depth)
            }
            return listOf(audioItem(finalUrl, title, head))
        }

        val page = http.get(finalUrl)
        val body = page.body
        val pageType = page.contentType.orEmpty()
        val start = body.trimStart().take(500)
        if (pageType.contains("xml") || pageType.contains("rss") ||
            (start.startsWith("<?xml") && !start.contains("<html", ignoreCase = true)) ||
            start.startsWith("<rss")
        ) {
            val episodes = parseFeed(body)
            if (episodes.isNotEmpty()) return episodes
        }
        return parseHtml(body, page.finalUrl, title).flatMap { item ->
            val itemExt = extensionOf(item.url)
            if (itemExt == "m3u" || itemExt == "pls") {
                runCatching { resolvePlaylist(http.get(item.url, 200_000).body, item.url, item.title, depth + 1) }
                    .getOrDefault(emptyList())
            } else listOf(item)
        }
    }

    private fun audioItem(url: String, title: String?, head: HttpResponse?): PlayableItem {
        val kind = head?.let { kindFromHeaders(it) }
            ?: if (url.contains("stream", ignoreCase = true) || url.contains("live", ignoreCase = true)) StreamKind.LIVE else StreamKind.EPISODE
        val icyName = head?.headers?.get("icy-name")?.takeIf { it.isNotBlank() }
        val name = icyName ?: title ?: Stations.all.firstOrNull { it.streamUrl == url }?.name ?: fileTitle(url)
        return PlayableItem(name, url, kind, subtitle = if (kind == StreamKind.LIVE) "Live" else null)
    }

    private fun kindFromHeaders(head: HttpResponse): StreamKind {
        val h = head.headers
        if (h.keys.any { it.startsWith("icy-") || it == "ice-audio-info" }) return StreamKind.LIVE
        if (head.contentType in HLS_TYPES) return StreamKind.LIVE
        if (head.code == 206 || h["content-range"] != null) return StreamKind.EPISODE
        val length = h["content-length"]?.toLongOrNull()
        return if (length != null && length > 0) StreamKind.EPISODE else StreamKind.LIVE
    }

    private fun resolvePlaylist(body: String, baseUrl: String, title: String?, depth: Int): List<PlayableItem> {
        val entries = parsePlaylist(body, baseUrl)
        for (entry in entries) {
            val items = runCatching { resolveUrl(entry, title, depth + 1) }.getOrDefault(emptyList())
            if (items.isNotEmpty()) return items.map { it.copy(kind = StreamKind.LIVE) }
        }
        return emptyList()
    }

    // ---------------------------------------------------------------- Apple Podcasts

    private fun applePodcast(url: String): List<PlayableItem>? {
        val podcastId = Regex("/id(\\d+)").find(url)?.groupValues?.get(1) ?: return null
        val episodeId = Regex("[?&]i=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
        val lookup = http.get("https://itunes.apple.com/lookup?id=$podcastId&entity=podcastEpisode&limit=200&country=de").body
        val results = Json.parseToJsonElement(lookup).jsonObject["results"] as? JsonArray ?: return null
        val objects = results.map { it.jsonObject }
        val show = objects.firstOrNull { it.str("wrapperType") != "podcastEpisode" }
        val episodes = objects.filter { it.str("wrapperType") == "podcastEpisode" && it.str("episodeUrl") != null }
        val chosen = episodeId?.let { id -> episodes.filter { it["trackId"]?.jsonPrimitive?.longOrNull == id } }
        val list = (chosen?.takeIf { it.isNotEmpty() } ?: episodes.take(MAX_ITEMS)).map {
            PlayableItem(
                title = it.str("trackName") ?: "Episode",
                url = it.str("episodeUrl")!!,
                kind = StreamKind.EPISODE,
                subtitle = it.str("collectionName"),
                artUrl = it.str("artworkUrl600") ?: show?.str("artworkUrl600"),
            )
        }
        if (list.isNotEmpty()) return list
        return show?.str("feedUrl")?.let { feed -> parseFeed(http.get(feed).body).takeIf { it.isNotEmpty() } }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        private const val MAX_ITEMS = 10
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "mpga", "m3u8")
        private val PLAYLIST_EXTENSIONS = setOf("m3u", "m3u8", "pls")
        private val HLS_TYPES = setOf("application/vnd.apple.mpegurl", "application/x-mpegurl")
        private val PLAYLIST_TYPES = setOf("audio/x-mpegurl", "audio/mpegurl", "audio/x-scpls", "audio/scpls")

        private val DRM_SERVICES = mapOf(
            "spotify" to "Spotify", "music.apple.com" to "Apple Music", "music.youtube.com" to "YouTube Music",
            "youtube.com" to "YouTube", "youtu.be" to "YouTube", "music.amazon" to "Amazon Music",
            "deezer" to "Deezer", "tidal.com" to "Tidal",
        )

        fun drmMessage(url: String): String? {
            val host = hostOf(url)
            val service = DRM_SERVICES.entries.firstOrNull { host.contains(it.key) }?.value ?: return null
            return "$service-Inhalte sind kopiergeschützt und können nicht direkt an Sonos geschickt werden. " +
                "Füge $service stattdessen in der Sonos-S1-App als Musikdienst hinzu."
        }

        fun extractUrls(text: String): List<String> =
            Regex("https?://[^\\s<>\"'«»]+").findAll(text)
                .map { it.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '“', '”', '\'') }
                .distinct().toList()

        internal fun hostOf(url: String): String = runCatching { URI(url).host?.lowercase() }.getOrNull().orEmpty()

        internal fun extensionOf(url: String): String =
            url.substringBefore('#').substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()

        private fun fileTitle(url: String): String {
            val name = url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
            return runCatching { URLDecoder.decode(name, "UTF-8") }.getOrDefault(name)
                .replace('_', ' ').replace('-', ' ').ifBlank { hostOf(url) }
        }

        fun parsePlaylist(body: String, baseUrl: String): List<String> =
            body.lines().map { it.trim() }.mapNotNull { line ->
                when {
                    line.isEmpty() || line.startsWith("#") || line.startsWith("[") -> null
                    Regex("(?i)^File\\d+=").containsMatchIn(line) -> line.substringAfter('=').trim()
                    line.contains('=') && !line.contains("://") -> null
                    else -> line
                }
            }.mapNotNull { absolutize(it, baseUrl) }.filter { it.startsWith("http") }

        private fun absolutize(link: String, baseUrl: String): String? =
            runCatching { URI(baseUrl).resolve(link.trim().replace(" ", "%20")).toString() }.getOrNull()

        /** RSS 2.0 (podcast) and Atom feeds; newest entries first as published. */
        fun parseFeed(xml: String): List<PlayableItem> {
            val doc = runCatching { Xml.parse(xml) }.getOrNull() ?: return emptyList()
            val channelImage = doc.getElementsByTagName("itunes:image").let {
                if (it.length > 0) (it.item(0) as Element).getAttribute("href").ifEmpty { null } else null
            }
            val showTitle = doc.getElementsByTagName("channel").let {
                if (it.length > 0) childText(it.item(0) as Element, "title") else null
            }
            val result = mutableListOf<PlayableItem>()
            for (tag in listOf("item", "entry")) {
                val nodes = doc.getElementsByTagName(tag)
                for (i in 0 until nodes.length) {
                    val item = nodes.item(i) as Element
                    val enclosure = firstChild(item, "enclosure")?.getAttribute("url")?.ifEmpty { null }
                        ?: childElements(item, "link").firstOrNull { it.getAttribute("rel") == "enclosure" }?.getAttribute("href")
                        ?: continue
                    val image = firstChild(item, "itunes:image")?.getAttribute("href")?.ifEmpty { null } ?: channelImage
                    result += PlayableItem(
                        title = childText(item, "title") ?: fileTitle(enclosure),
                        url = enclosure,
                        kind = StreamKind.EPISODE,
                        subtitle = showTitle,
                        artUrl = image,
                    )
                    if (result.size >= MAX_ITEMS) return result
                }
            }
            return result
        }

        private fun childElements(parent: Element, name: String): List<Element> {
            val out = mutableListOf<Element>()
            val nodes = parent.childNodes
            for (i in 0 until nodes.length) {
                val n = nodes.item(i)
                if (n is Element && n.tagName == name) out += n
            }
            return out
        }

        private fun firstChild(parent: Element, name: String) = childElements(parent, name).firstOrNull()
        private fun childText(parent: Element, name: String) = firstChild(parent, name)?.textContent?.trim()?.ifEmpty { null }

        /** Finds audio on a web page: OpenGraph/Twitter tags, JSON-LD, <audio> tags and any linked audio file. */
        fun parseHtml(html: String, pageUrl: String, fallbackTitle: String?): List<PlayableItem> {
            fun meta(vararg names: String): String? {
                for (name in names) {
                    val n = Regex.escape(name)
                    val patterns = listOf(
                        "<meta[^>]+(?:property|name)=[\"']$n[\"'][^>]*content=[\"']([^\"']+)[\"']",
                        "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]*(?:property|name)=[\"']$n[\"']",
                    )
                    for (p in patterns) Regex(p, RegexOption.IGNORE_CASE).find(html)?.let { return Xml.unescape(it.groupValues[1]) }
                }
                return null
            }

            val pageTitle = meta("og:title", "twitter:title")
                ?: Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(html)?.groupValues?.get(1)?.let { Xml.unescape(it).trim() }
                ?: fallbackTitle
            val image = meta("og:image", "twitter:image")?.let { absolutize(it, pageUrl) }

            val candidates = linkedSetOf<String>()
            val ogAudio = meta("og:audio:secure_url", "og:audio", "og:audio:url", "twitter:player:stream")
                ?.let { absolutize(it, pageUrl) }
            ogAudio?.let { candidates += it }

            val unescaped = html.replace("\\/", "/").replace("\\u002F", "/").replace("\\u002f", "/")
            Regex("\"contentUrl\"\\s*:\\s*\"([^\"]+)\"").findAll(unescaped).forEach { candidates += it.groupValues[1] }
            Regex("<(?:audio|source)[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                .findAll(html).forEach { candidates += it.groupValues[1] }
            Regex("(?:data-[\\w-]*(?:audio|src|url|file)[\\w-]*)=[\"']([^\"']+\\.(?:mp3|m4a|aac|ogg|opus|m3u8?)[^\"']*)[\"']", RegexOption.IGNORE_CASE)
                .findAll(html).forEach { candidates += it.groupValues[1] }
            Regex("https?://[^\\s\"'<>()\\\\]+?\\.(?:mp3|m4a|aac|ogg|opus|m3u8?)(?:\\?[^\\s\"'<>()\\\\]*)?", RegexOption.IGNORE_CASE)
                .findAll(unescaped).forEach { candidates += it.value }

            val urls = candidates
                .map { Xml.unescape(it) }
                .mapNotNull { absolutize(it, pageUrl) }
                .filter { it.startsWith("http") && (it == ogAudio || extensionOf(it) in AUDIO_EXTENSIONS || extensionOf(it) in PLAYLIST_EXTENSIONS) }
                .distinct()

            val items = urls.mapIndexed { index, url ->
                val station = Stations.all.firstOrNull { it.streamUrl == url }
                val live = station != null || extensionOf(url) == "m3u8" || extensionOf(url) in setOf("m3u", "pls") ||
                    Regex("(?i)/(live|stream)(/|\\.|$)").containsMatchIn(url)
                PlayableItem(
                    title = station?.name ?: if (index == 0 && pageTitle != null) pageTitle else fileTitle(url),
                    url = url,
                    kind = if (live) StreamKind.LIVE else StreamKind.EPISODE,
                    subtitle = if (live) "Live" else hostOf(pageUrl).removePrefix("www."),
                    artUrl = image,
                )
            }.toMutableList()

            Stations.forHost(hostOf(pageUrl))?.let { station ->
                if (items.none { it.url == station.streamUrl }) items += station.toItem().copy(title = "${station.name} (Live-Programm)")
            }
            return items.take(MAX_ITEMS)
        }
    }
}
