package de.sonoss1.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.sonoss1.app.ShareUiState
import de.sonoss1.app.ShareViewModel
import de.sonoss1.core.PlayableItem
import kotlinx.coroutines.delay

@Composable
fun ShareScreen(vm: ShareViewModel, onClose: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(s.done) {
        if (s.done != null) {
            delay(1_500)
            onClose()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures { } },
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            tonalElevation = 6.dp,
        ) {
            Column(
                Modifier.padding(20.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Auf Sonos abspielen", style = MaterialTheme.typography.titleLarge)
                SheetContent(s, vm, onClose)
            }
        }
    }
}

@Composable
private fun SheetContent(s: ShareUiState, vm: ShareViewModel, onClose: () -> Unit) {
    if (s.done != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text("Läuft jetzt in ${s.done}", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    if (s.resolving) {
        Progress("Suche Audio im geteilten Link …")
        return
    }
    if (s.error != null) {
        Text(s.error, style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Schließen") }
        return
    }

    if (s.items.size == 1) {
        ItemSummary(s.items.first())
    } else {
        Text("Was soll abgespielt werden?", style = MaterialTheme.typography.titleSmall)
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
            s.items.forEachIndexed { index, item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = index == s.selectedItem, onClick = { vm.selectItem(index) })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = index == s.selectedItem, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    ItemSummary(item)
                }
            }
        }
    }

    Text("Raum", style = MaterialTheme.typography.titleSmall)
    when {
        s.searching && s.groups.isEmpty() -> Progress("Suche Sonos-Lautsprecher …")
        s.groups.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Keine Lautsprecher gefunden. Ist das Handy im selben WLAN?")
            OutlinedButton(onClick = vm::loadGroups) { Text("Erneut suchen") }
        }
        else -> RoomChips(s.groups, s.selectedGroupId, vm::selectGroup)
    }

    s.playError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

    val room = s.groups.firstOrNull { it.id == s.selectedGroupId }
    Button(
        onClick = vm::play,
        enabled = room != null && !s.playing,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (s.playing) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        } else {
            Text(room?.let { "Abspielen in ${it.name}" } ?: "Abspielen")
        }
    }
}

@Composable
private fun ItemSummary(item: PlayableItem) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(item.icon, contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun Progress(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text)
    }
}
