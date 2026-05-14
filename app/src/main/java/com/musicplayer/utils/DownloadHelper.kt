package com.musicplayer.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.musicplayer.model.Song
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream

object DownloadHelper {
    private const val TAG = "DEBUG_PLAYER"

    @JvmStatic
    fun downloadSong(context: Context, song: Song?, youtubeUrl: String?, format: String) {
        if (youtubeUrl == null || song == null) {
            Log.e(TAG, "DownloadHelper: URL o Canción nula")
            return
        }

        val channelId = "downloads_channel"
        val notificationId = System.currentTimeMillis().toInt()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Descargas", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }

        val notificationBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Descargando: ${song.title}")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(0, 0, true)

        notificationManager.notify(notificationId, notificationBuilder.build())

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val safeTitle = song.title.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
                val cacheDir = File(context.getExternalFilesDir(null), "downloads")
                if (!cacheDir.exists()) cacheDir.mkdirs()
                val tmpFile = File(cacheDir, "$safeTitle.$format")

                val request = YoutubeDLRequest(youtubeUrl)
                request.addOption("-o", tmpFile.absolutePath)
                if (format.equals("mp3", ignoreCase = true)) {
                    request.addOption("-x")
                    request.addOption("--audio-format", "mp3")
                    request.addOption("--audio-quality", "4") // NITRO: Calidad V5 (balanceada) para velocidad máxima
                } else {
                    request.addOption("-f", "bestaudio[ext=$format]/bestaudio")
                }
                request.addOption("--no-mtime")
                request.addOption("--no-check-certificate")
                request.addOption("--buffer-size", "16M") // NITRO-MAX: 16MB de buffer para evitar hipos y maximizar velocidad
                request.addOption("--hls-prefer-native")
                request.addOption("--downloader", "ffmpeg") 
                request.addOption("--downloader-args", "ffmpeg:-threads 8") // Más hilos para equipos modernos
                request.addOption("--retries", "20")
                request.addOption("--no-playlist")
                request.addOption("--socket-timeout", "60")
                request.addOption("--no-part") // Evitar renombramiento constante de archivos
                request.addOption("--add-header", "User-Agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")

                YoutubeDL.getInstance().execute(request, null) { progress, _, _ ->
                    if (progress != null && progress.toInt() >= 0) {
                        val p = progress.toInt()
                        notificationBuilder.setProgress(100, p, false)
                            .setContentText("Progreso: $p%")
                    } else {
                        notificationBuilder.setProgress(0, 0, true)
                            .setContentText("Procesando audio...")
                    }
                    notificationManager.notify(notificationId, notificationBuilder.build())
                }

                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, "$safeTitle.$format")
                    when {
                        format.equals("mp3", ignoreCase = true) -> put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                        format.equals("wav", ignoreCase = true) -> put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                        else -> put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                    }
                    put(MediaStore.Audio.Media.TITLE, song.title)
                    put(MediaStore.Audio.Media.ARTIST, song.artist)
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                    put(MediaStore.Audio.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/NMusic")
                    }
                }

                val audioUri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)

                if (audioUri != null) {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(audioUri)?.use { os ->
                            FileInputStream(tmpFile).use { `is` ->
                                val buffer = ByteArray(1024 * 1024) // 1MB buffer para copia ultra-rápida
                                var length: Int
                                while (`is`.read(buffer).also { length = it } > 0) {
                                    os.write(buffer, 0, length)
                                }
                            }
                        }
                    }
                    tmpFile.delete()
                }

                notificationBuilder.setContentTitle("✅ Descarga completada")
                    .setContentText("¡Listo! ${song.title}")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setOngoing(false)
                    .setProgress(0, 0, false)
                    .setAutoCancel(true)
                notificationManager.notify(notificationId, notificationBuilder.build())

                LocalBroadcastManager.getInstance(context)
                    .sendBroadcast(Intent("com.musicplayer.DOWNLOAD_COMPLETE"))

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "¡Completado! ${song.title}", Toast.LENGTH_SHORT).show()
                }
                Log.d(TAG, "DownloadHelper: ¡Descarga completada con éxito!")

            } catch (e: Exception) {
                Log.e(TAG, "DownloadHelper: Error", e)
                notificationManager.cancel(notificationId)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
