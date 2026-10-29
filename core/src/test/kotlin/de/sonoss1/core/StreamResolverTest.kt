package de.sonoss1.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StreamResolverTest {
    private val http = FakeHttp()
    private val resolver = StreamResolver(http)

    private fun found(text: String, subject: String? = null): List<PlayableItem> {
        val r = resolver.resolve(text, subject)
        assertIs<ResolveResult.Found>(r, r.toString())
        return r.items
    }

    @Test
    fun extractsUrlsWithoutTrailingPunctuation() {
        assertEquals(
            listOf("https://www.deutschlandfunk.de/a-100.html"),
            StreamResolver.extractUrls("Hör mal (https://www.deutschlandfunk.de/a-100.html)."),
        )
    }

    @Test
    fun deutschlandfunkArticleWithAudio() {
        http.page(
            "https://www.deutschlandfunk.de/interview-100.html",
            """<html><head><meta property="og:title" content="Interview &amp; Analyse">
               <meta property="og:image" content="/bild.jpg"></head><body>
               <script>var x = {"audio":"https:\/\/download.deutschlandfunk.de\/file\/dradio\/2026\/10\/29\/interview_dlf_20261029.mp3"}</script>
               </body></html>""",
        )
        val items = found("Interview im Deutschlandfunk https://www.deutschlandfunk.de/interview-100.html")
        assertEquals("https://download.deutschlandfunk.de/file/dradio/2026/10/29/interview_dlf_20261029.mp3", items[0].url)
        assertEquals("Interview & Analyse", items[0].title)
        assertEquals(StreamKind.EPISODE, items[0].kind)
        assertEquals("https://www.deutschlandfunk.de/bild.jpg", items[0].artUrl)
        // Live programme is offered as an alternative.
        assertEquals(Stations.all[0].streamUrl, items.last().url)
        assertEquals(StreamKind.LIVE, items.last().kind)
    }

    @Test
    fun stationPageWithoutAudioFallsBackToLive() {
        http.page("https://www.deutschlandfunkkultur.de/programm", "<html><title>Programm</title></html>")
        val items = found("https://www.deutschlandfunkkultur.de/programm")
        assertEquals(1, items.size)
        assertEquals("https://st02.sslstream.dlf.de/dlf/02/128/mp3/stream.mp3", items[0].url)
    }

    @Test
    fun ogAudioWithoutExtensionIsUsed() {
        http.page(
            "https://example.org/folge",
            """<meta content="https://cdn.example.org/play/12345" property="og:audio"><title>Folge 5</title>""",
        )
        val items = found("https://example.org/folge")
        assertEquals("https://cdn.example.org/play/12345", items.single().url)
        assertEquals("Folge 5", items.single().title)
    }

    @Test
    fun directStreamIsDetectedAsLive() {
        http.page("http://radio.example.org/live", "", type = "audio/mpeg", headers = mapOf("icy-name" to "Radio X"))
        val item = found("http://radio.example.org/live").single()
        assertEquals(StreamKind.LIVE, item.kind)
        assertEquals("Radio X", item.title)
    }

    @Test
    fun directFileIsEpisode() {
        http.page("https://cdn.example.org/ep1.mp3", "", type = "audio/mpeg", headers = mapOf("content-range" to "bytes 0-1/5000"))
        val item = found("Neue Folge https://cdn.example.org/ep1.mp3", subject = "Folge 1").single()
        assertEquals(StreamKind.EPISODE, item.kind)
        assertEquals("Folge 1", item.title)
    }

    @Test
    fun plsPlaylist() {
        http.page("https://radio.example.org/listen.pls", "[playlist]\nFile1=http://s1.example.org:8000/stream\nTitle1=X\n", type = "audio/x-scpls")
        http.page("http://s1.example.org:8000/stream", "", type = "audio/aacp")
        val item = found("https://radio.example.org/listen.pls").single()
        assertEquals("http://s1.example.org:8000/stream", item.url)
        assertEquals(StreamKind.LIVE, item.kind)
    }

    @Test
    fun applePodcastEpisode() {
        http.page(
            "https://itunes.apple.com/lookup?id=123&entity=podcastEpisode&limit=200&country=de",
            """{"resultCount":3,"results":[
              {"wrapperType":"track","kind":"podcast","collectionName":"Der Podcast","artworkUrl600":"https://img/show.jpg","feedUrl":"https://feed"},
              {"wrapperType":"podcastEpisode","trackId":111,"trackName":"Neu","episodeUrl":"https://cdn/neu.mp3","collectionName":"Der Podcast"},
              {"wrapperType":"podcastEpisode","trackId":222,"trackName":"Alt","episodeUrl":"https://cdn/alt.mp3","collectionName":"Der Podcast"}]}""",
            type = "application/json",
        )
        val item = found("https://podcasts.apple.com/de/podcast/der-podcast/id123?i=222").single()
        assertEquals("Alt", item.title)
        assertEquals("https://cdn/alt.mp3", item.url)
        assertEquals("https://img/show.jpg", item.artUrl)

        assertEquals(2, found("https://podcasts.apple.com/de/podcast/der-podcast/id123").size)
    }

    @Test
    fun rssFeed() {
        http.page(
            "https://example.org/feed.xml",
            """<?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel>
              <title>Show</title><itunes:image href="https://img/c.jpg"/>
              <item><title>Folge 2</title><enclosure url="https://cdn/2.mp3" type="audio/mpeg" length="1"/></item>
              <item><title>Folge 1</title><enclosure url="https://cdn/1.mp3" type="audio/mpeg" length="1"/></item>
            </channel></rss>""",
            type = "application/rss+xml",
        )
        val items = found("https://example.org/feed.xml")
        assertEquals(listOf("Folge 2", "Folge 1"), items.map { it.title })
        assertEquals("Show", items[0].subtitle)
        assertEquals("https://img/c.jpg", items[0].artUrl)
    }

    @Test
    fun spotifyIsRejectedWithExplanation() {
        val r = resolver.resolve("https://open.spotify.com/track/abc")
        assertIs<ResolveResult.Failed>(r)
        assertTrue(r.message.contains("Spotify"))
    }

    @Test
    fun stationNameWithoutLink() {
        assertEquals("Deutschlandfunk Kultur", found("Ich höre gerade Deutschlandfunk Kultur").single().title)
        assertEquals("Deutschlandfunk", found("Ich höre gerade Deutschlandfunk").single().title)
    }
}
