package com.letter.alphapro.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.letter.alphapro.R
import com.letter.alphapro.data.AppLanguage
import com.letter.alphapro.data.AppLanguageOption
import com.letter.alphapro.ui.components.AlphaSegmentedButton
import com.letter.alphapro.ui.components.AlphaSingleChoiceSegmentedButtonRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLanguageSettings() {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var selected by remember(configuration) { mutableStateOf(AppLanguage.currentOption(context)) }
    Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Language, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.language_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.language_description), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            AlphaSingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AppLanguageOption.entries.forEachIndexed { index, option ->
                    AlphaSegmentedButton(selected = selected == option, onClick = {
                        selected = option
                        AppLanguage.set(context, option)
                    }, index = index, count = AppLanguageOption.entries.size) { Text(stringResource(option.labelRes)) }
                }
            }
        }
    }
}
