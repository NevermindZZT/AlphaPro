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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun WifiDiagnosticDialog(diagnostic: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wi-Fi 连接诊断") },
        text = {
            SelectionContainer {
                Text(
                    "以下仅包含阶段、特征编号、状态码、响应长度和 MTU，不包含热点名称或密码。\n\n" + diagnostic,
                    modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            AlphaTextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("AlphaPro Wi-Fi 诊断", diagnostic))
            }) { Text("复制诊断") }
        },
        dismissButton = { AlphaTextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
