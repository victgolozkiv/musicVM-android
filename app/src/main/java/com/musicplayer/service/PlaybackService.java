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
import androidx.media3.common.ForwardingPlayer;
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
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.database.StandaloneDatabaseProvider;
import android.media.audiofx.Equalizer;
import java.io.File;
import java.util.List;

public class PlaybackService extends MediaSessionService {
    private static final String TAG = "DEBUG_PLAYER";
    private static final String CHANNEL_ID = "music_channel_vfinal";
    private static final int NOTIFICATION_ID = 101;
    
    private ExoPlayer player;
    private MediaSession mediaSession;
    private MusicRepository repository;
    
    // Phase 1: Engine Foundation
    private static SimpleCache simpleCache;
    public static Equalizer equalizer;
    
    public static SimpleCache getCache() {
        return simpleCache;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service: Creando motor blindado para Android 14");
        repository = MusicRepository.getInstance(this);
        
        createNotificationChannel();
        startForegroundWithStatus("Iniciando...", "Preparando reproductor de música");

        // --- SMART CACHE (Offline Auto-Cache) ---
        if (simpleCache == null) {
            File cacheDir = new File(getCacheDir(), "media3_cache");
            LeastRecentlyUsedCacheEvictor evictor = new LeastRecentlyUsedCacheEvictor(500 * 1024 * 1024); // 500 MB max
            StandaloneDatabaseProvider databaseProvider = new StandaloneDatabaseProvider(this);
            simpleCache = new SimpleCache(cacheDir, evictor, databaseProvider);
            Log.d(TAG, "Service: 🧠 Smart Cache (500MB) configurado.");
        }

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
 
        // --- CACHE ROUTING ---
        CacheDataSource.Factory cacheDataSourceFactory = new CacheDataSource.Factory()
                .setCache(simpleCache)
                .setUpstreamDataSourceFactory(resolvingDataSourceFactory) // Intercept network misses to cache them
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(cacheDataSourceFactory))
                .setLoadControl(loadControl)
                .setAudioAttributes(AudioAttributes.DEFAULT, true)
                .setHandleAudioBecomingNoisy(true)
                .build();
        
        // ASEGURAR QUE HAGA BUCLE SI SOLO HAY UN TEMA
        player.setRepeatMode(Player.REPEAT_MODE_ALL);

        player.addListener(new Player.Listener() {
            @Override
            public void onAudioSessionIdChanged(int audioSessionId) {
                Log.d(TAG, "Service: AudioSessionId changed to " + audioSessionId);
                try {
                    if (equalizer != null) {
                        equalizer.release();
                    }
                    if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                        equalizer = new Equalizer(0, audioSessionId);
                        equalizer.setEnabled(true);
                        Log.d(TAG, "Service: 🎛️ Equalizer bound to session " + audioSessionId);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Service: Failed to bind Equalizer", e);
                }
            }

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
                    // PRE-CARGA PROACTIVA DE LOS SIGUIENTES TEMAS 🚀
                    prefetchNextTracks();
                }
            }

            @Override
            public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                if (mediaItem != null) {
                    prefetchNextTracks();
                    
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
                // Si falla el JIT o URL, auto saltar a la siguiente.
                if (player.hasNextMediaItem()) {
                    Log.d(TAG, "Service: Auto-skipping to next due to error.");
                    player.seekToNextMediaItem();
                    player.prepare();
                    player.play();
                }
            }
        });

        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        ForwardingPlayer forwardingPlayer = new ForwardingPlayer(player) {
            @Override
            public Player.Commands getAvailableCommands() {
                return super.getAvailableCommands().buildUpon()
                        .add(Player.COMMAND_SEEK_TO_NEXT)
                        .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                        .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                        .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                        .build();
            }

            @Override
            public boolean isCommandAvailable(int command) {
                if (command == Player.COMMAND_SEEK_TO_NEXT || command == Player.COMMAND_SEEK_TO_PREVIOUS ||
                    command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM || command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) {
                    return true;
                }
                return super.isCommandAvailable(command);
            }

            @Override
            public boolean hasNextMediaItem() {
                return true;
            }

            @Override
            public boolean hasPreviousMediaItem() {
                return true;
            }

            @Override
            public void seekToNextMediaItem() {
                super.seekToNextMediaItem();
            }

            @Override
            public void seekToPreviousMediaItem() {
                super.seekToPreviousMediaItem();
            }

            @Override
            public void seekToNext() {
                seekToNextMediaItem();
            }

            @Override
            public void seekToPrevious() {
                seekToPreviousMediaItem();
            }
        };

        mediaSession = new MediaSession.Builder(this, forwardingPlayer)
                .setSessionActivity(pendingIntent)
                .build();
    }

    private void prefetchNextTracks() {
        int nextIndex = player.getNextMediaItemIndex();
        int count = 0;
        // Pre-cargar hasta 3 temas por adelantado en paralelo para máxima velocidad
        while (nextIndex != C.INDEX_UNSET && count < 3) {
            MediaItem nextItem = player.getMediaItemAt(nextIndex);
            if (nextItem.localConfiguration != null) {
                String nextUrl = nextItem.localConfiguration.uri.toString();
                if (nextUrl.contains("youtube.com") || nextUrl.contains("youtu.be")) {
                    Log.d(TAG, "Service: 🚀 Pre-cargando (URL Cache) tema -> " + nextUrl);
                    repository.getStreamUrl(nextUrl, new MusicRepository.StreamCallback() {
                        @Override public void onSuccess(String streamUrl) {} // Ya queda en caché
                        @Override public void onError(Exception e) {}
                    });
                }
            }
            if (nextIndex < player.getMediaItemCount() - 1) {
                nextIndex++;
            } else {
                nextIndex = C.INDEX_UNSET;
            }
            count++;
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
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
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
                    });
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
        if (equalizer != null) {
            equalizer.release();
            equalizer = null;
        }
        // No liberar el SimpleCache porque es estático y bloquea el dir si se regenera
        super.onDestroy();
    }
}
