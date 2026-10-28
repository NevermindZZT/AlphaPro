@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.letter.alphapro.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.letter.alphapro.R
import com.letter.alphapro.data.ParameterSliderScale

/**
 * A card-free parameter editor. Use logarithmic = true for second ranges such as
 * 1..3600 (finer control near the minimum), and false for counts such as 1..999.
 * The caller owns the published value; dialog edits remain private until confirmed.
 */
@Composable
fun AlphaParameterSlider(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    valueRange: IntRange,
    valueLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    logarithmic: Boolean = false
) {
    val position = ParameterSliderScale.toPosition(value, valueRange, logarithmic)
    // Replacing these states synchronously discards any draft when a run disables
    // the control, even if it is re-enabled before a dismissal effect could run.
    var dialogOpen by remember(enabled, valueRange) { mutableStateOf(false) }
    var draft by remember(enabled, valueRange) { mutableStateOf("") }
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentRange by rememberUpdatedState(valueRange)
    val currentLogarithmic by rememberUpdatedState(logarithmic)

    val settingsDescription = stringResource(R.string.parameter_settings_a11y, label)
    val dialogTitle = stringResource(R.string.parameter_dialog_title, label)
    val inputLabel = stringResource(R.string.parameter_value_label)
    val rangeDescription = stringResource(
        R.string.parameter_range, valueRange.first, valueRange.last
    )
    val confirmLabel = stringResource(R.string.parameter_confirm)
    val cancelLabel = stringResource(R.string.parameter_cancel)

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AlphaIconButton(
                onClick = {
                    if (currentEnabled) {
                        draft = value.coerceIn(currentRange).toString()
                        dialogOpen = true
                    }
                },
                enabled = enabled
            ) {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = settingsDescription,
                    modifier = Modifier.size(AlphaButtonDefaults.IconSize)
                )
            }
        }
        Slider(
            value = position,
            onValueChange = { nextPosition ->
                if (currentEnabled) {
                    currentOnValueChange(
                        ParameterSliderScale.fromPosition(
                            nextPosition, currentRange, currentLogarithmic
                        )
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = label
                stateDescription = valueLabel
            },
            enabled = enabled,
            valueRange = 0f..1f
        )
    }

    if (enabled && dialogOpen) {
        val parsed = draft.trim().toIntOrNull()
        val error = when {
            parsed == null -> stringResource(R.string.parameter_error_whole_number)
            parsed < valueRange.first -> stringResource(
                R.string.parameter_error_minimum, valueRange.first
            )
            parsed > valueRange.last -> stringResource(
                R.string.parameter_error_maximum, valueRange.last
            )
            else -> null
        }
        // Both the button and IME use the same guarded publication path. No
        // composable resource lookup occurs in any event callback.
        val confirm: () -> Unit = {
            val candidate = draft.trim().toIntOrNull()
            if (currentEnabled && dialogOpen && candidate != null && candidate in currentRange) {
                dialogOpen = false
                currentOnValueChange(candidate)
            }
        }
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text(dialogTitle) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (currentEnabled) draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    label = { Text(inputLabel) },
                    supportingText = { Text(error ?: rangeDescription) },
                    isError = error != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { confirm() })
                )
            },
            confirmButton = {
                AlphaButton(onClick = confirm, enabled = enabled && error == null) {
                    Text(confirmLabel)
                }
            },
            dismissButton = {
                AlphaTextButton(onClick = { dialogOpen = false }) {
                    Text(cancelLabel)
                }
            }
        )
    }
}
