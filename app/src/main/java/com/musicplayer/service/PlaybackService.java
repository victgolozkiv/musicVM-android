package com.musicplayer.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import com.musicplayer.repository.MusicRepository;
import com.musicplayer.ui.MainActivity;
import java.util.List;

public class PlaybackService extends MediaSessionService {
    private static final String TAG = "DEBUG_PLAYER";
    private static final String CHANNEL_ID = "music_channel_vfinal";
    private static final int NOTIFICATION_ID = 101;
    
    private ExoPlayer player;
    private MediaSession mediaSession;
    private MusicRepository repository;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service: Creando motor blindado para Android 14");
        repository = MusicRepository.getInstance(this);
        
        createNotificationChannel();
        startForegroundWithStatus("Iniciando...", "Preparando reproductor de música");

        // --- MOTOR DE RED NITRO (OkHttp) ---
        okhttp3.OkHttpClient okHttpClient = new okhttp3.OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .build();

        androidx.media3.datasource.okhttp.OkHttpDataSource.Factory httpDataSourceFactory = 
                new androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okHttpClient)
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36");

        // EXTRAER URL JUSTO ANTES DE REPRODUCIR (JIT)
        ResolvingDataSource.Factory resolvingDataSourceFactory = new ResolvingDataSource.Factory(
                new androidx.media3.datasource.DefaultDataSource.Factory(this, httpDataSourceFactory),
                dataSpec -> {
                    String originalUrl = dataSpec.uri.toString();
                    // Solo intentar extraer si parece una URL de YouTube (no local/content)
                    if (originalUrl.contains("youtube.com") || originalUrl.contains("youtu.be")) {
                        Log.d(TAG, "Service JIT: Resolviendo -> " + originalUrl);
                        String resolvedUrl = repository.getStreamUrlSync(originalUrl);
                        if (resolvedUrl != null) {
                            return dataSpec.buildUpon().setUri(android.net.Uri.parse(resolvedUrl)).build();
                        }
                    }
                    return dataSpec;
                }
        );

        // --- OPTIMIZACIÓN DE CARGA (VELOCIDAD EXTREMA 0.5s) ---
        androidx.media3.exoplayer.DefaultLoadControl loadControl = new androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        10000, // Min buffer 10s
                        20000, // Max buffer 20s
                        500,   // Buffer for playback 0.5s -> VELOCIDAD LUZ ⚡⚡
                        1000   // Buffer for playback after rebuffer 1s
                ).build();
 
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(resolvingDataSourceFactory))
                .setLoadControl(loadControl)
                .setAudioAttributes(AudioAttributes.DEFAULT, true)
                .setHandleAudioBecomingNoisy(true)
                .build();
        
        // ASEGURAR QUE SIEMPRE AVANCE AL SIGUIENTE TEMA
        player.setRepeatMode(Player.REPEAT_MODE_OFF);

        player.addListener(new Player.Listener() {
            @Override
            public void onMediaMetadataChanged(androidx.media3.common.MediaMetadata mediaMetadata) {
                Log.d(TAG, "Service: Cambio de metadata detectado");
                updateNotificationWithMetadata(mediaMetadata);
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) {
                    androidx.media3.common.MediaMetadata metadata = player.getMediaMetadata();
                    String title = (metadata.title != null) ? metadata.title.toString() : "Cargando...";
                    startForegroundWithStatus(title, "Preparando audio...");
                } else if (state == Player.STATE_READY) {
                    updateNotificationWithMetadata(player.getMediaMetadata());
                    // PRE-CARGA PROACTIVA DEL SIGUIENTE TEMA 🚀
                    prefetchNextTrack();
                }
            }

            @Override
            public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                if (mediaItem != null) {
                    prefetchNextTrack();
                    
                    // --- QUEUE GUARD: Mantener siempre al menos 3 temas por delante ---
                    int currentIdx = player.getCurrentMediaItemIndex();
                    int totalItems = player.getMediaItemCount();
                    int remaining = totalItems - (currentIdx + 1);
                    
                    if (remaining < 3) {
                        Log.d(TAG, "Service: 🛡️ Queue Guard activo (quedan " + remaining + "). Añadiendo más...");
                        fetchAndAddRelatedSongs(mediaItem);
                    }
                }
            }

            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                Log.e(TAG, "Service: Error motor -> " + error.getMessage());
            }
        });

        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        mediaSession = new MediaSession.Builder(this, player)
                .setSessionActivity(pendingIntent)
                .build();
    }

    private void prefetchNextTrack() {
        int nextIndex = player.getNextMediaItemIndex();
        if (nextIndex != C.INDEX_UNSET) {
            MediaItem nextItem = player.getMediaItemAt(nextIndex);
            if (nextItem.localConfiguration != null) {
                String nextUrl = nextItem.localConfiguration.uri.toString();
                if (nextUrl.contains("youtube.com") || nextUrl.contains("youtu.be")) {
                    Log.d(TAG, "Service: 🚀 Pre-cargando (URL Cache) el siguiente tema -> " + nextUrl);
                    repository.getStreamUrl(nextUrl, new MusicRepository.StreamCallback() {
                        @Override public void onSuccess(String streamUrl) {} // Ya queda en caché
                        @Override public void onError(Exception e) {}
                    });
                }
            }
        }
    }

    private void fetchAndAddRelatedSongs(MediaItem currentItem) {
        if (currentItem.localConfiguration == null) return;
        String url = currentItem.localConfiguration.uri.toString();
        
        Log.d(TAG, "Service: 🔄 Modo Radio - Buscando estilo similar para -> " + url);
        repository.getRelatedSongs(url, new MusicRepository.MusicCallback() {
            @Override
            public void onSuccess(List<com.musicplayer.model.Song> songs) {
                if (songs != null && !songs.isEmpty()) {
                    Log.d(TAG, "Service: ✅ Se añadieron " + songs.size() + " temas similares a la cola!");
                    for (com.musicplayer.model.Song song : songs) {
                        MediaItem.Builder builder = new MediaItem.Builder()
                                .setUri(song.getUrl())
                                .setMediaId(song.getId())
                                .setMediaMetadata(new androidx.media3.common.MediaMetadata.Builder()
                                        .setTitle(song.getTitle())
                                        .setArtist(song.getArtist())
                                        .setArtworkUri(android.net.Uri.parse(song.getThumbnailUrl()))
                                        .build());
                        player.addMediaItem(builder.build());
                    }
                }
            }
            @Override
            public void onError(Exception e) {
                Log.e(TAG, "Service: Error al cargar radio", e);
            }
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Música", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void updateNotificationWithMetadata(androidx.media3.common.MediaMetadata metadata) {
        String title = "Reproductor Java";
        String artist = "Música activa";

        if (metadata.title != null && metadata.title.length() > 0) {
            title = metadata.title.toString();
        }
        if (metadata.artist != null && metadata.artist.length() > 0) {
            artist = metadata.artist.toString();
        }

        startForegroundWithStatus(title, artist);
    }

    private boolean isForegroundStarted = false;

    private void startForegroundWithStatus(String title, String text) {
        // En Media3 1.3.1, el servicio gestiona gran parte de la notificación solo.
        // Solo necesitamos asegurar el inicio del foreground para Android 14+ JIT.
        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true);

        if (!isForegroundStarted) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notificationBuilder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(NOTIFICATION_ID, notificationBuilder.build());
                }
                isForegroundStarted = true;
            } catch (Exception e) {
                Log.e(TAG, "Service: startForeground fail", e);
            }
        }
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onDestroy() {
        if (player != null) player.release();
        if (mediaSession != null) mediaSession.release();
        super.onDestroy();
    }
}
