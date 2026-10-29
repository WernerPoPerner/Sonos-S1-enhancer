package de.sonoss1.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.sonoss1.core.PlayableItem
import de.sonoss1.core.ResolveResult
import de.sonoss1.core.SonosGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShareUiState(
    val resolving: Boolean = true,
    val items: List<PlayableItem> = emptyList(),
    val selectedItem: Int = 0,
    /** The shared content could not be turned into audio. */
    val error: String? = null,
    val searching: Boolean = true,
    val groups: List<SonosGroup> = emptyList(),
    val selectedGroupId: String? = null,
    val playing: Boolean = false,
    val playError: String? = null,
    /** Name of the room that is now playing; the sheet closes itself. */
    val done: String? = null,
)

class ShareViewModel(private val repo: SonosRepository) : ViewModel() {
    private val _state = MutableStateFlow(ShareUiState())
    val state: StateFlow<ShareUiState> = _state.asStateFlow()
    private var started = false

    fun start(text: String, subject: String?) {
        if (started) return
        started = true
        viewModelScope.launch {
            val result = runCatching { repo.resolve(text, subject) }
                .getOrElse { ResolveResult.Failed(it.message ?: "Link konnte nicht gelesen werden") }
            _state.update {
                when (result) {
                    is ResolveResult.Found -> it.copy(resolving = false, items = result.items)
                    is ResolveResult.Failed -> it.copy(resolving = false, error = result.message)
                }
            }
            maybeAutoPlay()
        }
        loadGroups()
    }

    fun loadGroups() {
        viewModelScope.launch {
            _state.update { it.copy(searching = true) }
            val groups = runCatching { repo.refreshGroups() }.getOrDefault(emptyList())
            _state.update { s ->
                s.copy(
                    searching = false,
                    groups = groups,
                    selectedGroupId = s.selectedGroupId?.takeIf { id -> groups.any { it.id == id } }
                        ?: repo.preferredGroup(groups)?.id,
                )
            }
            maybeAutoPlay()
        }
    }

    fun selectItem(index: Int) = _state.update { it.copy(selectedItem = index) }
    fun selectGroup(group: SonosGroup) = _state.update { it.copy(selectedGroupId = group.id) }

    fun play() {
        val s = _state.value
        val item = s.items.getOrNull(s.selectedItem) ?: return
        val group = s.groups.firstOrNull { it.id == s.selectedGroupId } ?: return
        if (s.playing) return
        _state.update { it.copy(playing = true, playError = null) }
        viewModelScope.launch {
            runCatching { repo.play(group, item) }
                .onSuccess { _state.update { it.copy(playing = false, done = group.name) } }
                .onFailure { e -> _state.update { it.copy(playing = false, playError = e.message ?: "Abspielen fehlgeschlagen") } }
        }
    }

    private fun maybeAutoPlay() {
        val s = _state.value
        if (repo.prefs.autoPlayOnShare && !s.resolving && !s.searching && s.items.size == 1 &&
            s.selectedGroupId != null && s.done == null && s.playError == null
        ) play()
    }

    companion object {
        fun factory(repo: SonosRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { ShareViewModel(repo) }
        }
    }
}
