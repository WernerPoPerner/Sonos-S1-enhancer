package de.sonoss1.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.sonoss1.core.NowPlaying
import de.sonoss1.core.PlayableItem
import de.sonoss1.core.ResolveResult
import de.sonoss1.core.SonosGroup
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class MainUiState(
    val groups: List<SonosGroup> = emptyList(),
    val selected: SonosGroup? = null,
    val searching: Boolean = false,
    val nowPlaying: NowPlaying? = null,
    val volume: Int? = null,
    val history: List<PlayableItem> = emptyList(),
    val busy: Boolean = false,
    /** Several candidates were found for a pasted link; the user has to pick one. */
    val choices: List<PlayableItem>? = null,
    val manualIp: String = "",
    val forceHttp: Boolean = false,
    val autoPlayOnShare: Boolean = false,
)

class MainViewModel(private val repo: SonosRepository) : ViewModel() {
    private val _state = MutableStateFlow(
        MainUiState(
            history = repo.prefs.history,
            manualIp = repo.prefs.manualIp,
            forceHttp = repo.prefs.forceHttp,
            autoPlayOnShare = repo.prefs.autoPlayOnShare,
        )
    )
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var pollJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(searching = true) }
            val groups = runCatching { repo.refreshGroups() }.getOrDefault(emptyList())
            _state.update { s ->
                s.copy(
                    searching = false,
                    groups = groups,
                    selected = groups.firstOrNull { it.id == s.selected?.id } ?: repo.preferredGroup(groups),
                )
            }
            if (groups.isEmpty()) message("Keine Sonos-Lautsprecher gefunden")
            updateStatus()
        }
    }

    fun select(group: SonosGroup) {
        repo.prefs.lastGroupId = group.coordinator.uuid
        _state.update { it.copy(selected = group, nowPlaying = null, volume = null) }
        viewModelScope.launch { updateStatus() }
    }

    fun startPolling() {
        _state.update { it.copy(history = repo.prefs.history) }
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                updateStatus()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun play(item: PlayableItem) {
        val group = _state.value.selected ?: return message("Bitte zuerst einen Raum auswählen")
        _state.update { it.copy(busy = true, choices = null) }
        viewModelScope.launch {
            runCatching { repo.play(group, item) }
                .onSuccess { message("Spielt in ${group.name}: ${item.title}") }
                .onFailure { message(it.message ?: "Abspielen fehlgeschlagen") }
            _state.update { it.copy(busy = false, history = repo.prefs.history) }
            updateStatus()
        }
    }

    fun playLink(text: String) {
        if (text.isBlank()) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = runCatching { repo.resolve(text, null) }
                .getOrElse { ResolveResult.Failed(it.message ?: "Link konnte nicht gelesen werden") }
            _state.update { it.copy(busy = false) }
            when (result) {
                is ResolveResult.Failed -> message(result.message)
                is ResolveResult.Found ->
                    if (result.items.size == 1) play(result.items.first())
                    else _state.update { it.copy(choices = result.items) }
            }
        }
    }

    fun dismissChoices() = _state.update { it.copy(choices = null) }

    fun removeFromHistory(item: PlayableItem) {
        repo.prefs.removeFromHistory(item)
        _state.update { it.copy(history = repo.prefs.history) }
    }

    fun togglePlayPause() = control { group ->
        if (_state.value.nowPlaying?.isPlaying == true) repo.pause(group) else repo.resume(group)
    }

    fun stop() = control { repo.stop(it) }

    fun setVolume(volume: Int) {
        _state.update { it.copy(volume = volume) }
        control { repo.setVolume(it, volume) }
    }

    fun saveSettings(manualIp: String, forceHttp: Boolean, autoPlayOnShare: Boolean) {
        val ipChanged = manualIp.trim() != repo.prefs.manualIp
        repo.prefs.manualIp = manualIp
        repo.prefs.forceHttp = forceHttp
        repo.prefs.autoPlayOnShare = autoPlayOnShare
        _state.update { it.copy(manualIp = manualIp.trim(), forceHttp = forceHttp, autoPlayOnShare = autoPlayOnShare) }
        if (ipChanged) refresh()
    }

    private fun control(action: suspend (SonosGroup) -> Unit) {
        val group = _state.value.selected ?: return message("Bitte zuerst einen Raum auswählen")
        viewModelScope.launch {
            runCatching { action(group) }.onFailure { message(it.message ?: "Befehl fehlgeschlagen") }
            updateStatus()
        }
    }

    private suspend fun updateStatus() {
        val group = _state.value.selected ?: return
        runCatching { repo.status(group) }.onSuccess { (nowPlaying, volume) ->
            _state.update { if (it.selected?.id == group.id) it.copy(nowPlaying = nowPlaying, volume = volume) else it }
        }
    }

    private fun message(text: String) {
        _messages.tryEmit(text)
    }

    companion object {
        private const val POLL_INTERVAL_MS = 5_000L

        fun factory(repo: SonosRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { MainViewModel(repo) }
        }
    }
}
