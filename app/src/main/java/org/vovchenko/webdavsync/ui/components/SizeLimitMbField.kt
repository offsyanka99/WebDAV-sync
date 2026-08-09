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
            val mb = text.toLongOrNull()
            onBytesChange(mb?.let { it * 1024 * 1024 })
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
    )
}
