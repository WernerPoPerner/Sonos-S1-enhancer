package de.sonoss1.app

import android.content.Context
import android.net.wifi.WifiManager
import de.sonoss1.core.NowPlaying
import de.sonoss1.core.PlayableItem
import de.sonoss1.core.ResolveResult
import de.sonoss1.core.SonosClient
import de.sonoss1.core.SonosDiscovery
import de.sonoss1.core.SonosGroup
import de.sonoss1.core.StreamResolver
import de.sonoss1.core.UrlConnectionFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Single entry point for the UI: speaker discovery, link resolution and playback. */
class SonosRepository(context: Context) {
    private val appContext = context.applicationContext
    private val http = UrlConnectionFetcher()
    private val discovery = SonosDiscovery(http)
    private val client = SonosClient(http)
    private val resolver = StreamResolver(http)
    val prefs = Prefs(appContext)

    private val _groups = MutableStateFlow<List<SonosGroup>>(emptyList())
    val groups: StateFlow<List<SonosGroup>> = _groups.asStateFlow()

    suspend fun refreshGroups(): List<SonosGroup> = withContext(Dispatchers.IO) {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("s1player").apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            val known = listOf(prefs.manualIp).filter { it.isNotBlank() } + prefs.knownIps
            val found = discovery.discover(known)
            if (found.isNotEmpty()) {
                prefs.knownIps = found.flatMap { g -> g.members.map { it.ip } + g.coordinator.ip }.distinct()
            }
            _groups.value = found
            found
        } finally {
            lock.release()
        }
    }

    fun preferredGroup(groups: List<SonosGroup>): SonosGroup? {
        val last = prefs.lastGroupId
        return groups.firstOrNull { it.coordinator.uuid == last }
            ?: groups.firstOrNull { g -> g.members.any { it.uuid == last } }
            ?: groups.firstOrNull()
    }

    suspend fun resolve(text: String, subject: String?): ResolveResult =
        withContext(Dispatchers.IO) { resolver.resolve(text, subject) }

    suspend fun play(group: SonosGroup, item: PlayableItem) = withContext(Dispatchers.IO) {
        client.play(group, item, prefs.forceHttp)
        prefs.lastGroupId = group.coordinator.uuid
        prefs.addToHistory(item)
    }

    suspend fun resume(group: SonosGroup) = withContext(Dispatchers.IO) { client.resume(group) }
    suspend fun pause(group: SonosGroup) = withContext(Dispatchers.IO) { client.pause(group) }
    suspend fun stop(group: SonosGroup) = withContext(Dispatchers.IO) { client.stop(group) }
    suspend fun setVolume(group: SonosGroup, volume: Int) = withContext(Dispatchers.IO) { client.setVolume(group, volume) }

    suspend fun status(group: SonosGroup): Pair<NowPlaying, Int> = withContext(Dispatchers.IO) {
        client.nowPlaying(group) to client.volume(group)
    }
}
