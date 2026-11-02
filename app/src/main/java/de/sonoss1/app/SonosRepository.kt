package de.sonoss1.app

import android.content.Context
import android.net.wifi.WifiManager
import de.sonoss1.core.Episode
import de.sonoss1.core.NowPlaying
import de.sonoss1.core.PlayableItem
import de.sonoss1.core.Podcast
import de.sonoss1.core.PodcastFeed
import de.sonoss1.core.PodcastService
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
    private val podcastService = PodcastService(http)
    private val feedCache = mutableMapOf<String, PodcastFeed>()
    val prefs = Prefs(appContext)

    private val _groups = MutableStateFlow<List<SonosGroup>>(emptyList())
    val groups: StateFlow<List<SonosGroup>> = _groups.asStateFlow()

    /** Bumped whenever episodes are ticked off, so open episode lists can refresh. */
    private val _heardChanges = MutableStateFlow(0)
    val heardChanges: StateFlow<Int> = _heardChanges.asStateFlow()

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

    suspend fun searchPodcasts(term: String): List<Podcast> = withContext(Dispatchers.IO) { podcastService.search(term) }

    suspend fun findFeedUrl(link: String): String? = withContext(Dispatchers.IO) { podcastService.findFeedUrl(link) }

    suspend fun loadFeed(feedUrl: String, forceReload: Boolean = false): PodcastFeed = withContext(Dispatchers.IO) {
        if (!forceReload) feedCache[feedUrl]?.let { return@withContext it }
        podcastService.load(feedUrl).also { feedCache[feedUrl] = it }
    }

    fun subscribe(podcast: Podcast) {
        prefs.podcasts = prefs.podcasts.filter { it.feedUrl != podcast.feedUrl } + podcast
    }

    fun unsubscribe(podcast: Podcast) {
        prefs.podcasts = prefs.podcasts.filter { it.feedUrl != podcast.feedUrl }
    }

    fun setHeard(podcast: Podcast, episodeIds: Collection<String>, heard: Boolean) {
        val current = prefs.heard(podcast.feedUrl)
        prefs.setHeard(podcast.feedUrl, if (heard) current + episodeIds else current - episodeIds.toSet())
        _heardChanges.value++
    }

    /** Puts [episodes] into the room's Sonos queue; Sonos plays them one after another by itself. */
    suspend fun playEpisodes(group: SonosGroup, podcast: Podcast, episodes: List<Episode>) = withContext(Dispatchers.IO) {
        val queue = episodes.take(QUEUE_SIZE)
        client.playQueue(group, queue.map { it.toItem(podcast.title) }, prefs.forceHttp)
        prefs.lastGroupId = group.coordinator.uuid
        prefs.queueFeed = podcast.feedUrl
        prefs.queueIds = queue.map { it.id }
        prefs.queueUrls = queue.map { it.url }
        setHeard(podcast, listOf(queue.first().id), heard = true)
    }

    /** Ticks off every queued episode up to the one Sonos is playing now. */
    fun trackProgress(nowPlaying: NowPlaying) {
        val feed = prefs.queueFeed ?: return
        val playing = nowPlaying.trackUri?.let(::comparable) ?: return
        val index = prefs.queueUrls.indexOfFirst { comparable(it) == playing }
        if (index < 0) return
        val ids = prefs.queueIds.take(index + 1)
        if (!prefs.heard(feed).containsAll(ids)) {
            prefs.setHeard(feed, prefs.heard(feed) + ids)
            _heardChanges.value++
        }
    }

    /** Sonos may report the URL with another scheme (https sent as http, radio prefix). */
    private fun comparable(uri: String) = uri.substringAfter("://").substringBefore('#')

    suspend fun resume(group: SonosGroup) = withContext(Dispatchers.IO) { client.resume(group) }
    suspend fun pause(group: SonosGroup) = withContext(Dispatchers.IO) { client.pause(group) }
    suspend fun stop(group: SonosGroup) = withContext(Dispatchers.IO) { client.stop(group) }
    suspend fun setVolume(group: SonosGroup, volume: Int) = withContext(Dispatchers.IO) { client.setVolume(group, volume) }

    suspend fun status(group: SonosGroup): Pair<NowPlaying, Int> = withContext(Dispatchers.IO) {
        val nowPlaying = client.nowPlaying(group)
        trackProgress(nowPlaying)
        nowPlaying to client.volume(group)
    }

    private companion object {
        const val QUEUE_SIZE = 20
    }
}
