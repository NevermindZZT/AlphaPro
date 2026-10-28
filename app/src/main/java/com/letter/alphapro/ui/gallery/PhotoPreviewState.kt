package com.letter.alphapro.ui.gallery

import android.graphics.Bitmap
import com.letter.alphapro.ui.components.LocalizedText

data class PhotoPreviewState(
    val handle: Long? = null,
    val bitmap: Bitmap? = null,
    val isLoading: Boolean = false,
    val error: LocalizedText? = null,
    val sourceLabel: LocalizedText? = null
)
