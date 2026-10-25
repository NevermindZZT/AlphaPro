package com.letter.alphapro

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.letter.alphapro.core.heif.CoilHeifDecoder

class AlphaProApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(CoilHeifDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }
}
