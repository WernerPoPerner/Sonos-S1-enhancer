package de.sonoss1.core

import org.w3c.dom.Element
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI

/** Finds Sonos speakers on the local network (SSDP) and reads the room/group layout from one of them. */
class SonosDiscovery(private val http: HttpFetcher) {

    private val client = SonosClient(http)

    /**
     * Tries the [knownIps] first (fast, no multicast needed) and falls back to an SSDP search.
     * Returns the groups sorted by name; empty if nothing was found.
     */
    fun discover(knownIps: Collection<String>, timeoutMs: Int = 3_000): List<SonosGroup> {
        for (ip in knownIps.distinct()) {
            runCatching { return loadGroups(ip) }
        }
        for (ip in ssdpSearch(timeoutMs)) {
            runCatching { return loadGroups(ip) }
        }
        return emptyList()
    }

    fun loadGroups(ip: String): List<SonosGroup> {
        val probe = SonosPlayer(uuid = "", name = ip, ip = ip)
        val response = client.soap(probe, SonosClient.ZONE_TOPOLOGY, "GetZoneGroupState")
        val groups = parseZoneGroupState(response)
        if (groups.isEmpty()) throw SonosException("Keine Räume von $ip erhalten")
        return groups
    }

    /** Sends M-SEARCH requests and collects the IPs of all answering Sonos players. */
    fun ssdpSearch(timeoutMs: Int): Set<String> {
        val found = linkedSetOf<String>()
        val request = (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 1\r\n" +
                "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n"
            ).toByteArray()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            val targets = listOf("239.255.255.250", "255.255.255.255")
            repeat(2) {
                for (t in targets) runCatching {
                    socket.send(DatagramPacket(request, request.size, InetAddress.getByName(t), 1900))
                }
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(2048)
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                socket.soTimeout = remaining.toInt()
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    break
                }
                val text = String(packet.data, 0, packet.length)
                if (!text.contains("Sonos", ignoreCase = true) && !text.contains("ZonePlayer")) continue
                val location = Regex("(?im)^LOCATION:\\s*(\\S+)").find(text)?.groupValues?.get(1)
                val ip = location?.let { runCatching { URI(it).host }.getOrNull() } ?: packet.address.hostAddress
                found += ip
            }
        }
        return found
    }

    companion object {
        /** Parses a GetZoneGroupState response from S1 (escaped XML) or S2 (inline XML) firmware. */
        fun parseZoneGroupState(soapResponse: String): List<SonosGroup> {
            val inline = Regex("<ZoneGroupState>\\s*(<.*?)</ZoneGroupState>", RegexOption.DOT_MATCHES_ALL)
                .find(soapResponse)?.groupValues?.get(1)
            val state = inline ?: Xml.tag(soapResponse, "ZoneGroupState") ?: return emptyList()
            // S2 firmware returns several sibling elements (ZoneGroups, VanishedDevices); give them one root.
            val doc = Xml.parse("<root>" + state.trim().replace(Regex("^<\\?xml[^>]*\\?>"), "") + "</root>")
            val groupNodes = doc.getElementsByTagName("ZoneGroup")
            val groups = mutableListOf<SonosGroup>()
            for (i in 0 until groupNodes.length) {
                val g = groupNodes.item(i) as Element
                val coordinatorId = g.getAttribute("Coordinator")
                val members = mutableListOf<SonosPlayer>()
                var coordinator: SonosPlayer? = null
                val memberNodes = g.childNodes
                for (j in 0 until memberNodes.length) {
                    val m = memberNodes.item(j) as? Element ?: continue
                    if (m.tagName != "ZoneGroupMember") continue
                    val player = toPlayer(m) ?: continue
                    if (player.uuid == coordinatorId) coordinator = player
                    val hidden = m.getAttribute("Invisible") == "1" || m.getAttribute("IsZoneBridge") == "1"
                    if (!hidden) members += player
                }
                if (coordinator == null || members.isEmpty()) continue
                groups += SonosGroup(g.getAttribute("ID").ifEmpty { coordinatorId }, coordinator, members)
            }
            return groups.sortedBy { it.name.lowercase() }
        }

        private fun toPlayer(m: Element): SonosPlayer? {
            val location = m.getAttribute("Location").takeIf { it.isNotEmpty() } ?: return null
            val url = runCatching { URI(location) }.getOrNull()?.takeIf { it.host != null } ?: return null
            return SonosPlayer(
                uuid = m.getAttribute("UUID"),
                name = m.getAttribute("ZoneName").ifEmpty { url.host },
                ip = url.host,
                port = if (url.port > 0) url.port else 1400,
            )
        }
    }
}
