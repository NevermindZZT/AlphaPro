package com.letter.alphapro.ui.gallery

import android.graphics.Bitmap

data class PhotoPreviewState(
    val handle: Long? = null,
    val bitmap: Bitmap? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val sourceLabel: String? = null
)
