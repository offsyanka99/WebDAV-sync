package org.vovchenko.webdavsync.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType

/** MB text field ↔ optional byte limit (blank = no limit). Used on Synchronization settings. */
@Composable
fun SizeLimitMbField(
    label: String,
    valueBytes: Long?,
    onBytesChange: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = valueBytes?.let { (it / (1024 * 1024)).toString() } ?: "",
        onValueChange = { text ->
            if (text.isEmpty()) {
                onBytesChange(null)
                return@OutlinedTextField
            }
            val mb = text.toLongOrNull() ?: return@OutlinedTextField
            val bytes = mb.coerceIn(0L, MAX_MB) * 1024L * 1024L
            onBytesChange(bytes)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
    )
}

private const val MAX_MB = 1024L * 1024L // 1 PiB in MB would overflow; 1M MB = 1 TiB
