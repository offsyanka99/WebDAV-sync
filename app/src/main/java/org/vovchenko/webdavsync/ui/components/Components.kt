package org.vovchenko.webdavsync.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A titled card container used for grouped sections (Sync status, Recent changes, Cloud storage, etc.). */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** A label/value row, e.g. "Last sync : 2026-08-06 10:24". */
@Composable
fun LabeledRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}

/**
 * Shared product name style: `Web` + gray `DAV` + `-Sync`.
 * Used on Overview header and About.
 */
@Composable
fun WebDavSyncTitle(
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 26.sp,
    fontWeight: FontWeight = FontWeight.SemiBold,
    textAlign: TextAlign? = null,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val title = buildAnnotatedString {
        append("Web")
        withStyle(SpanStyle(color = muted)) { append("DAV") }
        append("-Sync")
    }
    Text(
        text = title,
        modifier = modifier,
        style = MaterialTheme.typography.titleLarge.copy(
            fontSize = fontSize,
            fontWeight = fontWeight,
        ),
        textAlign = textAlign,
    )
}

/**
 * Label text with an optional trailing gray (i) that wraps with the last line of the phrase
 * (not floating beside the first line of a multi-line label).
 */
@Composable
fun LabelWithInfoIcon(
    label: String,
    infoDescription: String?,
    modifier: Modifier = Modifier,
) {
    if (infoDescription == null) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier,
        )
        return
    }

    var showDialog by remember { mutableStateOf(false) }
    val iconId = "info_icon"
    val annotated = remember(label) {
        buildAnnotatedString {
            append(label)
            append('\u00A0') // non-breaking space so icon stays with last word when possible
            appendInlineContent(iconId, "[i]")
        }
    }
    val inlineContent = mapOf(
        iconId to InlineTextContent(
            Placeholder(
                width = 18.sp,
                height = 18.sp,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { showDialog = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "About $label",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        },
    )
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium,
        inlineContent = inlineContent,
        modifier = modifier,
    )
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(label) },
            text = { Text(infoDescription) },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) { Text("OK") }
            },
        )
    }
}

/**
 * Compact label + switch. Prefer [SettingToggleRow] for new settings with help text;
 * this remains a thin wrapper for simple toggles (folder pair options, etc.).
 */
@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    infoDescription: String? = null,
) {
    SettingToggleRow(
        label = label,
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        help = infoDescription,
        helpStyle = SettingHelpStyle.InlineInfo,
    )
}
