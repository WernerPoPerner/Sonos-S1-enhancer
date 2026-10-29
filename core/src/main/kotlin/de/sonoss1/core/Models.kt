package de.sonoss1.core

enum class StreamKind { LIVE, EPISODE }

/** Something the speaker can play: a live radio stream or a single audio file (e.g. a podcast episode). */
data class PlayableItem(
    val title: String,
    val url: String,
    val kind: StreamKind,
    val subtitle: String? = null,
    val artUrl: String? = null,
)

sealed class ResolveResult {
    data class Found(val items: List<PlayableItem>) : ResolveResult()
    data class Failed(val message: String) : ResolveResult()
}

data class SonosPlayer(val uuid: String, val name: String, val ip: String, val port: Int = 1400) {
    val baseUrl: String get() = "http://$ip:$port"
}

data class SonosGroup(val id: String, val coordinator: SonosPlayer, val members: List<SonosPlayer>) {
    val name: String
        get() = (listOf(coordinator) + members.filter { it.uuid != coordinator.uuid })
            .map { it.name }.distinct().joinToString(" + ")
}

data class NowPlaying(val state: String, val title: String?, val detail: String?) {
    val isPlaying: Boolean get() = state == "PLAYING" || state == "TRANSITIONING"
}
