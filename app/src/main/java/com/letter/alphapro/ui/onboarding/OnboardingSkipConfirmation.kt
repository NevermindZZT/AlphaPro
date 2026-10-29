package com.letter.alphapro.ui.onboarding

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.letter.alphapro.R
import com.letter.alphapro.ui.components.AlphaButton
import com.letter.alphapro.ui.components.AlphaTextButton

/** A raw Skip callback only opens this dialog; no rendering/disposal path confirms it. */
@Composable
fun OnboardingSkipConfirmation(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    enabled: Boolean = true
) {
    AlertDialog(
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
