package com.letter.alphapro.ui.onboarding

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import com.letter.alphapro.R
import com.letter.alphapro.ui.components.AlphaButton
import com.letter.alphapro.ui.components.AlphaTextButton

/** A raw Skip callback only opens this dialog; no rendering/disposal path confirms it. */
@Composable
fun OnboardingSkipConfirmation(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    enabled: Boolean = true,
    onTrace: (String) -> Unit = {}
) {
    val latestTrace by rememberUpdatedState(onTrace)
    val inputTrace = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                for (change in event.changes) {
                    val phase = when {
                        change.pressed && !change.previousPressed -> "down"
                        !change.pressed && change.previousPressed -> "up"
                        else -> null
                    }
                    if (phase != null) latestTrace("input_dialog_pointer phase=$phase type=${change.type}")
                }
            }
        }
    }.onPreviewKeyEvent { event ->
        if (event.key in listOf(Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.DirectionCenter, Key.Back, Key.Escape)) {
            latestTrace("input_dialog_key action=${event.nativeKeyEvent.action} code=${event.nativeKeyEvent.keyCode} repeat=${event.nativeKeyEvent.repeatCount}")
        }
        false
    }
    AlertDialog(
        modifier = inputTrace,
        onDismissRequest = { if (enabled) onCancel() },
        title = { Text(stringResource(R.string.onboarding_skip_confirm_title)) },
        text = { Text(stringResource(R.string.onboarding_skip_confirm_message)) },
        confirmButton = {
            AlphaButton(onClick = onConfirm, enabled = enabled) {
                Text(stringResource(R.string.onboarding_skip_confirm_action))
            }
        },
        dismissButton = {
            AlphaTextButton(onClick = onCancel, enabled = enabled) {
                Text(stringResource(R.string.onboarding_skip_continue))
            }
        }
    )
}
