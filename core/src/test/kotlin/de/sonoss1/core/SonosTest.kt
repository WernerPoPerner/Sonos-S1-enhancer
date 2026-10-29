package de.sonoss1.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SonosTest {
    private val s1Response = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
        <u:GetZoneGroupStateResponse xmlns:u="urn:schemas-upnp-org:service:ZoneGroupTopology:1"><ZoneGroupState>&lt;ZoneGroups&gt;
        &lt;ZoneGroup Coordinator="RINCON_A" ID="RINCON_A:1"&gt;
          &lt;ZoneGroupMember UUID="RINCON_A" Location="http://192.168.1.20:1400/xml/device_description.xml" ZoneName="Küche"/&gt;
          &lt;ZoneGroupMember UUID="RINCON_B" Location="http://192.168.1.21:1400/xml/device_description.xml" ZoneName="Bad"/&gt;
        &lt;/ZoneGroup&gt;
        &lt;ZoneGroup Coordinator="RINCON_C" ID="RINCON_C:5"&gt;
          &lt;ZoneGroupMember UUID="RINCON_C" Location="http://192.168.1.22:1400/xml/device_description.xml" ZoneName="Wohnzimmer"&gt;
            &lt;Satellite UUID="RINCON_D" Location="http://192.168.1.23:1400/xml/device_description.xml" ZoneName="Wohnzimmer" Invisible="1"/&gt;
          &lt;/ZoneGroupMember&gt;
        &lt;/ZoneGroup&gt;
        &lt;ZoneGroup Coordinator="RINCON_E" ID="RINCON_E:2"&gt;
          &lt;ZoneGroupMember UUID="RINCON_E" Location="http://192.168.1.24:1400/xml/device_description.xml" ZoneName="BOOST" IsZoneBridge="1"/&gt;
        &lt;/ZoneGroup&gt;
        &lt;/ZoneGroups&gt;</ZoneGroupState></u:GetZoneGroupStateResponse></s:Body></s:Envelope>"""

    @Test
    fun parsesS1ZoneGroupState() {
        val groups = SonosDiscovery.parseZoneGroupState(s1Response)
        assertEquals(listOf("Küche + Bad", "Wohnzimmer"), groups.map { it.name })
        assertEquals("192.168.1.20", groups[0].coordinator.ip)
        assertEquals(1, groups[1].members.size)
    }

    @Test
    fun parsesInlineZoneGroupState() {
        val inline = """<s:Envelope><s:Body><u:R><ZoneGroupState><ZoneGroups><ZoneGroup Coordinator="RINCON_X" ID="g">
            <ZoneGroupMember UUID="RINCON_X" Location="http://10.0.0.5:1400/xml/device_description.xml" ZoneName="Büro"/>
            </ZoneGroup></ZoneGroups><VanishedDevices/></ZoneGroupState></u:R></s:Body></s:Envelope>"""
        assertEquals("Büro", SonosDiscovery.parseZoneGroupState(inline).single().name)
    }

    @Test
    fun transportUris() {
        val live = PlayableItem("R", "http://radio.example.org/stream.mp3", StreamKind.LIVE)
        assertEquals("x-rincon-mp3radio://radio.example.org/stream.mp3", SonosClient.transportUri(live, false))
        val https = live.copy(url = "https://radio.example.org/stream.mp3")
        assertEquals("https://radio.example.org/stream.mp3", SonosClient.transportUri(https, false))
        assertEquals("x-rincon-mp3radio://radio.example.org/stream.mp3", SonosClient.transportUri(https, true))
        val ep = PlayableItem("E", "https://cdn/e.mp3", StreamKind.EPISODE)
        assertEquals("https://cdn/e.mp3", SonosClient.transportUri(ep, false))
        assertEquals("http://cdn/e.mp3", SonosClient.transportUri(ep, true))
    }

    @Test
    fun playSendsUriAndPlayToCoordinator() {
        val http = FakeHttp()
        val coordinator = SonosPlayer("RINCON_A", "Küche", "192.168.1.20")
        val group = SonosGroup("g", coordinator, listOf(coordinator, SonosPlayer("RINCON_B", "Bad", "192.168.1.21")))
        SonosClient(http).play(group, PlayableItem("Nachrichten & mehr", "https://cdn/a.mp3?x=1&y=2", StreamKind.EPISODE))

        assertEquals(2, http.posts.size)
        val (url, headers, body) = http.posts[0]
        assertEquals("http://192.168.1.20:1400/MediaRenderer/AVTransport/Control", url)
        assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"", headers["SOAPACTION"])
        assertTrue(body.contains("<CurrentURI>https://cdn/a.mp3?x=1&amp;y=2</CurrentURI>"), body)
        // DIDL is XML-escaped inside the SOAP body, so its own entities are escaped twice.
        assertTrue(body.contains("&lt;dc:title&gt;Nachrichten &amp;amp; mehr&lt;/dc:title&gt;"), body)
        assertTrue(http.posts[1].third.contains("<u:Play "))
    }

    @Test
    fun nowPlayingReadsRadioMetadata() {
        val http = FakeHttp()
        http.postResponder = { url, body ->
            val response = when {
                body.contains("GetTransportInfo") -> "<CurrentTransportState>PLAYING</CurrentTransportState>"
                body.contains("GetMediaInfo") -> "<CurrentURIMetaData>" + Xml.escape(
                    "<DIDL-Lite><item><dc:title>Deutschlandfunk</dc:title></item></DIDL-Lite>"
                ) + "</CurrentURIMetaData>"
                else -> "<TrackMetaData>" + Xml.escape(
                    "<DIDL-Lite><item><dc:title>x-sonosapi-stream:abc</dc:title><r:streamContent>Nachrichten</r:streamContent></item></DIDL-Lite>"
                ) + "</TrackMetaData>"
            }
            HttpResponse(200, url, emptyMap(), response)
        }
        val p = SonosPlayer("A", "Küche", "1.2.3.4")
        val np = SonosClient(http).nowPlaying(SonosGroup("g", p, listOf(p)))
        assertEquals(NowPlaying("PLAYING", "Deutschlandfunk", "Nachrichten"), np)
    }
}
