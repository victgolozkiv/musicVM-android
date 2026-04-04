package com.musicplayer.utils;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;
import com.musicplayer.model.Song;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import java.io.File;

public class DownloadHelper {
    private static final String TAG = "DEBUG_PLAYER";

    public static void downloadSong(Context context, Song song, String youtubeUrl) {
        if (youtubeUrl == null || song == null) {
            Log.e(TAG, "DownloadHelper: URL o Canción nula");
            return;
        }

        String channelId = "downloads_channel";
        int notificationId = (int) System.currentTimeMillis();
        android.app.NotificationManager notificationManager = (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(channelId, "Descargas", android.app.NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
        }

        androidx.core.app.NotificationCompat.Builder notificationBuilder = new androidx.core.app.NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Descargando: " + song.getTitle())
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setProgress(0, 0, true);

        notificationManager.notify(notificationId, notificationBuilder.build());

        new Thread(() -> {
            try {
                String safeTitle = song.getTitle().replaceAll("[\\\\/:*?\"<>|]", "_");
                File cacheDir = new File(context.getExternalFilesDir(null), "downloads");
                if (!cacheDir.exists()) cacheDir.mkdirs();
                File tmpFile = new File(cacheDir, safeTitle + ".m4a");

                YoutubeDLRequest request = new YoutubeDLRequest(youtubeUrl);
                request.addOption("-o", tmpFile.getAbsolutePath());
                request.addOption("-f", "bestaudio[ext=m4a]/bestaudio");
                request.addOption("--no-mtime");
                request.addOption("--no-check-certificate");
                request.addOption("--buffer-size", "16K");
                request.addOption("--no-playlist");
                request.addOption("--add-header", "User-Agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

                YoutubeDL.getInstance().execute(request, null, (progress, eta, line) -> {
                    if (progress != null && progress.intValue() >= 0) {
                        int p = progress.intValue();
                        notificationBuilder.setProgress(100, p, false)
                                .setContentText("Progreso: " + p + "%");
                    } else {
                        // Si es -1 o null, significa que está procesando metadatos
                        notificationBuilder.setProgress(0, 0, true)
                                .setContentText("Procesando audio...");
                    }
                    notificationManager.notify(notificationId, notificationBuilder.build());
                    return null;
                });

                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, safeTitle + ".m4a");
                values.put(android.provider.MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
                values.put(android.provider.MediaStore.Audio.Media.TITLE, song.getTitle());
                values.put(android.provider.MediaStore.Audio.Media.ARTIST, song.getArtist());
                values.put(android.provider.MediaStore.Audio.Media.IS_MUSIC, 1);
                values.put(android.provider.MediaStore.Audio.Media.DATE_ADDED, System.currentTimeMillis() / 1000);
                
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    values.put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_MUSIC);
                }

                android.net.Uri audioUri = context.getContentResolver().insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);

                if (audioUri != null) {
                    try (java.io.InputStream is = new java.io.FileInputStream(tmpFile);
                         java.io.OutputStream os = context.getContentResolver().openOutputStream(audioUri)) {
                        byte[] buffer = new byte[8192];
                        int length;
                        while ((length = is.read(buffer)) > 0) {
                            os.write(buffer, 0, length);
                        }
                    }
                    tmpFile.delete();
                }

                notificationBuilder.setContentTitle("✅ Descarga completada")
                        .setContentText("¡Listo! " + song.getTitle())
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setOngoing(false)
                        .setProgress(0, 0, false)
                        .setAutoCancel(true);
                notificationManager.notify(notificationId, notificationBuilder.build());

                // Notificar a la UI para refrescar la lista
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context)
                        .sendBroadcast(new android.content.Intent("com.musicplayer.DOWNLOAD_COMPLETE"));

                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    Toast.makeText(context, "¡Completado! " + song.getTitle(), Toast.LENGTH_SHORT).show();
                });
                Log.d(TAG, "DownloadHelper: ¡Descarga completada con éxito!");

            } catch (Exception e) {
                Log.e(TAG, "DownloadHelper: Error", e);
                notificationManager.cancel(notificationId);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    Toast.makeText(context, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }
}
