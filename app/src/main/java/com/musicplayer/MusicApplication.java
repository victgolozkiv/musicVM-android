package com.musicplayer;

import android.app.Application;
import android.util.Log;
import com.musicplayer.downloader.OkHttpDownloader;
import com.yausername.youtubedl_android.YoutubeDL;
import org.schabi.newpipe.extractor.NewPipe;

public class MusicApplication extends Application {
    private static final String TAG = "DEBUG_PLAYER";

    @Override
    public void onCreate() {
        super.onCreate();
        
        try {
            YoutubeDL.getInstance().init(this);
            // Actualizar a la última versión de yt-dlp cada vez que inicia la app para evitar bloqueos de YouTube
            new Thread(() -> {
                try {
                    Log.d(TAG, "Application: Buscando actualizaciones de yt-dlp...");
                    YoutubeDL.getInstance().updateYoutubeDL(this, YoutubeDL.UpdateChannel._STABLE);
                    Log.d(TAG, "Application: yt-dlp actualizado con éxito.");
                } catch (Exception e) {
                    Log.e(TAG, "Application: Error actualizando yt-dlp", e);
                }
            }).start();

            // Inicializar NewPipe para búsquedas ultra-rápidas
            NewPipe.init(new OkHttpDownloader());
            Log.d(TAG, "Application: Motores listos.");
        } catch (Exception e) {
            Log.e(TAG, "Application: Error inicializando motores", e);
        }
    }
}
