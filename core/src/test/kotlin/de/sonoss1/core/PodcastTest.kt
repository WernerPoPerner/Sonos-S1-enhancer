package de.sonoss1.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PodcastTest {
    private val feedXml = """<?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel>
          <title>Kakadu</title><itunes:author>Deutschlandfunk Kultur</itunes:author>
          <itunes:image href="https://img/kakadu.jpg"/>
          <item><title>Warum ist der Himmel blau?</title><guid isPermaLink="false">ep-2</guid>
            <pubDate>Tue, 28 Oct 2025 07:05:00 +0100</pubDate><itunes:duration>27:30</itunes:duration>
            <enclosure url="https://download.example.de/kakadu_2.mp3" type="audio/mpeg" length="1"/></item>
          <item><title>Wie schlafen Fische?</title>
            <pubDate>Mon, 6 Jan 2020 07:05:00 GMT</pubDate><itunes:duration>1650</itunes:duration>
            <enclosure url="https://download.example.de/kakadu_1.mp3" type="audio/mpeg" length="1"/></item>
          <item><title>Ohne Audio</title></item>
        </channel></rss>"""

    @Test
    fun parsesCompleteFeed() {
        val feed = PodcastService.parseFeed(feedXml, "https://feed")!!
        assertEquals("Kakadu", feed.podcast.title)
        assertEquals("Deutschlandfunk Kultur", feed.podcast.author)
        assertEquals(2, feed.episodes.size)
        val (newer, older) = feed.episodes
        assertEquals("ep-2", newer.id)
        assertEquals(1650, older.durationSec)
        assertEquals(27 * 60 + 30, newer.durationSec)
        assertEquals("https://download.example.de/kakadu_1.mp3", older.id) // no guid → audio URL
        assertTrue(newer.published!! > older.published!!)
        assertEquals("https://img/kakadu.jpg", older.artUrl)
    }

    @Test
    fun parsesDatesAndDurations() {
        assertEquals(1578294300000, PodcastService.parseDate("Mon, 6 Jan 2020 07:05:00 GMT"))
        assertEquals(1578294300000, PodcastService.parseDate("Mon, 06 Jan 2020 08:05:00 +0100"))
        assertNull(PodcastService.parseDate("gestern"))
        assertEquals(3723, PodcastService.parseDuration("1:02:03"))
        assertNull(PodcastService.parseDuration("lang"))
    }

    @Test
    fun searchUsesAppleDirectory() {
        val http = FakeHttp()
        http.page(
            "https://itunes.apple.com/search?media=podcast&entity=podcast&country=de&limit=25&term=Kakadu+Kinder",
            """{"results":[{"collectionName":"Kakadu","artistName":"Deutschlandfunk Kultur","feedUrl":"https://feed/kakadu.xml","artworkUrl600":"https://img/k.jpg"},
               {"collectionName":"Ohne Feed"}]}""",
            type = "application/json",
        )
        val result = PodcastService(http).search("Kakadu Kinder")
        assertEquals(listOf(Podcast("https://feed/kakadu.xml", "Kakadu", "Deutschlandfunk Kultur", "https://img/k.jpg")), result)
    }

    @Test
    fun findsFeedLinkOnShowPage() {
        val http = FakeHttp()
        http.page(
            "https://www.deutschlandfunkkultur.de/kakadu-100.html",
            """<html><head><link rel="alternate" type="application/rss+xml" title="Podcast" href="/kakadu-104.xml"></head></html>""",
        )
        assertEquals(
            "https://www.deutschlandfunkkultur.de/kakadu-104.xml",
            PodcastService(http).findFeedUrl("Hör mal: https://www.deutschlandfunkkultur.de/kakadu-100.html"),
        )
    }

    @Test
    fun queuePlaybackReplacesQueueAndStartsAtFirstTrack() {
        val http = FakeHttp()
        val p = SonosPlayer("RINCON_A", "Kinderzimmer", "192.168.1.30")
        val episodes = PodcastService.parseFeed(feedXml, "f")!!.episodes.map { it.toItem("Kakadu") }
        SonosClient(http).playQueue(SonosGroup("g", p, listOf(p)), episodes)

        val actions = http.posts.map { Regex("<u:(\\w+) ").find(it.third)!!.groupValues[1] }
        assertEquals(
            listOf("RemoveAllTracksFromQueue", "AddURIToQueue", "AddURIToQueue", "SetAVTransportURI", "SetPlayMode", "Seek", "Play"),
            actions,
        )
        assertTrue(http.posts[1].third.contains("<EnqueuedURI>https://download.example.de/kakadu_2.mp3</EnqueuedURI>"))
        assertTrue(http.posts[3].third.contains("<CurrentURI>x-rincon-queue:RINCON_A#0</CurrentURI>"))
    }
}
