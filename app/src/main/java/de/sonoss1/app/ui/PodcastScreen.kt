package de.sonoss1.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.sonoss1.app.MainUiState
import de.sonoss1.app.MainViewModel
import de.sonoss1.app.PodcastUiState
import de.sonoss1.app.PodcastViewModel
import de.sonoss1.core.Episode
import de.sonoss1.core.Podcast
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun PodcastsTab(
    s: PodcastUiState,
    vm: PodcastViewModel,
    main: MainUiState,
    mainVm: MainViewModel,
    modifier: Modifier,
) {
    val open = s.open
    if (open == null) PodcastOverview(s, vm, modifier) else EpisodeList(s, vm, main, mainVm, modifier)
}

@Composable
private fun PodcastOverview(s: PodcastUiState, vm: PodcastViewModel, modifier: Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val search = {
        focus.clearFocus()
        vm.search(query)
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Podcast hinzufügen")
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Name (z. B. Kakadu) oder Link") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    trailingIcon = {
                        IconButton(onClick = search, enabled = !s.searching && query.isNotBlank()) {
                            Icon(Icons.Filled.Search, "Suchen")
                        }
                    },
                )
                if (s.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        s.searchResults?.let { results ->
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Suchergebnisse – zum Hinzufügen antippen")
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = vm::clearSearch) { Text("Schließen") }
                }
            }
            items(results, key = { "result:" + it.feedUrl }) { podcast ->
                PodcastRow(podcast, onClick = { vm.subscribe(podcast) }, showAdd = true)
            }
        }
        item { SectionTitle("Meine Podcasts") }
        if (s.subscriptions.isEmpty()) {
            item { Text("Noch keine Podcasts. Suche oben zum Beispiel nach »Kakadu«.") }
        }
        items(s.subscriptions, key = { "sub:" + it.feedUrl }) { podcast ->
            PodcastRow(podcast, onClick = { vm.open(podcast) })
        }
    }
}

@Composable
private fun PodcastRow(podcast: Podcast, onClick: () -> Unit, showAdd: Boolean = false) {
    val author = podcast.author
    ListItem(
        headlineContent = { Text(podcast.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (author != null) {
            { Text(author, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else null,
        leadingContent = { Icon(Icons.Filled.Podcasts, contentDescription = null) },
        trailingContent = if (showAdd) {
            { Icon(Icons.Filled.Add, "Hinzufügen") }
        } else null,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun EpisodeList(
    s: PodcastUiState,
    vm: PodcastViewModel,
    main: MainUiState,
    mainVm: MainViewModel,
    modifier: Modifier,
) {
    val visible = s.visibleEpisodes
    val dateFormat = remember { DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.systemDefault()) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RoomSection(main, mainVm)
                Text(
                    if (s.episodes.isEmpty() && s.loading) "Lade Folgen …"
                    else "${s.episodes.size} Folgen · ${s.unheardCount} ungehört",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = { vm.playNextUnheard(main.selected) },
                    enabled = !s.busy && s.unheardCount > 0 && main.selected != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Nächste ungehörte Folge abspielen")
                }
                Text(
                    "Sonos spielt danach die folgenden Folgen der Liste automatisch weiter (bis zu 20) " +
                        "und sie werden hier abgehakt. Das ersetzt die Sonos-Warteschlange dieses Raums.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = s.onlyUnheard,
                        onClick = { vm.setOnlyUnheard(!s.onlyUnheard) },
                        label = { Text("Nur ungehörte") },
                    )
                    AssistChip(
                        onClick = { vm.setOldestFirst(!s.oldestFirst) },
                        label = { Text(if (s.oldestFirst) "Älteste zuerst" else "Neueste zuerst") },
                        leadingIcon = { Icon(Icons.Filled.SwapVert, contentDescription = null) },
                    )
                }
                s.loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        items(visible, key = { it.id }) { episode ->
            EpisodeRow(
                episode = episode,
                heard = episode.id in s.heard,
                dateFormat = dateFormat,
                onPlay = { vm.playFrom(main.selected, episode) },
                onToggleHeard = { vm.toggleHeard(episode) },
            )
        }
        if (!s.loading && visible.isEmpty() && s.episodes.isNotEmpty()) {
            item {
                Column {
                    Text("Alle Folgen gehört 🎉")
                    TextButton(onClick = vm::markAllUnheard) { Text("Alle wieder als ungehört markieren") }
                }
            }
        }
        if (s.episodes.isNotEmpty()) {
            item {
                Text(
                    "Tippe auf eine Folge, um ab dort abzuspielen. Der Kreis links hakt sie ab. " +
                        "Angezeigt wird alles, was der Sender im Podcast-Feed anbietet.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    heard: Boolean,
    dateFormat: DateTimeFormatter,
    onPlay: () -> Unit,
    onToggleHeard: () -> Unit,
) {
    val details = listOfNotNull(
        episode.published?.let { dateFormat.format(Instant.ofEpochMilli(it)) },
        episode.durationSec?.let { "${(it + 30) / 60} min" },
        if (heard) "gehört" else null,
    ).joinToString(" · ")
    ListItem(
        headlineContent = {
            Text(
                episode.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (heard) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
            )
        },
        supportingContent = { Text(details) },
        leadingContent = {
            IconButton(onClick = onToggleHeard) {
                if (heard) {
                    Icon(Icons.Filled.CheckCircle, "Als ungehört markieren", tint = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(Icons.Outlined.RadioButtonUnchecked, "Als gehört markieren")
                }
            }
        },
        trailingContent = { Icon(Icons.Filled.PlayArrow, "Ab hier abspielen") },
        modifier = Modifier.clickable(onClick = onPlay),
    )
}
