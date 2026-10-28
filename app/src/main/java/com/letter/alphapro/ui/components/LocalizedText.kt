package com.letter.alphapro.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** Keep resource identity in retained screen state, so a language change does not cache a translation. */
data class LocalizedText(@StringRes val resource: Int, val arguments: List<Any> = emptyList()) {
    @Composable fun asString(): String = stringResource(resource, *arguments.toTypedArray())
}
