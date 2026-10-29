package de.sonoss1.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import de.sonoss1.core.PlayableItem
import de.sonoss1.core.SonosGroup
import de.sonoss1.core.StreamKind

val PlayableItem.icon: ImageVector
    get() = if (kind == StreamKind.LIVE) Icons.Filled.Radio else Icons.Filled.Podcasts

@Composable
fun RoomChips(groups: List<SonosGroup>, selectedId: String?, onSelect: (SonosGroup) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        groups.forEach { group ->
            FilterChip(
                selected = group.id == selectedId,
                onClick = { onSelect(group) },
                label = { Text(group.name) },
                leadingIcon = { Icon(Icons.Filled.Speaker, contentDescription = null) },
            )
        }
    }
}
