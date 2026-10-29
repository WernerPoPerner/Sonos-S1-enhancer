package de.sonoss1.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sonoss1.app.MainUiState
import de.sonoss1.app.MainViewModel
import de.sonoss1.core.PlayableItem
import de.sonoss1.core.Stations

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("S1 Player") },
                actions = {
                    IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, "Lautsprecher suchen") }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, "Einstellungen") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { RoomSection(state, vm) }
            item { NowPlayingCard(state, vm) }
            item { LinkInput(busy = state.busy, onPlay = vm::playLink) }
            item { SectionTitle("Sender") }
            items(Stations.all, key = { "station:" + it.streamUrl }) { station ->
                val item = station.toItem()
                PlayableRow(item, onClick = { vm.play(item) })
            }
            if (state.history.isNotEmpty()) {
                item { SectionTitle("Zuletzt gespielt") }
                items(state.history, key = { "history:" + it.url }) { item ->
                    PlayableRow(item, onClick = { vm.play(item) }, onDelete = { vm.removeFromHistory(item) })
                }
            }
            item { HelpCard() }
        }
    }

    state.choices?.let { choices ->
        ChoiceDialog(choices, onPick = vm::play, onDismiss = vm::dismissChoices)
    }
    if (showSettings) {
        SettingsDialog(
            state = state,
            onSave = { ip, forceHttp, autoPlay ->
                vm.saveSettings(ip, forceHttp, autoPlay)
                showSettings = false
            },
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun RoomSection(state: MainUiState, vm: MainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Raum")
        when {
            state.groups.isEmpty() && state.searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Suche Sonos-Lautsprecher …")
            }
            state.groups.isEmpty() -> Text(
                "Keine Lautsprecher gefunden. Ist das Handy im selben WLAN wie Sonos? " +
                    "Falls die Suche im Netz blockiert ist, trage die IP-Adresse eines Lautsprechers in den Einstellungen ein.",
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> RoomChips(state.groups, state.selected?.id, vm::select)
        }
    }
}

@Composable
private fun NowPlayingCard(state: MainUiState, vm: MainViewModel) {
    val group = state.selected ?: return
    val np = state.nowPlaying
    var dragging by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(0f) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(group.name, style = MaterialTheme.typography.labelLarge)
            Text(
                np?.title ?: "—",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            np?.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledIconButton(onClick = vm::togglePlayPause) {
                    if (np?.isPlaying == true) Icon(Icons.Filled.Pause, "Pause") else Icon(Icons.Filled.PlayArrow, "Abspielen")
                }
                IconButton(onClick = vm::stop) { Icon(Icons.Filled.Stop, "Stopp") }
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Lautstärke")
                Slider(
                    value = if (dragging) sliderValue else (state.volume ?: 0).toFloat(),
                    onValueChange = {
                        dragging = true
                        sliderValue = it
                    },
                    onValueChangeFinished = {
                        vm.setVolume(sliderValue.toInt())
                        dragging = false
                    },
                    valueRange = 0f..100f,
                    enabled = state.volume != null,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun LinkInput(busy: Boolean, onPlay: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Link abspielen")
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Link zu Sendung, Podcast oder Stream") },
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { clipboard.getText()?.text?.let { text = it } }) {
                    Icon(Icons.Filled.ContentPaste, "Einfügen")
                }
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onPlay(text) }, enabled = !busy && text.isNotBlank()) { Text("Abspielen") }
            if (busy) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun PlayableRow(item: PlayableItem, onClick: () -> Unit, onDelete: (() -> Unit)? = null) {
    val subtitle = item.subtitle
    ListItem(
        headlineContent = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (subtitle != null) {
            { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else null,
        leadingContent = { Icon(item.icon, contentDescription = null) },
        trailingContent = if (onDelete != null) {
            { IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Entfernen") } }
        } else null,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun HelpCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("So funktioniert's", style = MaterialTheme.typography.titleSmall)
            Text(
                "Tippe in einer anderen App (z. B. Deutschlandfunk, ARD Audiothek, Apple Podcasts, Browser) " +
                    "auf »Teilen« und wähle »Auf Sonos abspielen«. Die App sucht den Audio-Stream im geteilten Link " +
                    "und schickt ihn an den gewählten Raum.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ChoiceDialog(choices: List<PlayableItem>, onPick: (PlayableItem) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Was soll abgespielt werden?") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                choices.forEach { item -> PlayableRow(item, onClick = { onPick(item) }) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun SettingsDialog(
    state: MainUiState,
    onSave: (String, Boolean, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var ip by remember { mutableStateOf(state.manualIp) }
    var forceHttp by remember { mutableStateOf(state.forceHttp) }
    var autoPlay by remember { mutableStateOf(state.autoPlayOnShare) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Einstellungen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text("IP-Adresse eines Lautsprechers (optional)") },
                    placeholder = { Text("z. B. 192.168.1.20") },
                    singleLine = true,
                )
                SettingSwitch(
                    "Beim Teilen sofort abspielen",
                    "Spielt ohne Nachfrage im zuletzt genutzten Raum, wenn genau ein Audio gefunden wurde.",
                    autoPlay,
                ) { autoPlay = it }
                SettingSwitch(
                    "HTTPS als HTTP senden",
                    "Hilft älteren S1-Lautsprechern, die verschlüsselte Streams nicht abspielen.",
                    forceHttp,
                ) { forceHttp = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(ip, forceHttp, autoPlay) }) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
