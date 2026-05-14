package com.musicplayer.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.gson.Gson
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.musicplayer.repository.MusicRepository
import com.musicplayer.db.entity.PlaybackHistory
import com.musicplayer.ui.MainActivity
import com.musicplayer.utils.MusicCacheManager
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null
    private lateinit var repository: MusicRepository
    private var equalizer: Equalizer? = null
    private var dynamicsProcessing: DynamicsProcessing? = null
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val resolvedUrlCache = ConcurrentHashMap<String, String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // GUARDAS DE ESTABILIDAD Y MÉTRICAS
    private var isSkipping = false
    private val skippingDebounceMs = 500L
    private var lastSkipTime = 0L
    private var lastNextClickTime = 0L
    private var lastResolutionStartTime = 0L
    private var lastBufferingStartTime = 0L
    
    // CONTROL CLÍNICO (Generaciones y Mutex)
    private var queueGeneration = 0
    private val playlistMutex = Mutex()

    companion object {
        private const val TAG = "DEBUG_PLAYER"
        private const val CHANNEL_ID = "music_channel_vfinal"
        private const val NOTIFICATION_ID = 101
        private const val CROSSFADE_MS = 3000L
        private const val NITRO_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
        
        const val ACTION_STOP = "com.musicplayer.ACTION_STOP"
        const val ACTION_PREPARE_SONG = "com.musicplayer.ACTION_PREPARE_SONG"
    }

    private fun createDataSourceFactory(context: Context, simpleCache: SimpleCache): DataSource.Factory {
        val httpDataSourceFactory = OkHttpDataSource.Factory(MusicRepository.okHttpClient)
            .setUserAgent(NITRO_UA)
            .setDefaultRequestProperties(
                mapOf(
                    "Accept-Language" to "en-US,en;q=0.9,es;q=0.8",
                    "Icy-MetaData" to "1" // Para streams (Ej: Icecast/SoundCloud/Audio)
                )
            )

        val upstreamFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        return ResolvingDataSource.Factory(cacheDataSourceFactory) { dataSpec ->
            val originalUrl = dataSpec.uri.toString()

            // BYPASS: Solo evitamos resolución para URLs que YA son streams directos o archivos locales.
            val isAlreadyResolved = originalUrl.contains("googlevideo.com") || 
                originalUrl.contains("sndcdn.com") || 
                originalUrl.contains("cf-media.") ||
                originalUrl.startsWith("/") || 
                originalUrl.startsWith("file://") || 
                originalUrl.startsWith("content://")

            if (!isAlreadyResolved) {
                val resolutionStart = System.currentTimeMillis()
                val cachedUrl = resolvedUrlCache[originalUrl]
                if (cachedUrl != null) {
                    Log.d(TAG, "METRIC: ⚡ CACHE_HIT en ${System.currentTimeMillis() - resolutionStart}ms para: $originalUrl")
                    return@Factory dataSpec.withUri(Uri.parse(cachedUrl))
                }

                // FAST-FAIL RESOLVER: Si el prefetch no llegó a tiempo, resolvemos aquí con timeout de 15s
                Log.d(TAG, "METRIC: 🔍 DATASOURCE_RESOLVE (Fallback) para: $originalUrl")
                val resolvedUrl = kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(15000) {
                        repository.resolveStreamNitro(originalUrl)
                    }
                }
                if (resolvedUrl != null && resolvedUrl.contains("http")) {
                    resolvedUrlCache[originalUrl] = resolvedUrl
                    Log.d(TAG, "METRIC: ✅ DATASOURCE_RESOLVED en ${System.currentTimeMillis() - resolutionStart}ms")
                    return@Factory dataSpec.withUri(Uri.parse(resolvedUrl))
                }
                Log.e(TAG, "METRIC: ❌ DATASOURCE_TIMEOUT/FAILED para $originalUrl")
                throw java.io.IOException("NewPipeExtractor no pudo resolver la URL del stream para $originalUrl")
            }
            dataSpec
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service: Creando motor blindado con Nitro-Stabilizer (UA: Standardized)")
        repository = MusicRepository.getInstance(this)
        
        createNotificationChannel()
        startForegroundWithStatus("Iniciando...", "Preparando reproductor de música")

        val simpleCache = MusicCacheManager.getInstance(this).cache
        if (simpleCache == null) {
            Log.e(TAG, "Service: ❌ CACHE NO DISPONIBLE.")
            stopSelf()
            return
        }

        val dataSourceFactory = createDataSourceFactory(this, simpleCache)
        
        // NITRO-SAFE-MODE: Buffer estable para evitar buffering infinito y cortes
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30000,   // Min buffer (Aumentado para estabilidad)
                60000,   // Max buffer
                2500,    // Playback start buffer ⚡ (Aumentado para evitar micro-pausas)
                5000     // Rebuffer
            )
            .setBackBuffer(15000, true) 
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .setSkipSilenceEnabled(true)
            .build()
        
        player.repeatMode = Player.REPEAT_MODE_OFF

        player.addListener(object : Player.Listener {
            private var lastSessionId = -1
            private var trackWasActuallyReady = false

            override fun onAudioSessionIdChanged(sessionId: Int) {
                if (sessionId <= 0 || sessionId == lastSessionId) return
                lastSessionId = sessionId
                repository.setActiveAudioSessionId(sessionId)
                try {
                    equalizer?.release()
                    equalizer = Equalizer(0, sessionId).apply { enabled = true }
                    dynamicsProcessing?.release()
                    val configBuilder = DynamicsProcessing.Config.Builder(C.INDEX_UNSET, 1, false, 0, false, 0, false, 0, true)
                    val limiter = DynamicsProcessing.Limiter(true, true, 0, 1.0f, 2.0f, 10.0f, -1.0f, 0.0f)
                    configBuilder.setPreferredFrameDuration(10.0f)
                    configBuilder.setLimiterByChannelIndex(0, limiter)
                    dynamicsProcessing = DynamicsProcessing(0, sessionId, configBuilder.build()).apply { enabled = true }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to bind AudioEffects", e)
                }
            }

            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                updateNotificationWithMetadata(mediaMetadata)
            }

            override fun onPlaybackStateChanged(state: Int) {
                val stateName = when(state) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN"
                }
                Log.d(TAG, "METRIC: 🔄 STATE_CHANGE -> $stateName (Index: ${player.currentMediaItemIndex}, MediaId: ${player.currentMediaItem?.mediaId})")

                when (state) {
                    Player.STATE_BUFFERING -> {
                        lastBufferingStartTime = System.currentTimeMillis()
                        // NITRO-STABLE: No cambiamos la notificación a "Preparando..." para evitar parpadeos
                        Log.d(TAG, "METRIC: ⏳ BUFFERING... URI: ${player.currentMediaItem?.localConfiguration?.uri}")
                    }
                    Player.STATE_READY -> {
                        val bufferingDuration = if (lastBufferingStartTime > 0) System.currentTimeMillis() - lastBufferingStartTime else 0
                        Log.d(TAG, "METRIC: ✅ READY (Buffering: ${bufferingDuration}ms, Click2Play: ${System.currentTimeMillis() - lastNextClickTime}ms)")
                        trackWasActuallyReady = true
                        updateNotificationWithMetadata(player.mediaMetadata)
                        prefetchNextTracks()
                    }
                    Player.STATE_IDLE -> {
                        Log.d(TAG, "METRIC: 🛑 IDLE (Player possible stalled or error)")
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val reasonName = when(reason) {
                    Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
                    Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
                    Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
                    Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
                    else -> "OTHER"
                }
                Log.d(TAG, "METRIC: ⏭️ TRANSITION (Reason: $reasonName, ID: ${mediaItem?.mediaId})")

                mediaItem?.let { item ->
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && !trackWasActuallyReady) {
                        Log.e(TAG, "Service: 🚫 GHOST SKIP DETECTADO — El track previo no cargó y el player saltó al siguiente.")
                        player.pause()
                        player.stop()
                        return
                    }
                    trackWasActuallyReady = false
                    
                    scope.launch {
                        val history = PlaybackHistory(item.mediaId, item.mediaMetadata.title?.toString() ?: "", item.mediaMetadata.artist?.toString() ?: "", item.localConfiguration?.uri?.toString() ?: "", 0, item.mediaMetadata.artworkUri?.toString() ?: "", System.currentTimeMillis())
                        repository.insertPlaybackHistory(history)
                    }

                    prefetchNextTracks()
                    startFadeIn()
                    
                    val remaining = player.mediaItemCount - (player.currentMediaItemIndex + 1)
                    if (remaining < 5) {
                        fetchAndAddRelatedSongs(item, false)
                    }
                }
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                Log.d(TAG, "METRIC: 📅 TIMELINE_CHANGED (Reason: $reason, Items: ${timeline.windowCount})")
            }

            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause
                Log.e(TAG, "==========================================")
                Log.e(TAG, "💥 ERROR CRÍTICO DE EXOPLAYER 💥")
                Log.e(TAG, "ID: ${player.currentMediaItem?.mediaId}")
                Log.e(TAG, "ErrorCode: ${error.errorCode} - ${error.errorCodeName}")
                Log.e(TAG, "Mensaje: ${error.message}")
                
                if (cause is androidx.media3.datasource.HttpDataSource.HttpDataSourceException) {
                    Log.e(TAG, "🌐 Error de Red Crítico:")
                    Log.e(TAG, "   URI Fallida: ${cause.dataSpec.uri}")
                    if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                        Log.e(TAG, "   HTTP Status: ${cause.responseCode} (Posible 403 Forbidden)")
                        Log.e(TAG, "   Headers: ${cause.headerFields}")
                    }
                }
                Log.e(TAG, "==========================================")
                
                // Detenemos el salto fantasma parando el reproductor.
                player.pause()
                
                if (error.errorCode == 3030 || error.errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION || error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
                    val targetGen = queueGeneration
                    val currentPos = player.currentPosition
                    val currentIndex = player.currentMediaItemIndex
                    val item = player.currentMediaItem ?: return

                    // FORCE CLEAR CACHE TO PREVENT INFINITE LOOP OF 403s
                    repository.removeCache(item.mediaId)
                    item.localConfiguration?.uri?.toString()?.let { 
                        repository.removeCache(it)
                        resolvedUrlCache.remove(it) // También limpiar el cache de RAM
                    }

                    // En lugar de replaceMediaItem, solo preparamos. 
                    // El ResolvingDataSource se encargará de re-resolver al intentar cargar de nuevo.
                    player.prepare()
                    player.play()
                }
            }
        })

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val forwardingPlayer = object : ForwardingPlayer(player) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return if (command == Player.COMMAND_SEEK_TO_NEXT || command == Player.COMMAND_SEEK_TO_PREVIOUS ||
                    command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM || command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) {
                    true
                } else super.isCommandAvailable(command)
            }

            override fun hasNextMediaItem(): Boolean = true
            override fun hasPreviousMediaItem(): Boolean = true
            override fun seekToNextMediaItem() {
                val now = System.currentTimeMillis()
                if (now - lastSkipTime < skippingDebounceMs) return
                lastSkipTime = now
                lastNextClickTime = now
                queueGeneration++
                Log.d(TAG, "FAST_SKIP: ⏭️ Inmediato Gen:$queueGeneration")
                
                // CONTROL DE COLA VACÍA ("No pasa nada o reinicia la canción")
                if (player.nextMediaItemIndex == C.INDEX_UNSET) {
                    val currentItem = player.currentMediaItem
                    if (currentItem != null) {
                        Log.w(TAG, "FAST_SKIP: ⚠️ Fin de la cola. Solicitando nuevas pistas de emergencia...")
                        player.pause() // Simula buffering visualmente
                        startForegroundWithStatus("Obteniendo recomendaciones...", "Conectando...")
                        fetchAndAddRelatedSongs(currentItem, forceSkip = true)
                    }
                    return // No saltamos hasta que lleguen las nuevas canciones
                }
                
                super.seekToNextMediaItem()
                if (player.playbackState == Player.STATE_IDLE || player.playerError != null) {
                    player.prepare()
                }
                player.play()
            }

            override fun seekToPreviousMediaItem() {
                val now = System.currentTimeMillis()
                if (now - lastSkipTime < skippingDebounceMs) return
                lastSkipTime = now
                queueGeneration++
                super.seekToPreviousMediaItem()
                if (player.playbackState == Player.STATE_IDLE || player.playerError != null) {
                    player.prepare()
                }
                player.play()
            }
            
            override fun seekToNext() = seekToNextMediaItem()
            override fun seekToPrevious() = seekToPreviousMediaItem()
        }

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(pendingIntent)
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
                    var isSearch = false
                    var searchQuery: String? = null

                    for (item in mediaItems) {
                        if (item.requestMetadata.searchQuery != null && item.requestMetadata.searchQuery!!.isNotEmpty()) {
                            isSearch = true
                            searchQuery = item.requestMetadata.searchQuery
                            break
                        }
                    }

                    if (isSearch && searchQuery != null) {
                        Log.d(TAG, "Service: 🎙️ Search requested via onAddMediaItems: $searchQuery")
                        val future = com.google.common.util.concurrent.SettableFuture.create<List<MediaItem>>()
                        
                        repository.searchSongs(searchQuery, object : MusicRepository.MusicCallback {
                            override fun onSuccess(songs: List<com.musicplayer.model.Song>) {
                                val results = songs.map { s ->
                                    MediaItem.Builder()
                                        .setMediaId(s.id)
                                        .setUri(Uri.parse(s.url))
                                        .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder()
                                            .setTitle(s.title)
                                            .setArtist(s.artist)
                                            .setArtworkUri(Uri.parse(s.thumbnailUrl))
                                            .build())
                                        .build()
                                }
                                future.set(results)
                            }
                            override fun onError(e: Exception) {
                                future.set(emptyList())
                            }
                        })
                        return future
                    }
                    
                    return Futures.immediateFuture(mediaItems)
                }
            })
            .build()
        
        fadeHandler.post(crossfadeCheckRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_STOP -> {
                player.pause()
                player.stop()
                stopForeground(true)
                stopSelf()
            }
            ACTION_PREPARE_SONG -> {
                val songJson = intent.getStringExtra("song_json")
                if (songJson != null) {
                    try {
                        val song = Gson().fromJson(songJson, com.musicplayer.model.Song::class.java)
                        prePrepareSong(song)
                    } catch (e: Exception) {
                        Log.e(TAG, "Prepare Error", e)
                    }
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun prePrepareSong(song: com.musicplayer.model.Song) {
        scope.launch {
            val originalUrl = song.url ?: ""
            if (originalUrl.isEmpty()) return@launch
            
            // NITRO-ULTRA-WARMING: Solo resolvemos y cacheamos en RAM (resolvedUrlCache).
            // NO tocamos el player para evitar que aparezca el mini-player al inicio.
            val streamUrl = resolvedUrlCache[originalUrl] ?: repository.resolveStreamNitro(originalUrl)
            
            if (streamUrl != null) {
                resolvedUrlCache[originalUrl] = streamUrl
                Log.d(TAG, "WARMING: ✅ Stream pre-resuelto y cacheado silenciosamente para: $originalUrl")
                repository.nitroPreCache(streamUrl)
            }
        }
    }

    private fun prefetchNextTracks() {
        val startIndex = player.nextMediaItemIndex
        if (startIndex == C.INDEX_UNSET) return
        val targetGen = queueGeneration
        var nextIndex = startIndex
        var count = 0

        while (nextIndex != C.INDEX_UNSET && count < 2) {
            val itemIndex = nextIndex
            val nextItem = player.getMediaItemAt(itemIndex)
            val nextUrl = nextItem.localConfiguration?.uri?.toString() ?: nextItem.mediaId
            
            // Si ya es una URL de CDN o ya está en caché, no necesitamos prefetch
            val alreadyResolved = resolvedUrlCache.containsKey(nextUrl) || 
                                 nextUrl.contains("googlevideo.com") || 
                                 nextUrl.contains("sndcdn.com")

            if (!alreadyResolved && nextUrl.length > 15) {
                Log.d(TAG, "PREFETCH: 🚀 Resolviendo fondo para $nextUrl")
                scope.launch(Dispatchers.IO) {
                    try {
                        val streamUrl = kotlinx.coroutines.withTimeoutOrNull(8000) {
                            repository.resolveStreamNitro(nextUrl)
                        } ?: return@launch

                        if (queueGeneration == targetGen) {
                            resolvedUrlCache[nextUrl] = streamUrl
                            Log.d(TAG, "PREFETCH: ✅ Caché Ram listo para $nextUrl. Eliminado replaceMediaItem por inestabilidad.")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "PREFETCH: ❌ Falló $nextUrl", e)
                    }
                }
            }
            nextIndex = if (nextIndex < player.mediaItemCount - 1) nextIndex + 1 else C.INDEX_UNSET
            count++
        }
    }

    private val fadeHandler = Handler(Looper.getMainLooper())
    private var currentVolume = 1.0f

    private val fadeOutRunnable = object : Runnable {
        override fun run() {
            if (currentVolume > 0.05f) {
                currentVolume -= 0.05f
                player.volume = currentVolume
                fadeHandler.postDelayed(this, 100)
            } else {
                player.volume = 0f
            }
        }
    }

    private val fadeInRunnable = object : Runnable {
        override fun run() {
            if (currentVolume < 1.0f) {
                currentVolume += 0.05f
                player.volume = currentVolume
                fadeHandler.postDelayed(this, 100)
            } else {
                currentVolume = 1.0f
                player.volume = 1.0f
            }
        }
    }

    private fun startFadeOut() {
        fadeHandler.removeCallbacks(fadeInRunnable)
        fadeHandler.post(fadeOutRunnable)
    }

    private fun startFadeIn() {
        fadeHandler.removeCallbacks(fadeOutRunnable)
        // NITRO-BOOST: Empezamos con un volumen mayor (0.2 en vez de 0.05) para evitar el "hueco" sonoro 
        if (currentVolume < 0.3f) {
            currentVolume = 0.3f
            player.volume = currentVolume
        }
        fadeHandler.post(fadeInRunnable)
    }

    private var isLoadingNext = false

    private fun fetchAndAddRelatedSongs(currentItem: MediaItem, forceSkip: Boolean = false) {
        if (currentItem.localConfiguration == null) return
        if (isLoadingNext) return
        
        scope.launch {
            val uri = currentItem.localConfiguration?.uri ?: return@launch
            val url = uri.toString()
            val currentSong = com.musicplayer.model.Song(
                currentItem.mediaId,
                currentItem.mediaMetadata.title?.toString() ?: "",
                currentItem.mediaMetadata.artist?.toString() ?: "",
                url,
                0,
                currentItem.mediaMetadata.artworkUri?.toString() ?: ""
            )

            Log.d(TAG, "Service: 🛡️ Queue Guard: Buscando recomendaciones (Safe Lock)...")
            // RESOLUCIÓN FUERA DEL LOCK
            val related = withTimeoutOrNull(8000) { repository.getRelatedSongsSync(currentSong) }
            
            if (related != null && related.isNotEmpty()) {
                playlistMutex.withLock {
                    isLoadingNext = true
                    try {
                        val items = related.map { s ->
                            MediaItem.Builder()
                                .setMediaId(s.id)
                                .setUri(Uri.parse(s.url))
                                .setMediaMetadata(MediaMetadata.Builder()
                                    .setTitle(s.title)
                                    .setArtist(s.artist)
                                    .setArtworkUri(Uri.parse(s.thumbnailUrl))
                                    .build())
                                .setRequestMetadata(MediaItem.RequestMetadata.Builder()
                                    .setMediaUri(Uri.parse(s.url))
                                    .build())
                                .build()
                        }
                        this@PlaybackService.player.addMediaItems(items)
                        Log.d(TAG, "Service: 🛡️ Queue Guard: ${items.size} canciones inyectadas.")
                        
                        if (forceSkip) {
                            Log.d(TAG, "Service: 🚀 Fuerza-Skip activado. Saltando a las nuevas rolas.")
                            this@PlaybackService.player.seekToNextMediaItem()
                            if (this@PlaybackService.player.playbackState == Player.STATE_IDLE) {
                                this@PlaybackService.player.prepare()
                            }
                            this@PlaybackService.player.play()
                        }
                    } finally {
                        isLoadingNext = false
                    }
                }
            } else if (forceSkip) {
                // FALLBACK: si falla NewPipe, usamos Buffer Nuclear en vez de reiniciar la pista fallida
                Log.e(TAG, "Service: ❌ Fallo obteniendo relacionados. Recurriendo a Buffer Nuclear...")
                repository.getPersonalizedMix(object : MusicRepository.MusicCallback {
                    override fun onSuccess(songs: List<com.musicplayer.model.Song>) {
                        scope.launch {
                            playlistMutex.withLock {
                                val items = songs.filter { !it.isHeader }.map { s ->
                                    MediaItem.Builder()
                                        .setMediaId(s.id)
                                        .setUri(Uri.parse(s.url))
                                        .setMediaMetadata(MediaMetadata.Builder()
                                            .setTitle(s.title)
                                            .setArtist(s.artist)
                                            .setArtworkUri(Uri.parse(s.thumbnailUrl))
                                            .build())
                                        .setRequestMetadata(MediaItem.RequestMetadata.Builder()
                                            .setMediaUri(Uri.parse(s.url))
                                            .build())
                                        .build()
                                }
                                if (items.isNotEmpty()) {
                                    this@PlaybackService.player.addMediaItems(items)
                                    this@PlaybackService.player.seekToNextMediaItem()
                                    if (this@PlaybackService.player.playbackState == Player.STATE_IDLE || this@PlaybackService.player.playerError != null) {
                                        this@PlaybackService.player.prepare()
                                    }
                                    this@PlaybackService.player.play()
                                } else {
                                    if (this@PlaybackService.player.playbackState == Player.STATE_IDLE || this@PlaybackService.player.playerError != null) {
                                        this@PlaybackService.player.prepare()
                                    }
                                    this@PlaybackService.player.play()
                                }
                            }
                        }
                    }
                    override fun onError(e: Exception) {
                        Log.e(TAG, "Service: ❌ Fallo total en Fallback. Se queda atorado.")
                        if (this@PlaybackService.player.playbackState == Player.STATE_IDLE || this@PlaybackService.player.playerError != null) {
                            this@PlaybackService.player.prepare()
                        }
                        this@PlaybackService.player.play()
                    }
                })
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Música", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun updateNotificationWithMetadata(metadata: androidx.media3.common.MediaMetadata) {
        val title = metadata.title?.toString() ?: "Reproductor Java"
        val artist = metadata.artist?.toString() ?: "Música activa"
        startForegroundWithStatus(title, artist)
    }

    private var isForegroundStarted = false

    private fun startForegroundWithStatus(title: String, text: String) {
        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        try {
            val notification = notificationBuilder.build()
            val manager = getSystemService(NotificationManager::class.java)
            
            if (isForegroundStarted) {
                // Si ya estamos en primer plano, solo actualizamos para evitar saltos visuales
                manager?.notify(NOTIFICATION_ID, notification)
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                isForegroundStarted = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Service: Notification update fail", e)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession
    
    private val crossfadeCheckRunnable = object : Runnable {
        override fun run() {
            if (player.isPlaying) {
                val duration = player.duration
                val position = player.currentPosition
                val remaining = duration - position

                /* 🚀 NITRO-INJECTION: DESACTIVADO POR ESTABILIDAD (Fase 5 SAFE MODE)
                if (duration > 0 && remaining in 2000L..15000L) {
                   ...
                }
                */

                if (duration > 0 && remaining <= CROSSFADE_MS) {
                    if (currentVolume >= 1.0f) {
                        Log.d(TAG, "Service: 📉 Fade Out proactivo (Safe Mode Monitoring)...")
                        startFadeOut()
                    }
                }
            }
            fadeHandler.postDelayed(this, 1000) // Reducimos frecuencia para bajar uso de CPU
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Service: App quit via Recents. Stopping motor.")
        player.pause()
        player.stop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        fadeHandler.removeCallbacksAndMessages(null)
        player.release()
        mediaSession?.release()
        equalizer?.release()
        dynamicsProcessing?.release()
        super.onDestroy()
    }

    private fun triggerOfflineFallback() {
        mainHandler.post {
            Toast.makeText(applicationContext, "Sin conexión. Cambiando a música guardada...", Toast.LENGTH_LONG).show()
        }

        repository.getMergedOfflineSongs(object : MusicRepository.MusicCallback {
            override fun onSuccess(songs: List<com.musicplayer.model.Song>) {
                if (songs.isNotEmpty()) {
                    mainHandler.post {
                        val items = songs.map { s ->
                            MediaItem.Builder()
                                .setMediaId(s.id)
                                .setUri(Uri.parse(s.url))
                                .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder()
                                    .setTitle(s.title)
                                    .setArtist(s.artist)
                                    .setArtworkUri(Uri.parse(s.thumbnailUrl))
                                    .build())
                                .build()
                        }
                        player.stop()
                        player.setMediaItems(items)
                        player.prepare()
                        player.play()
                    }
                } else {
                    Log.w(TAG, "No hay música offline disponible.")
                    mainHandler.post {
                        Toast.makeText(applicationContext, "No hay música offline guardada.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            override fun onError(e: Exception) {
                Log.e(TAG, "Fallo total: no hay música offline disponible.")
            }
        })
    }
}
