package de.sonoss1.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.sonoss1.core.Episode
import de.sonoss1.core.Podcast
import de.sonoss1.core.SonosGroup
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PodcastUiState(
    val subscriptions: List<Podcast> = emptyList(),
    val searching: Boolean = false,
    val searchResults: List<Podcast>? = null,
    /** The podcast whose episode list is shown; null shows the overview. */
    val open: Podcast? = null,
    val episodes: List<Episode> = emptyList(),
    val loading: Boolean = false,
    val loadError: String? = null,
    val heard: Set<String> = emptySet(),
    val onlyUnheard: Boolean = true,
    val oldestFirst: Boolean = true,
    val busy: Boolean = false,
) {
    /** Episodes in the chosen order and filter – this is also the order they are queued in. */
    val visibleEpisodes: List<Episode>
        get() {
            // Feeds list the newest episode first; dates refine that where present.
            val newestFirst = episodes.withIndex()
                .sortedWith(compareByDescending<IndexedValue<Episode>> { it.value.published ?: Long.MIN_VALUE }.thenBy { it.index })
                .map { it.value }
            val sorted = if (oldestFirst) newestFirst.reversed() else newestFirst
            return if (onlyUnheard) sorted.filter { it.id !in heard } else sorted
        }

    val unheardCount: Int get() = episodes.count { it.id !in heard }
}

class PodcastViewModel(private val repo: SonosRepository) : ViewModel() {
    private val _state = MutableStateFlow(PodcastUiState(subscriptions = repo.prefs.podcasts))
    val state: StateFlow<PodcastUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            repo.heardChanges.collect {
                val open = _state.value.open ?: return@collect
                _state.update { it.copy(heard = repo.prefs.heard(open.feedUrl)) }
            }
        }
    }

    /** Searches Apple's podcast directory, or – for a pasted link – finds the feed behind it. */
    fun search(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        _state.update { it.copy(searching = true, searchResults = null) }
        viewModelScope.launch {
            if (text.contains("://")) {
                val feedUrl = runCatching { repo.findFeedUrl(text) }.getOrNull()
                val feed = feedUrl?.let { url -> runCatching { repo.loadFeed(url) }.getOrNull() }
                _state.update { it.copy(searching = false) }
                if (feed == null) message("Hinter diesem Link wurde kein Podcast-Feed gefunden")
                else subscribe(feed.podcast)
            } else {
                runCatching { repo.searchPodcasts(text) }
                    .onSuccess { results ->
                        _state.update { it.copy(searching = false, searchResults = results) }
                        if (results.isEmpty()) message("Nichts gefunden")
                    }
                    .onFailure {
                        _state.update { s -> s.copy(searching = false) }
                        message("Suche fehlgeschlagen: ${it.message}")
                    }
            }
        }
    }

    fun clearSearch() = _state.update { it.copy(searchResults = null) }

    fun subscribe(podcast: Podcast) {
        repo.subscribe(podcast)
        _state.update { it.copy(subscriptions = repo.prefs.podcasts, searchResults = null) }
        open(podcast)
    }

    fun unsubscribe(podcast: Podcast) {
        repo.unsubscribe(podcast)
        _state.update { it.copy(subscriptions = repo.prefs.podcasts) }
        close()
    }

    fun open(podcast: Podcast, forceReload: Boolean = false) {
        _state.update {
            it.copy(
                open = podcast,
                episodes = if (it.open?.feedUrl == podcast.feedUrl) it.episodes else emptyList(),
                heard = repo.prefs.heard(podcast.feedUrl),
                loading = true,
                loadError = null,
            )
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            runCatching { repo.loadFeed(podcast.feedUrl, forceReload) }
                .onSuccess { feed -> _state.update { it.copy(loading = false, episodes = feed.episodes) } }
                .onFailure { e -> _state.update { it.copy(loading = false, loadError = e.message ?: "Feed konnte nicht geladen werden") } }
        }
    }

    fun close() {
        loadJob?.cancel()
        _state.update { it.copy(open = null, episodes = emptyList(), loadError = null, loading = false) }
    }

    fun setOnlyUnheard(value: Boolean) = _state.update { it.copy(onlyUnheard = value) }
    fun setOldestFirst(value: Boolean) = _state.update { it.copy(oldestFirst = value) }

    fun toggleHeard(episode: Episode) {
        val podcast = _state.value.open ?: return
        repo.setHeard(podcast, listOf(episode.id), heard = episode.id !in _state.value.heard)
    }

    fun markAllUnheard() {
        val s = _state.value
        val podcast = s.open ?: return
        repo.setHeard(podcast, s.episodes.map { it.id }, heard = false)
    }

    /** Queues [episode] and the following visible (by default: unheard) episodes on Sonos. */
    fun playFrom(group: SonosGroup?, episode: Episode) {
        val s = _state.value
        val visible = s.visibleEpisodes
        val start = visible.indexOfFirst { it.id == episode.id }
        val queue = if (start >= 0) visible.drop(start) else listOf(episode)
        play(group, queue)
    }

    fun playNextUnheard(group: SonosGroup?) {
        val queue = _state.value.copy(onlyUnheard = true).visibleEpisodes
        if (queue.isEmpty()) return message("Alle Folgen sind schon gehört")
        play(group, queue)
    }

    private fun play(group: SonosGroup?, queue: List<Episode>) {
        val podcast = _state.value.open ?: return
        if (group == null) return message("Bitte zuerst einen Raum auswählen")
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { repo.playEpisodes(group, podcast, queue) }
                .onSuccess { message("Spielt in ${group.name}: ${queue.first().title}") }
                .onFailure { message(it.message ?: "Abspielen fehlgeschlagen") }
            _state.update { it.copy(busy = false) }
        }
    }

    private fun message(text: String) {
        _messages.tryEmit(text)
    }

    companion object {
        fun factory(repo: SonosRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { PodcastViewModel(repo) }
        }
    }
}
