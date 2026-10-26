package com.letter.alphapro

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.letter.alphapro.core.bluetooth.SonyBleGattManager
import com.letter.alphapro.core.heif.CoilHeifDecoder
import com.letter.alphapro.core.location.AdaptiveLocationEngine
import com.letter.alphapro.core.location.FusedLocationSource
import com.letter.alphapro.core.location.GpxTrackLogger
import java.io.File

class AlphaProApplication : Application(), ImageLoaderFactory {
    lateinit var gattManager: SonyBleGattManager
        private set
    lateinit var locationEngine: AdaptiveLocationEngine
        private set
    lateinit var gpxLogger: GpxTrackLogger
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        gattManager = SonyBleGattManager(this)
        val locationSource = FusedLocationSource(this)
        locationEngine = AdaptiveLocationEngine(locationSource)
        val gpxDir = File(getExternalFilesDir(null), "tracks").apply { mkdirs() }
        gpxLogger = GpxTrackLogger(gpxDir)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(CoilHeifDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }

    companion object {
        lateinit var instance: AlphaProApplication
            private set
    }
}
