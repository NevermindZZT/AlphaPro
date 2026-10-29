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
        gattManager = SonyBleGattManager(this, messageContext = { com.letter.alphapro.data.AppLanguage.localizedContext(this) })
        val locationSource = FusedLocationSource(this)
        locationEngine = AdaptiveLocationEngine(locationSource)
        // Automatic recording belongs in app-owned storage, not public Documents.
        // Directory existence/mkdirs does not grant scoped-storage write access.
        gpxLogger = GpxTrackLogger(File(filesDir, "tracks"))
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
