package de.sonoss1.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.w3c.dom.Element
import java.net.URI
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Podcast(
    val feedUrl: String,
    val title: String,
    val author: String? = null,
    val artUrl: String? = null,
)

data class Episode(
    /** The feed's guid, or the audio URL when the feed has none. Used to remember what was heard. */
    val id: String,
    val title: String,
    val url: String,
    /** Publication time in epoch milliseconds. */
    val published: Long? = null,
    val durationSec: Int? = null,
    val artUrl: String? = null,
) {
    fun toItem(podcastTitle: String?) =
        PlayableItem(title = title, url = url, kind = StreamKind.EPISODE, subtitle = podcastTitle, artUrl = artUrl)
}

data class PodcastFeed(val podcast: Podcast, val episodes: List<Episode>)

/** Podcast search (Apple's public directory), feed discovery and complete feed parsing. */
class PodcastService(private val http: HttpFetcher) {

    fun search(term: String): List<Podcast> {
        val q = URLEncoder.encode(term.trim(), "UTF-8")
        val body = http.get("https://itunes.apple.com/search?media=podcast&entity=podcast&country=de&limit=25&term=$q").body
        return parseSearch(body)
    }

    fun load(feedUrl: String): PodcastFeed {
        val response = http.get(feedUrl, maxBytes = 30_000_000)
        if (response.code >= 400) throw java.io.IOException("Feed nicht erreichbar (HTTP ${response.code})")
        return parseFeed(response.body, feedUrl)
            ?: throw java.io.IOException("Unter dieser Adresse ist kein Podcast-Feed")
    }

    /** Finds the RSS feed behind a podcast link (Apple Podcasts, a show's web page, or the feed itself). */
    fun findFeedUrl(link: String): String? {
        val url = StreamResolver.extractUrls(link).firstOrNull() ?: return null
        val podcastId = Regex("podcasts\\.apple\\.com/.*/id(\\d+)").find(url)?.groupValues?.get(1)
        if (podcastId != null) {
            val lookup = http.get("https://itunes.apple.com/lookup?id=$podcastId&entity=podcast").body
            return parseSearch(lookup).firstOrNull()?.feedUrl
        }
        val page = http.get(url)
        if (looksLikeFeed(page.body)) return page.finalUrl
        return feedLinkInHtml(page.body, page.finalUrl)
    }

    companion object {
        fun parseSearch(json: String): List<Podcast> {
            val results = runCatching { Json.parseToJsonElement(json).jsonObject["results"] as? JsonArray }
                .getOrNull() ?: return emptyList()
            return results.mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                val feed = o.str("feedUrl") ?: return@mapNotNull null
                Podcast(
                    feedUrl = feed,
                    title = o.str("collectionName") ?: o.str("trackName") ?: feed,
                    author = o.str("artistName"),
                    artUrl = o.str("artworkUrl600") ?: o.str("artworkUrl100"),
                )
            }
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        fun looksLikeFeed(body: String): Boolean {
            val start = body.trimStart().take(1000)
            return start.startsWith("<rss") || start.contains("<rss") || start.contains("<feed")
        }

        fun feedLinkInHtml(html: String, pageUrl: String): String? {
            val link = Regex("<link[^>]+>", RegexOption.IGNORE_CASE).findAll(html).map { it.value }.firstOrNull {
                it.contains("alternate", ignoreCase = true) &&
                    (it.contains("application/rss+xml", ignoreCase = true) || it.contains("application/atom+xml", ignoreCase = true))
            } ?: return null
            val href = Regex("href=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(link)?.groupValues?.get(1) ?: return null
            return runCatching { URI(pageUrl).resolve(Xml.unescape(href).trim()).toString() }.getOrNull()
        }

        /** Parses an RSS 2.0 or Atom podcast feed; episodes keep the feed's order (usually newest first). */
        fun parseFeed(xml: String, feedUrl: String): PodcastFeed? {
            val doc = runCatching { Xml.parse(xml) }.getOrNull() ?: return null
            val root = doc.documentElement
            val channel = (doc.getElementsByTagName("channel").item(0) as? Element) ?: root
            val channelImage = firstChild(channel, "itunes:image")?.getAttribute("href")?.ifEmpty { null }
                ?: firstChild(channel, "image")?.let { childText(it, "url") }
            val podcast = Podcast(
                feedUrl = feedUrl,
                title = childText(channel, "title") ?: feedUrl,
                author = childText(channel, "itunes:author") ?: childText(channel, "author"),
                artUrl = channelImage,
            )
            val episodes = mutableListOf<Episode>()
            for (tag in listOf("item", "entry")) {
                for (item in childElements(channel, tag)) {
                    val audio = firstChild(item, "enclosure")?.getAttribute("url")?.ifEmpty { null }
                        ?: childElements(item, "link").firstOrNull { it.getAttribute("rel") == "enclosure" }?.getAttribute("href")
                        ?: continue
                    episodes += Episode(
                        id = childText(item, "guid") ?: childText(item, "id") ?: audio,
                        title = childText(item, "title") ?: audio.substringAfterLast('/'),
                        url = audio.trim(),
                        published = parseDate(childText(item, "pubDate") ?: childText(item, "published") ?: childText(item, "updated")),
                        durationSec = parseDuration(childText(item, "itunes:duration")),
                        artUrl = firstChild(item, "itunes:image")?.getAttribute("href")?.ifEmpty { null } ?: channelImage,
                    )
                }
            }
            if (episodes.isEmpty() && root.tagName != "rss" && root.tagName != "feed") return null
            // Duplicate guids happen in real feeds; the app keys its lists by id.
            return PodcastFeed(podcast, episodes.distinctBy { it.id })
        }

        private val rfc822Variants = listOf(
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss zzz", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm zzz", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
        )

        fun parseDate(text: String?): Long? {
            val t = text?.trim()?.replace(Regex("\\s+"), " ") ?: return null
            for (f in rfc822Variants) {
                runCatching { return ZonedDateTime.parse(t, f).toInstant().toEpochMilli() }
            }
            return null
        }

        /** "1:02:03", "62:03" or "3723" → seconds. */
        fun parseDuration(text: String?): Int? {
            val parts = text?.trim()?.split(':')?.map { it.trim().toDoubleOrNull() ?: return null } ?: return null
            if (parts.isEmpty() || parts.size > 3) return null
            return parts.fold(0.0) { acc, p -> acc * 60 + p }.toInt()
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
    }
}
