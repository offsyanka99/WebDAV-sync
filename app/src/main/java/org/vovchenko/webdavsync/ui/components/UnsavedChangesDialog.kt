package org.vovchenko.webdavsync.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Save, discard, or keep editing. Back and tapping outside keep the edits.
 * [onSave] stays disabled until the form can be stored.
 */
@Composable
fun UnsavedChangesDialog(
    message: String,
    canSave: Boolean,
    saving: Boolean,
    error: String?,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onKeepEditing() },
        title = { Text("Save changes?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                if (!canSave) {
                    Text("Save is unavailable until the required fields are filled in.")
                }
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = canSave && !saving) {
                Text(if (saving) "Saving…" else "Save")
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = onDiscard,
                    enabled = !saving,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Discard") }
                TextButton(onClick = onKeepEditing, enabled = !saving) { Text("Cancel") }
            }
        },
    )
}
