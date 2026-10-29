package de.sonoss1.core

data class Station(
    val name: String,
    val streamUrl: String,
    /** Websites whose links should fall back to this station's live programme. */
    val hosts: List<String> = emptyList(),
    /** Names that identify the station in shared text without a link. */
    val aliases: List<String> = emptyList(),
) {
    fun toItem() = PlayableItem(title = name, url = streamUrl, kind = StreamKind.LIVE, subtitle = "Live")
}

object Stations {
    val all = listOf(
        Station(
            "Deutschlandfunk", "https://st01.sslstream.dlf.de/dlf/01/128/mp3/stream.mp3",
            hosts = listOf("deutschlandfunk.de", "dlf.de", "deutschlandradio.de"),
            aliases = listOf("Deutschlandfunk", "DLF"),
        ),
        Station(
            "Deutschlandfunk Kultur", "https://st02.sslstream.dlf.de/dlf/02/128/mp3/stream.mp3",
            hosts = listOf("deutschlandfunkkultur.de"),
            aliases = listOf("Deutschlandfunk Kultur", "Dlf Kultur"),
        ),
        Station(
            "Deutschlandfunk Nova", "https://st03.sslstream.dlf.de/dlf/03/128/mp3/stream.mp3",
            hosts = listOf("deutschlandfunknova.de"),
            aliases = listOf("Deutschlandfunk Nova", "Dlf Nova"),
        ),
        Station("Dlf Dokumente & Debatten", "https://st04.sslstream.dlf.de/dlf/04/128/mp3/stream.mp3"),
    )

    fun forHost(host: String): Station? {
        val h = host.lowercase().removePrefix("www.")
        return all.firstOrNull { s -> s.hosts.any { h == it || h.endsWith(".$it") } }
    }

    /** Longest alias match wins, so "Deutschlandfunk Kultur" beats "Deutschlandfunk". */
    fun forText(text: String): Station? =
        all.flatMap { s -> s.aliases.map { it to s } }
            .filter { (alias, _) -> Regex("(?i)(^|\\W)${Regex.escape(alias)}(\\W|$)").containsMatchIn(text) }
            .maxByOrNull { it.first.length }?.second
}
