package com.letter.alphapro.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.letter.alphapro.R

@Composable
fun WifiDiagnosticDialog(diagnostic: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_wifi_title)) },
        text = {
            SelectionContainer {
                Text(
                    stringResource(R.string.dialog_wifi_report, diagnostic),
                    modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            AlphaTextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.dialog_wifi_clip_label), diagnostic))
            }) { Text(stringResource(R.string.dialog_wifi_copy)) }
        },
        dismissButton = { AlphaTextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_wifi_close)) } }
    )
}
