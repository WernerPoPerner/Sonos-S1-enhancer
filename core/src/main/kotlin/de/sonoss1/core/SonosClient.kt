package de.sonoss1.core

import java.io.IOException

class SonosException(message: String) : IOException(message)

/** UPnP/SOAP control of a Sonos (S1 or S2) group. All calls block; run them off the main thread. */
class SonosClient(private val http: HttpFetcher) {

    /** Sends [item] to the group's coordinator and starts playback. */
    fun play(group: SonosGroup, item: PlayableItem, forceHttp: Boolean = false) {
        val player = group.coordinator
        soap(
            player, AV_TRANSPORT, "SetAVTransportURI",
            "InstanceID" to "0",
            "CurrentURI" to transportUri(item, forceHttp),
            "CurrentURIMetaData" to didl(item),
        )
        resume(group)
    }

    fun resume(group: SonosGroup) {
        soap(group.coordinator, AV_TRANSPORT, "Play", "InstanceID" to "0", "Speed" to "1")
    }

    fun pause(group: SonosGroup) {
        // Live radio cannot always be paused; stop is the fallback Sonos itself uses.
        try {
            soap(group.coordinator, AV_TRANSPORT, "Pause", "InstanceID" to "0")
        } catch (e: SonosException) {
            stop(group)
        }
    }

    fun stop(group: SonosGroup) {
        soap(group.coordinator, AV_TRANSPORT, "Stop", "InstanceID" to "0")
    }

    fun nowPlaying(group: SonosGroup): NowPlaying {
        val p = group.coordinator
        val state = Xml.tag(soap(p, AV_TRANSPORT, "GetTransportInfo", "InstanceID" to "0"), "CurrentTransportState") ?: "UNKNOWN"
        val media = soap(p, AV_TRANSPORT, "GetMediaInfo", "InstanceID" to "0")
        val position = soap(p, AV_TRANSPORT, "GetPositionInfo", "InstanceID" to "0")
        val mediaMeta = Xml.tag(media, "CurrentURIMetaData").orEmpty()
        val trackMeta = Xml.tag(position, "TrackMetaData").orEmpty()
        val stationTitle = Xml.tag(mediaMeta, "title")
        val trackTitle = Xml.tag(trackMeta, "title")?.takeUnless { it.startsWith("x-") || it.startsWith("http") }
        val streamContent = Xml.tag(trackMeta, "streamContent")?.takeIf { it.isNotBlank() }
        val creator = Xml.tag(trackMeta, "creator")?.takeIf { it.isNotBlank() }
        val title = stationTitle?.takeIf { it.isNotBlank() } ?: trackTitle
        val detail = streamContent ?: creator ?: trackTitle?.takeIf { it != title }
        return NowPlaying(state, title, detail)
    }

    fun volume(group: SonosGroup): Int {
        val p = group.coordinator
        val response = try {
            soap(p, GROUP_RENDERING, "GetGroupVolume", "InstanceID" to "0")
        } catch (e: SonosException) {
            soap(p, RENDERING, "GetVolume", "InstanceID" to "0", "Channel" to "Master")
        }
        return Xml.tag(response, "CurrentVolume")?.toIntOrNull() ?: 0
    }

    fun setVolume(group: SonosGroup, volume: Int) {
        val v = volume.coerceIn(0, 100).toString()
        val p = group.coordinator
        try {
            soap(p, GROUP_RENDERING, "SnapshotGroupVolume", "InstanceID" to "0")
            soap(p, GROUP_RENDERING, "SetGroupVolume", "InstanceID" to "0", "DesiredVolume" to v)
        } catch (e: SonosException) {
            soap(p, RENDERING, "SetVolume", "InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to v)
        }
    }

    internal fun soap(player: SonosPlayer, service: Service, action: String, vararg args: Pair<String, String>): String {
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
            append("<u:$action xmlns:u=\"${service.type}\">")
            for ((k, v) in args) append("<$k>").append(Xml.escape(v)).append("</$k>")
            append("</u:$action></s:Body></s:Envelope>")
        }
        val response = http.post(
            player.baseUrl + service.path,
            mapOf(
                "Content-Type" to "text/xml; charset=\"utf-8\"",
                "SOAPACTION" to "\"${service.type}#$action\"",
            ),
            body,
        )
        if (response.code != 200) {
            val code = Xml.tag(response.body, "errorCode")
            throw SonosException(
                "${player.name}: $action fehlgeschlagen (HTTP ${response.code}${code?.let { ", UPnP-Fehler $it" } ?: ""})"
            )
        }
        return response.body
    }

    internal class Service(val path: String, val type: String)

    companion object {
        internal val AV_TRANSPORT = Service("/MediaRenderer/AVTransport/Control", "urn:schemas-upnp-org:service:AVTransport:1")
        internal val RENDERING = Service("/MediaRenderer/RenderingControl/Control", "urn:schemas-upnp-org:service:RenderingControl:1")
        internal val GROUP_RENDERING =
            Service("/MediaRenderer/GroupRenderingControl/Control", "urn:schemas-upnp-org:service:GroupRenderingControl:1")
        internal val ZONE_TOPOLOGY = Service("/ZoneGroupTopology/Control", "urn:schemas-upnp-org:service:ZoneGroupTopology:1")

        /**
         * Sonos plays plain http(s) URLs as tracks. Endless http radio streams work best with the
         * `x-rincon-mp3radio` scheme (Sonos then treats them as a radio station and reconnects on drops).
         */
        fun transportUri(item: PlayableItem, forceHttp: Boolean): String {
            var url = item.url
            if (forceHttp && url.startsWith("https://", ignoreCase = true)) url = "http://" + url.substring(8)
            val isHls = url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
            if (item.kind == StreamKind.LIVE && !isHls && url.startsWith("http://", ignoreCase = true)) {
                return "x-rincon-mp3radio://" + url.substring(7)
            }
            return url
        }

        fun didl(item: PlayableItem): String {
            val upnpClass = when (item.kind) {
                StreamKind.LIVE -> "object.item.audioItem.audioBroadcast"
                StreamKind.EPISODE -> "object.item.audioItem.musicTrack"
            }
            return buildString {
                append("<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
                append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" ")
                append("xmlns:r=\"urn:schemas-rinconnetworks-com:metadata-1-0/\" ")
                append("xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">")
                append("<item id=\"-1\" parentID=\"-1\" restricted=\"true\">")
                append("<dc:title>").append(Xml.escape(item.title)).append("</dc:title>")
                item.subtitle?.let { append("<dc:creator>").append(Xml.escape(it)).append("</dc:creator>") }
                item.artUrl?.let { append("<upnp:albumArtURI>").append(Xml.escape(it)).append("</upnp:albumArtURI>") }
                append("<upnp:class>$upnpClass</upnp:class>")
                append("<desc id=\"cdudn\" nameSpace=\"urn:schemas-rinconnetworks-com:metadata-1-0/\">RINCON_AssociatedZPUDN</desc>")
                append("</item></DIDL-Lite>")
            }
        }
    }
}
