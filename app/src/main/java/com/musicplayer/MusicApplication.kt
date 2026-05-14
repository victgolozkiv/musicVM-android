package com.musicplayer

import android.app.Application
import android.util.Log
import com.musicplayer.downloader.OkHttpDownloader
import com.musicplayer.utils.CrashHandler
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import org.schabi.newpipe.extractor.NewPipe
import java.util.concurrent.Executors

class MusicApplication : Application() {
    companion object {
        private const val TAG = "DEBUG_PLAYER"
    }

    override fun onCreate() {
        super.onCreate()
        CrashHandler.init(this)
        
        try {
            Log.d(TAG, "Application: Inicializando YoutubeDL...")
            YoutubeDL.getInstance().init(this)
            Log.d(TAG, "Application: Inicializando FFmpeg...")
            FFmpeg.getInstance().init(this)
            
            // Actualizar a la última versión de yt-dlp cada vez que inicia la app para evitar bloqueos de YouTube
            Executors.newSingleThreadExecutor().execute {
                try {
                    Log.d(TAG, "Application: Buscando actualizaciones de yt-dlp...")
                    YoutubeDL.getInstance().updateYoutubeDL(this, YoutubeDL.UpdateChannel._NIGHTLY)
                    Log.d(TAG, "Application: yt-dlp actualizado con éxito.")
                } catch (e: Exception) {
                    Log.e(TAG, "Application: Error (no crítico) actualizando yt-dlp", e)
                }
            }

            // Inicializar NewPipe para búsquedas ultra-rápidas
            Log.d(TAG, "Application: Inicializando NewPipe...")
            NewPipe.init(OkHttpDownloader())
            Log.d(TAG, "Application: Motores listos ✅")
        } catch (e: Exception) {
            Log.e(TAG, "Application: ERROR FATAL EN INICIALIZACIÓN", e)
        }
    }
}
