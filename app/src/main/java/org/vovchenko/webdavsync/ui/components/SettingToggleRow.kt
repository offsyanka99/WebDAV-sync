package org.vovchenko.webdavsync.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** How optional help text is shown next to a settings switch. */
enum class SettingHelpStyle {
    /** Gray (i) opens a dialog (Synchronization screen). */
    InlineInfo,

    /** Always-visible help under the switch, inside a card (Configuration screen). */
    CardBody,
}

/**
 * Shared settings toggle used by Synchronization ([SettingHelpStyle.InlineInfo]) and
 * Configuration ([SettingHelpStyle.CardBody]).
 */
@Composable
fun SettingToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    help: String? = null,
    helpStyle: SettingHelpStyle = SettingHelpStyle.InlineInfo,
    enabled: Boolean = true,
) {
    when (helpStyle) {
        SettingHelpStyle.InlineInfo -> {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LabelWithInfoIcon(
                    label = label,
                    infoDescription = help,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                )
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    enabled = enabled,
                )
            }
        }
        SettingHelpStyle.CardBody -> {
            Card(
                modifier = modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f).padding(end = 12.dp),
                        )
                        Switch(
                            checked = checked,
                            onCheckedChange = onCheckedChange,
                            enabled = enabled,
                        )
                    }
                    if (help != null) {
                        Text(
                            text = help,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
