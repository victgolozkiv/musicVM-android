package com.musicplayer.repository

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.musicplayer.db.AppDatabase
import com.musicplayer.db.MusicDao
import com.musicplayer.db.entity.FeedSong
import com.musicplayer.db.entity.PlaybackHistory
import com.musicplayer.db.entity.PlaylistSong
import com.musicplayer.db.entity.SearchHistory
import com.musicplayer.model.LyricLine
import com.musicplayer.model.Song
import com.musicplayer.utils.CacheMetadataManager
import com.musicplayer.utils.LrcParser
import com.musicplayer.utils.MusicCacheManager
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.okhttp.OkHttpDataSource
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MusicRepository private constructor(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val musicDao: MusicDao = AppDatabase.getInstance(context).musicDao()
    private val prefs: SharedPreferences = context.getSharedPreferences("music_cache", Context.MODE_PRIVATE)
    
    val feedSeedInfo = MutableLiveData<String>()
    val activeAudioSessionId = MutableLiveData<Int>(-1)
    val executor: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newFixedThreadPool(4)

    companion object {
        private const val TAG = "DEBUG_PLAYER"
        private val urlCache = ConcurrentHashMap<String, String>()
        private val activeResolutions = ConcurrentHashMap<String, Deferred<String?>>()
        private val bannedDbUris = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        private val cachedMix = Collections.synchronizedList(ArrayList<Song>())
        
        @Volatile
        private var instance: MusicRepository? = null

        // SINGLETON OKHTTP CLIENT - Centralizado para Connection Pooling 🚀
        val okHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectionPool(ConnectionPool(10, 5, java.util.concurrent.TimeUnit.MINUTES))
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }

        @JvmStatic
        fun getInstance(context: Context): MusicRepository {
            return instance ?: synchronized(this) {
                instance ?: MusicRepository(context).also { instance = it }
            }
        }
    }

    init {
        loadCacheFromPrefs()
        preloadStreamUrlCache()
    }

    private fun preloadStreamUrlCache() {
        scope.launch {
            try {
                val feed = musicDao.getFeedSongs()
                feed.forEach { fs ->
                    fs.cachedStreamUrl?.let { urlCache.put(fs.url ?: "", it) }
                }
                val pl = musicDao.getAllPlaylistSongs()
                pl.forEach { ps ->
                    ps.cachedStreamUrl?.let { urlCache.put(ps.url ?: "", it) }
                }
                Log.d(TAG, "Repo: 🧠 Nitro-RAM Engine listo. Cache pre-cargado.")
            } catch (e: Exception) {
                Log.e(TAG, "Error pre-cargando cache", e)
            }
        }
    }

    fun setActiveAudioSessionId(id: Int) {
        activeAudioSessionId.postValue(id)
    }

    fun insertPlaybackHistory(history: PlaybackHistory) {
        scope.launch {
            try {
                musicDao.insertPlaybackHistory(history)
            } catch (e: Exception) {
                Log.e(TAG, "Repo: Error guardando historial", e)
            }
        }
    }

    suspend fun getLastPlayedSync(): PlaybackHistory? = musicDao.getLastPlayed()

    fun clearMemoryCache() {
        urlCache.clear()
        cachedMix.clear()
        prefs.edit().remove("cached_mix").apply()
    }

    fun removeCache(originalUrl: String) {
        urlCache.remove(originalUrl)
        bannedDbUris.add(originalUrl)
        extractVideoId(originalUrl)?.let { songId ->
            bannedDbUris.add(songId)
            scope.launch {
                try {
                    musicDao.updateFeedSongStreamUrl(songId, null, 0)
                    musicDao.updatePlaylistSongStreamUrl(songId, null, 0)
                } catch (ignored: Exception) {}
            }
        }
    }

    interface MusicCallback {
        fun onSuccess(songs: List<Song>)
        fun onError(e: Exception)
    }

    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager? ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val nw = cm.activeNetwork ?: return false
            val actNw = cm.getNetworkCapabilities(nw) ?: return false
            actNw.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    (actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                     actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                     actNw.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                     actNw.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH))
        } else {
            val nwInfo = cm.activeNetworkInfo
            nwInfo != null && nwInfo.isConnected
        }
    }

    fun getMergedOfflineSongs(callback: MusicCallback) {
        scope.launch {
            try {
                val offlineSongs = ArrayList<Song>()
                val seenIds = HashSet<String>()
                val cache = MusicCacheManager.getInstance(context).cache

                // 1. Añadir canciones del Caché Remoto
                val potentialCached = CacheMetadataManager.getAllCachedSongs(context)
                potentialCached.forEach { s ->
                    if (!seenIds.contains(s.id)) {
                        val key = s.url
                        var isCached = false
                        if (cache != null && key != null) {
                            isCached = cache.isCached(key, 0, 1)
                            val streamUrl = s.streamUrl
                            if (!isCached && streamUrl != null) {
                                isCached = cache.isCached(streamUrl, 0, 1)
                            }
                        }
                        if (isCached || potentialCached.isNotEmpty()) {
                            s.isLocal = false
                            offlineSongs.add(s)
                            seenIds.add(s.id)
                        }
                    }
                }

                // 2. Añadir canciones de Descargas Físicas (MediaStore)
                val physical = loadMediaStoreSongs()
                physical.forEach { s ->
                    if (!seenIds.contains(s.id)) {
                        s.isLocal = true
                        offlineSongs.add(s)
                        seenIds.add(s.id)
                    }
                }

                Log.d(TAG, "Repo: Total verified offline songs: ${offlineSongs.size}")
                withContext(Dispatchers.Main) {
                    callback.onSuccess(offlineSongs)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error merging offline songs", e)
                withContext(Dispatchers.Main) {
                    callback.onError(e)
                }
            }
        }
    }

    private fun loadMediaStoreSongs(): List<Song> {
        val list = ArrayList<Song>()
        try {
            val contentResolver = context.contentResolver
            val uri = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                android.provider.MediaStore.Audio.Media._ID,
                android.provider.MediaStore.Audio.Media.TITLE,
                android.provider.MediaStore.Audio.Media.ARTIST,
                android.provider.MediaStore.Audio.Media.DATA,
                android.provider.MediaStore.Audio.Media.DURATION,
                android.provider.MediaStore.Audio.Media.ALBUM_ID
            )
            val selection = "${android.provider.MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${android.provider.MediaStore.Audio.Media.DATA} LIKE '%/NMusic/%'"
            contentResolver.query(uri, projection, selection, null, null).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    val idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.ARTIST)
                    val dataCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATA)
                    val durationCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DURATION)
                    val albumIdCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.ALBUM_ID)
                    do {
                        val idValue = cursor.getLong(idCol)
                        val title = cursor.getString(titleCol)
                        val artist = cursor.getString(artistCol)
                        val data = cursor.getString(dataCol)
                        val duration = cursor.getInt(durationCol)
                        val albumId = cursor.getLong(albumIdCol)
                        if (data != null) {
                            val file = File(data)
                            val sArtworkUri = android.net.Uri.parse("content://media/external/audio/albumart")
                            val thumbUri = android.content.ContentUris.withAppendedId(sArtworkUri, albumId).toString()
                            val song = Song(idValue.toString(), title, artist, android.net.Uri.fromFile(file).toString(), duration.toLong(), thumbUri)
                            song.isLocal = true
                            list.add(song)
                        }
                    } while (cursor.moveToNext())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error MediaStore", e)
        }
        return list
    }

    interface StreamCallback {
        fun onSuccess(streamUrl: String)
        fun onError(e: Exception)
    }

    private fun loadCacheFromPrefs() {
        val jsonStr = prefs.getString("cached_mix", null)
        if (jsonStr != null) {
            try {
                val array = JSONArray(jsonStr)
                val loaded = ArrayList<Song>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val s = Song(
                        obj.getString("id"),
                        obj.getString("title"),
                        obj.getString("artist"),
                        obj.getString("url"),
                        obj.optLong("duration", 0),
                        obj.getString("thumbnailUrl")
                    )
                    loaded.add(s)
                }
                synchronized(cachedMix) {
                    cachedMix.clear()
                    cachedMix.addAll(loaded)
                }
                Log.d(TAG, "Repo: Cargados ${loaded.size} temas DESDE DISCO (Inicio Frío Cero)")
            } catch (e: Exception) {
                Log.e(TAG, "Error cargando caché", e)
            }
        }
    }

    private fun saveCacheToPrefs() {
        scope.launch {
            try {
                val array = JSONArray()
                synchronized(cachedMix) {
                    cachedMix.forEach { s ->
                        val obj = JSONObject()
                        obj.put("id", s.id)
                        obj.put("title", s.title)
                        obj.put("artist", s.artist)
                        obj.put("thumbnailUrl", s.thumbnailUrl)
                        obj.put("url", s.url)
                        obj.put("duration", s.duration)
                        array.put(obj)
                    }
                }
                prefs.edit().putString("cached_mix", array.toString()).apply()
            } catch (e: Exception) {
                Log.e(TAG, "Error guardando caché", e)
            }
        }
    }

    fun getSearchSuggestions(query: String, callback: SuggestionCallback) {
        scope.launch {
            try {
                val service = NewPipe.getService(0)
                // NITRO-LATIN-HYBRID: Hint dual para sugerencias balanceadas (Español/Inglés)
                val hintQuery = if (query.length > 2) "$query musica" else query
                val suggestions = service.suggestionExtractor.suggestionList(hintQuery)
                
                // Filtrar términos no deseados (Fútbol, Noticias, etc.)
                val forbidden = setOf("futbol", "soccer", "noticias", "news", "pelicula", "movie", "full match", "resumen", "goles")
                val filtered = suggestions.filter { sug ->
                    val low = sug.lowercase()
                    forbidden.none { word -> low.contains(word) } &&
                    !low.contains("tutorial") && !low.contains("minecraft")
                }.map { it.replace(" musica", "", ignoreCase = true).trim() } 
                
                withContext(Dispatchers.Main) {
                    callback.onSuccess(filtered.distinct())
                }
            } catch (e: Exception) {
                Log.e("MusicRepository", "Error fetching suggestions", e)
                withContext(Dispatchers.Main) {
                    callback.onSuccess(ArrayList())
                }
            }
        }
    }

    interface SuggestionCallback {
        fun onSuccess(suggestions: List<String>)
    }

    suspend fun getRelatedSongsSync(song: Song): List<Song> = withContext(Dispatchers.IO) {
        try {
            val service = NewPipe.getService(0)
            val streamInfo = org.schabi.newpipe.extractor.stream.StreamInfo.getInfo(service, song.url)
            processNewPipeItems(streamInfo.relatedItems, false)
        } catch (e: Exception) {
            Log.e(TAG, "Repo: Failed to get related songs sync", e)
            emptyList()
        }
    }

    @Throws(Exception::class)
    suspend fun resolveStreamNitro(url: String?): String? {
        if (url.isNullOrBlank()) return null
        
        // NITRO-COALESCING: Evitar requests duplicados en paralelo
        val existingJob = activeResolutions[url]
        if (existingJob != null) {
            Log.d(TAG, "METRIC: 🛡️ Deduplicando extracción para: $url")
            return existingJob.await()
        }

        val deferred = scope.async(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            try {
                // NORMALIZAR URL: Si es un video ID desnudo, convertir a URL completa
                val normalizedUrl = normalizeToFullUrl(url)
                if (normalizedUrl != url) {
                    Log.d(TAG, "URL_NORMALIZE: $url -> $normalizedUrl")
                }

                // 1. RAM CACHE (buscar también con URL normalizada)
                urlCache[normalizedUrl]?.let {
                    Log.d(TAG, "METRIC: ⚡ Cache Hit (RAM) en ${System.currentTimeMillis() - startTime}ms")
                    return@async it
                }

                if (normalizedUrl.startsWith("/") || normalizedUrl.startsWith("file://") || normalizedUrl.startsWith("content://")) return@async normalizedUrl

                // 2. NITRO-TTL DATABASE CACHE
                try {
                    val songId = extractVideoId(normalizedUrl)
                    if (songId != null && !bannedDbUris.contains(songId) && !bannedDbUris.contains(normalizedUrl)) {
                        val cachedFeed = musicDao.getFeedSongBySongId(songId)
                        if (cachedFeed != null && cachedFeed.cachedStreamUrl != null) {
                            if (System.currentTimeMillis() - cachedFeed.cachedAt < 1.5 * 60 * 60 * 1000) {
                                val resolved = cachedFeed.cachedStreamUrl!!
                                urlCache[normalizedUrl] = resolved
                                urlCache[url] = resolved // también cachear con el key original
                                Log.d(TAG, "METRIC: 💽 Cache Hit (DB-Nitro) en ${System.currentTimeMillis() - startTime}ms")
                                return@async resolved
                            }
                        }
                    }
                } catch (ignored: Exception) {}

                // 3. ACTUAL EXTRACTION (NewPipe)
                Log.d(TAG, "METRIC: 🔍 EXTRACTION_START (Network) para: $normalizedUrl")
                val primary = if (normalizedUrl.contains("music.youtube.com")) 1 else 0
                val secondary = if (primary == 1) 0 else 1

                var stream = tryExtractWithService(primary, normalizedUrl)
                if (stream == null) stream = tryExtractWithService(secondary, normalizedUrl)

                // 4. EMERGENCY FALLBACK (YoutubeDL)
                if (stream == null) {
                    try {
                        Log.d(TAG, "METRIC: ⚠️ EMERGENCY_FALLBACK (YoutubeDL)")
                        val request = com.yausername.youtubedl_android.YoutubeDLRequest(normalizedUrl)
                        request.addOption("-g")
                        request.addOption("-f", "bestaudio/best")
                        val response = com.yausername.youtubedl_android.YoutubeDL.getInstance().execute(request, null)
                        stream = response.out.split("\n")[0].trim()
                    } catch (e: Exception) {
                        Log.e(TAG, "METRIC: ❌ EXTRACTION_TOTALLY_FAILED")
                    }
                }

                if (stream != null && stream.startsWith("http")) {
                    saveToNitroCache(normalizedUrl, stream)
                    urlCache[url] = stream // también guardar con key original
                    Log.d(TAG, "METRIC: ✅ TOTAL_EXTRACTION_TIME: ${System.currentTimeMillis() - startTime}ms")
                    stream
                } else {
                    null
                }
            } finally {
                activeResolutions.remove(url)
            }
        }

        activeResolutions[url] = deferred
        return try {
            deferred.await()
        } finally {
            activeResolutions.remove(url)
        }
    }

    @Throws(Exception::class)
    suspend fun searchSongsSync(query: String?): List<Song> = withContext(Dispatchers.IO) {
        if (query.isNullOrBlank()) return@withContext emptyList<Song>()

        musicDao.insertSearch(SearchHistory(keyword = query, timestamp = System.currentTimeMillis()))
        val service = NewPipe.getService(0)
        
        val isLong = query.length > 50
        var musicQuery = query
        if (!isLong && !query.lowercase().contains("music") && !query.lowercase().contains("musica")) {
            // "oficial" es más rápido de extraer que "oficial musica latina" a veces
            musicQuery += " oficial" 
        }
        
        Log.d(TAG, "Repo: 🔍 Búsqueda Nivel 1: $musicQuery")
        var searchInfo = SearchInfo.getInfo(service, service.searchQHFactory.fromQuery(musicQuery))
        var results = processNewPipeItems(searchInfo.relatedItems, isLong).toMutableList()
        
        /* NITRO-DEEP-PAGING (Desactivado temporalmente por incompatibilidad de API)
        if (isLong && searchInfo.nextPage != null) {
            try {
                // ...
            } catch (ignored: Exception) {}
        }
        */

        // NITRO-KEYWORD-FALLBACK: Si hay pocos resultados, tokenizamos inteligentemente
        if (results.size < 12 && isLong) {
            Log.d(TAG, "Repo: 🔍 Búsqueda Nivel 2 (Smart Tokenizer): Pocos resultados. Filtrando stopwords...")
            val stopwords = setOf("para", "con", "del", "las", "los", "por", "una", "donde", "este", "esta", "estos", "estas")
            val words = query.split(" ")
                .map { it.trim().lowercase() }
                .filter { it.length > 3 && !stopwords.contains(it) }
            
            if (words.size > 2) {
                // Probamos con las 4 palabras más "pesadas"
                val shortQuery = words.take(4).joinToString(" ")
                Log.d(TAG, "Repo: 🔍 Re-intentando con keywords pesadas: $shortQuery")
                try {
                    val fallbackInfo = SearchInfo.getInfo(service, service.searchQHFactory.fromQuery(shortQuery))
                    val fallbackResults = processNewPipeItems(fallbackInfo.relatedItems, true)
                    fallbackResults.forEach { fs -> if (results.none { it.id == fs.id }) results.add(fs) }
                } catch (ignored: Exception) {}
            }
        }

        if (results.isEmpty() && !isLong) {
            searchInfo = SearchInfo.getInfo(service, service.searchQHFactory.fromQuery(query ?: ""))
            results = processNewPipeItems(searchInfo.relatedItems, false).toMutableList()
        }
        results
    }

    fun searchSongs(query: String?, callback: MusicCallback) {
        if (query.isNullOrBlank()) {
            callback.onSuccess(emptyList())
            return
        }

        scope.launch {
            try {
                val results = searchSongsSync(query)
                withContext(Dispatchers.Main) {
                    callback.onSuccess(results)
                }
                
                // NITRO-BOOST: Pre-resolver en segundo plano los 3 primeros resultados
                if (results.isNotEmpty()) {
                    results.take(6).forEach { song -> // Aumentar a 6 para mayor cobertura
                        scope.launch(Dispatchers.IO) {
                            try {
                                Log.d(TAG, "Nitro-Boost: Pre-resolviendo búsqueda (Parallel): ${song.title}")
                                resolveStreamNitro(song.url)?.let { streamUrl ->
                                    song.streamUrl = streamUrl
                                    // ⚡ NITRO-INJECT: Iniciar pre-cache de los primeros 512KB inmediatamente
                                    nitroPreCache(streamUrl)
                                }
                            } catch (ignored: Exception) {}
                        }
                    }
                }
                
                // Búsquedas profundas en segundo plano
                if (results.isNotEmpty()) {
                    scope.launch {
                        try {
                            val service = NewPipe.getService(0)
                            SearchInfo.getInfo(service, service.searchQHFactory.fromQuery(query))
                        } catch (ignored: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Repo Expert: Error en searchSongs", e)
                withContext(Dispatchers.Main) {
                    callback.onError(e)
                }
            }
        }
    }

    fun loadSongs(callback: MusicCallback) {
        getPersonalizedMix(callback)
    }

    fun getPersonalizedMix(callback: MusicCallback) {
        scope.launch {
            try {
                val dbSongs = musicDao.getFeedSongs()
                val lastUpdate = prefs.getLong("last_feed_update", 0)
                val now = System.currentTimeMillis()
                
                val isStale = (now - lastUpdate) > (4 * 60 * 60 * 1000)
                
                if (!dbSongs.isNullOrEmpty()) {
                    val songs = dbSongs.map { fs ->
                        Song(
                            fs.songId,
                            fs.title ?: "Unknown",
                            fs.artist ?: "Unknown Artist",
                            fs.url ?: "",
                            fs.duration,
                            fs.thumbnailUrl ?: ""
                        ).apply {
                            // NITRO-TTL: Solo usar el stream cacheado si tiene menos de 1.5 horas
                            if (fs.cachedStreamUrl != null && (now - fs.cachedAt < 1.5 * 60 * 60 * 1000)) {
                                streamUrl = fs.cachedStreamUrl
                            }
                            isHeader = fs.isHeader
                        }
                    }
                    withContext(Dispatchers.Main) {
                        callback.onSuccess(songs)
                    }
                    
                    if (isStale) {
                        Log.d(TAG, "Repo Nuclear: El feed tiene más de 4 horas. Actualizando en segundo plano...")
                        refreshFeedBuffer(null)
                    }
                } else {
                    Log.d(TAG, "Repo Nuclear: Buffer vacío. Iniciando Cold Start...")
                    refreshFeedBuffer(callback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error leyendo buffer persistente", e)
                refreshFeedBuffer(callback)
            }
        }
    }

    fun forceRefreshFeed(callback: MusicCallback) {
        scope.launch {
            try {
                Log.d(TAG, "Repo: Forzando limpieza manual del feed para nueva semilla...")
                musicDao.clearFeed()
                refreshFeedBuffer(callback)
            } catch (e: Exception) {
                Log.e(TAG, "Error forzando limpieza", e)
                withContext(Dispatchers.Main) {
                    callback.onError(e)
                }
            }
        }
    }

    fun refreshFeedBuffer(liveCallback: MusicCallback?) {
        if (!isNetworkAvailable()) {
            liveCallback?.onError(Exception("Sin red para refrescar buffer"))
            return
        }
        scope.launch {
            try {
                Log.d(TAG, "Repo Nuclear: ☢️ Refrescando Buffer con IA de Búsqueda y Descargas...")
                
                val playbackSeeds = musicDao.getRecentHistory().let {
                    if (it.size > 3) it.subList(0, 3) else it
                }
                
                val searchKeywords = musicDao.getTopKeywords().let {
                    if (it.size > 3) it.subList(0, 3) else it
                }
                
                val finalFeed = Collections.synchronizedList(ArrayList<FeedSong>())
                val seenIds = Collections.synchronizedSet(HashSet<String>())
                
                val tasks = mutableListOf<Deferred<Unit>>()
                
                searchKeywords?.forEach { keyword ->
                    tasks.add(async {
                        try {
                            val results = searchSongsSync(keyword)
                            if (results.isNotEmpty()) {
                                fetchRelatedForSeed(results[0], finalFeed, seenIds, "Por tus búsquedas: $keyword")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error en semilla de búsqueda: $keyword")
                        }
                        Unit
                    })
                }
                
                playbackSeeds?.forEach { hist ->
                    tasks.add(async {
                        val seedSong = Song(
                            hist.songId,
                            hist.title ?: "Unknown",
                            hist.artist ?: "Unknown Artist",
                            hist.url ?: "",
                            hist.duration,
                            hist.thumbnailUrl ?: ""
                        )
                        fetchRelatedForSeed(seedSong, finalFeed, seenIds, "Basado en tu música: ${seedSong.title}")
                    })
                }
                
                if (tasks.isEmpty()) {
                    Log.d(TAG, "Repo: Sin historial. Usando Trending...")
                    refreshTrendingBuffer(liveCallback)
                    return@launch
                }

                tasks.awaitAll()
                prefs.edit().putLong("last_feed_update", System.currentTimeMillis()).apply()
                persistAndDispatchFeed(finalFeed, liveCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Error en refreshFeedBuffer", e)
                withContext(Dispatchers.Main) {
                    liveCallback?.onError(e)
                }
            }
        }
    }

    private suspend fun fetchRelatedForSeed(seed: Song, finalFeed: MutableList<FeedSong>, seenIds: MutableSet<String>, sectionLabel: String) {
        try {
            val service = NewPipe.getService(0)
            val extractor = service.getStreamExtractor(seed.url)
            extractor.fetchPage()
            val results = processNewPipeItems(extractor.relatedItems?.items ?: emptyList())

            if (results.isNotEmpty()) {
                var count = 0
                for (s in results) {
                    if (count >= 12) break // Aumentado de 8 a 12 para rrellenar más fácil
                    if (!seenIds.contains(s.id)) {
                        seenIds.add(s.id)
                        val feedSong = FeedSong(
                            songId = s.id,
                            title = s.title,
                            artist = s.artist,
                            url = s.url,
                            duration = s.duration,
                            thumbnailUrl = s.thumbnailUrl,
                            seedKeyword = "Recomendado",
                            createdAt = System.currentTimeMillis()
                        )
                        count++
                        finalFeed.add(feedSong)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo relacionados para semilla: ${seed.title}")
        }
    }

    private fun persistAndDispatchFeed(feed: List<FeedSong>?, callback: MusicCallback?) {
        if (feed.isNullOrEmpty()) {
            scope.launch(Dispatchers.Main) {
                callback?.onSuccess(emptyList())
            }
            return
        }
        scope.launch {
            try {
                musicDao.clearFeed()
                musicDao.insertFeedSongs(feed)
                Log.d(TAG, "Repo Nuclear: ✅ Buffer Seccionado repoblado con ${feed.size} items.")
                
                if (callback != null) {
                    val songs = feed.map { fs ->
                        Song(
                            fs.songId,
                            fs.title ?: "Unknown",
                            fs.artist ?: "Unknown Artist",
                            fs.url ?: "",
                            fs.duration,
                            fs.thumbnailUrl ?: ""
                        ).apply {
                            isHeader = fs.isHeader
                        }
                    }
                    withContext(Dispatchers.Main) {
                        callback.onSuccess(songs)
                    }
                    // NITRO-BOOST: Pre-resolver los primeros temas del feed para carga instantánea
                    preResolveSearchResults(songs)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error persistiendo feed", e)
                withContext(Dispatchers.Main) {
                    callback?.onError(e)
                }
            }
        }
    }

    private fun refreshTrendingBuffer(callback: MusicCallback?) {
        scope.launch {
            try {
                val service = NewPipe.getService(0)
                val info = SearchInfo.getInfo(service, service.searchQHFactory.fromQuery("Top Music Hits 2024"))
                val results = processNewPipeItems(info.relatedItems)
                
                val trendingFeed = results.take(20).map { s ->
                    FeedSong(
                        songId = s.id,
                        title = s.title,
                        artist = s.artist,
                        url = s.url,
                        duration = s.duration,
                        thumbnailUrl = s.thumbnailUrl,
                        seedKeyword = "trending",
                        createdAt = System.currentTimeMillis()
                    )
                }
                persistAndDispatchFeed(trendingFeed, callback)
            } catch (e: Exception) {
                Log.e(TAG, "Fallo total en buffer", e)
                withContext(Dispatchers.Main) {
                    callback?.onError(e)
                }
            }
        }
    }

    fun getDailyMixes(callback: MusicCallback) {
        if (cachedMix.isNotEmpty()) {
            callback.onSuccess(ArrayList(cachedMix))
            return
        }
        searchSongs("Top Music Mix 2024", callback)
    }

    private fun processNewPipeItems(items: List<InfoItem>, isExtendedSearch: Boolean = false): List<Song> {
        val songs = ArrayList<Song>()
        for (item in items) {
            if (item is StreamInfoItem) {
                val duration = item.duration
                val title = item.name.lowercase()
                val uploader = item.uploaderName.lowercase()
                
                // Si es búsqueda extendida, somos más tolerantes con la duración
                if (!isExtendedSearch) {
                    if (duration < 60 || duration > 1200) continue 
                } else {
                    if (duration < 20 || duration > 3600) continue
                }
                
                val isNonMusic = title.contains("vlog") || title.contains("blog") || title.contains("tutorial") || 
                               title.contains("news") || title.contains("noticias") || title.contains("resumen") ||
                               title.contains("travel") || title.contains("viaje") || title.contains("hotel") ||
                               title.contains("resort") || title.contains("beach") || title.contains("guia") ||
                               title.contains("walking") || title.contains("tour") || title.contains("reaction") ||
                               title.contains("reaccion") || title.contains("podcast") || title.contains("interview") ||
                               title.contains("entrevista") || title.contains("unboxing") || title.contains("review") ||
                               title.contains("critica") || title.contains("humor") || title.contains("comedy") ||
                               title.contains("comedia") || title.contains("chismes") || title.contains("gaming") || 
                               title.contains("gameplay") || title.contains("trailer") || title.contains("futbol") || 
                               title.contains("soccer") || title.contains("gol") || title.contains("match") || 
                               title.contains("final") || title.contains("league") || title.contains("highlights") || 
                               title.contains("nba") || title.contains("nfl") || title.contains("mlb") || 
                               title.contains("deportes") || title.contains("sports")
                
                // En búsqueda extendida ignoramos el filtro de "no-música" si el título es largo y parece relevante
                if (isNonMusic && !isExtendedSearch) continue

                val isOfficialMusic = uploader.contains("topic") || uploader.contains("vevo") || 
                                    uploader.contains("official") || title.contains("official") || 
                                    title.contains("audio") || title.contains("lyrics") || 
                                    title.contains("video") || title.contains("full album") ||
                                    title.contains("remix") || title.contains("cover") ||
                                    title.contains("tema") || title.contains("cancion") ||
                                    title.contains("song") || title.contains("hit") ||
                                    title.contains("single") || title.contains("album") ||
                                    title.contains("mix")
                
                // Si no es música oficial y es largo, descartamos... EXCEPTO en búsqueda extendida
                if (!isOfficialMusic && duration > 500 && !isExtendedSearch) continue 

                val id = extractVideoId(item.url) ?: ""
                var thumb = ""
                
                if (item.thumbnails != null && item.thumbnails.isNotEmpty()) {
                    thumb = item.thumbnails[item.thumbnails.size - 1].url
                }
                
                if (item.url.contains("youtu")) {
                    if (thumb.isEmpty()) {
                        thumb = "https://img.youtube.com/vi/$id/hqdefault.jpg"
                    }
                }
                
                if (thumb.contains("googleusercontent.com") && !thumb.contains("=")) {
                    thumb += "=w1024-h1024-l100-rj"
                }
                
                songs.add(Song(id, item.name, item.uploaderName, item.url, duration.toInt().toLong(), thumb))
            }
            if (songs.size >= 150) break 
        }
        return songs
    }

    fun getStreamUrl(url: String?, callback: StreamCallback) {
        if (url == null) {
            callback.onError(Exception("URL es nula"))
            return
        }

        if (url.startsWith("/") || url.startsWith("file://") || url.startsWith("content://")) {
            callback.onSuccess(url)
            return
        }

        scope.launch {
            try {
                val cached = resolveStreamNitro(url)
                withContext(Dispatchers.Main) {
                    if (cached != null) {
                        callback.onSuccess(cached)
                    } else {
                        callback.onError(Exception("Fallo en extracción nuclear"))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback.onError(e)
                }
            }
        }
    }

    /**
     * Versión bloqueante para interoperabilidad con Java.
     * Debe llamarse desde un hilo de fondo.
     */
    fun getStreamUrlSyncBlocking(url: String?): String? = runBlocking {
        resolveStreamNitro(url)
    }



    private fun saveToNitroCache(originalUrl: String, streamUrl: String) {
        urlCache.put(originalUrl, streamUrl)
        extractVideoId(originalUrl)?.let { songId ->
            scope.launch {
                try {
                    musicDao.updateFeedSongStreamUrl(songId, streamUrl, System.currentTimeMillis())
                    musicDao.updatePlaylistSongStreamUrl(songId, streamUrl, System.currentTimeMillis())
                } catch (ignored: Exception) {}
            }
        }
    }

    private fun tryExtractWithService(serviceId: Int, url: String): String? {
        try {
            val service = NewPipe.getService(serviceId)
            val extractor = service.getStreamExtractor(url)
            extractor.fetchPage()
            
            val audioStreams = extractor.audioStreams
            if (!audioStreams.isNullOrEmpty()) {
                val stream = audioStreams[0]
                var streamUrl = stream.url
                if (streamUrl.isNullOrEmpty()) {
                    streamUrl = stream.content
                }
                
                if (streamUrl != null && streamUrl.startsWith("http")) {
                    Log.d(TAG, "Repo: Extracción exitosa via Motor $serviceId para $url")
                    return streamUrl
                }
            }
            Log.w(TAG, "Repo: Motor $serviceId no devolvió streams de audio para $url")
        } catch (e: Exception) {
            Log.e(TAG, "Repo: Error en Motor $serviceId extrayendo $url: ${e.message}")
        }
        return null
    }

    /**
     * NITRO PRE-CACHE: Descarga los primeros 512KB de un stream en segundo plano.
     */
    fun nitroPreCache(streamUrl: String?) {
        if (streamUrl == null || !streamUrl.startsWith("http")) return
        
        scope.launch {
            try {
                val cache = MusicCacheManager.getInstance(context).cache ?: return@launch
                val upstream = OkHttpDataSource.Factory(okHttpClient).createDataSource()
                val cacheDataSource = androidx.media3.datasource.cache.CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory { upstream }
                    .createDataSource()
                
                val dataSpec = DataSpec(android.net.Uri.parse(streamUrl), 0, 512 * 1024) // 512 KB
                
                Log.d(TAG, "Repo: ⚡ Nitro-Pre-Caching 512KB para: $streamUrl")
                val writer = CacheWriter(cacheDataSource, dataSpec, null) { _, _, _ -> }
                writer.cache()
                Log.d(TAG, "Repo: ✅ Nitro-Pre-Cache completado.")
            } catch (e: Exception) {
                Log.w(TAG, "Repo: Nitro-Pre-Cache falló (no crítico): ${e.message}")
            }
        }
    }

    fun getRelatedSongs(song: Song?, callback: MusicCallback) {
        if (song == null || song.url == null) {
            getPersonalizedMix(callback)
            return
        }
        scope.launch {
            try {
                val artistName = extractRealArtist(song)
                val genreHint = detectGenreHint(song)
                Log.d(TAG, "Repo: Smart Radio - Artista: $artistName | Género: $genreHint")
                
                val seenIds = HashSet<String>()
                val seenTitles = HashSet<String>()
                seenIds.add(song.id) 
                seenTitles.add(normalizeTitle(song.title))

                val finalQueue = ArrayList<Song>()

                // --- DESCUBRIMIENTO 1: Relacionados Directos ---
                var youtubeRelated = ArrayList<Song>()
                try {
                    val videoId = song.id
                    val radioUrl = if (videoId != null && videoId.isNotEmpty()) 
                        "https://www.youtube.com/watch?v=$videoId" 
                    else song.url

                    Log.d(TAG, "Repo: Solicitando Radio para $radioUrl")
                    val currentInfo = org.schabi.newpipe.extractor.stream.StreamInfo.getInfo(NewPipe.getService(1), radioUrl)
                    youtubeRelated = ArrayList(processNewPipeItems(currentInfo.relatedItems))
                } catch (e: Exception) {
                    Log.w(TAG, "Repo Expert: Falló Radio en YT Music, intentando Standard...")
                    try {
                        val currentInfo = org.schabi.newpipe.extractor.stream.StreamInfo.getInfo(NewPipe.getService(0), song.url)
                        youtubeRelated = ArrayList(processNewPipeItems(currentInfo.relatedItems))
                    } catch (fatal: Exception) {}
                }
                
                for (s in youtubeRelated) {
                    if (seenIds.contains(s.id)) continue
                    val normTitle = normalizeTitle(s.title)
                    if (seenTitles.contains(normTitle)) continue

                    if (isArtistMatch(artistName, s.artist, s.title) || isGenreMatch(genreHint, s.title)) {
                        seenIds.add(s.id)
                        seenTitles.add(normTitle)
                        finalQueue.add(s)
                    }
                }

                // --- DESCUBRIMIENTO 2: Catálogo Extendido del Artista ---
                if (artistName.length >= 2 && finalQueue.size < 15) {
                    try {
                        val srv = NewPipe.getService(1)
                        val query = "$artistName official audio"
                        val sInfo = SearchInfo.getInfo(srv, srv.searchQHFactory.fromQuery(query))
                        val artistSongs = processNewPipeItems(sInfo.relatedItems)
                        for (s in artistSongs) {
                            if (finalQueue.size >= 30) break
                            val nt = normalizeTitle(s.title)
                            if (!seenIds.contains(s.id) && !seenTitles.contains(nt) && isArtistMatch(artistName, s.artist, s.title)) {
                                seenIds.add(s.id)
                                seenTitles.add(nt)
                                finalQueue.add(s)
                            }
                        }
                    } catch (e1: Exception) {
                        Log.w(TAG, "Repo: Falló búsqueda de refuerzo para artista: $artistName")
                    }
                }

                // --- DESCUBRIMIENTO 3: Refuerzo por Género (Si aún hay pocos) ---
                if (genreHint.isNotEmpty() && finalQueue.size < 10) {
                    try {
                        val srv = NewPipe.getService(1)
                        val query = "$genreHint hits official"
                        val sInfo = SearchInfo.getInfo(srv, srv.searchQHFactory.fromQuery(query))
                        val genreSongs = processNewPipeItems(sInfo.relatedItems)
                        for (s in genreSongs) {
                            if (finalQueue.size >= 25) break
                            val nt = normalizeTitle(s.title)
                            if (!seenIds.contains(s.id) && !seenTitles.contains(nt) && isGenreMatch(genreHint, s.title)) {
                                seenIds.add(s.id)
                                seenTitles.add(nt)
                                finalQueue.add(s)
                            }
                        }
                    } catch (ignored: Exception) {}
                }

                // --- DESCUBRIMIENTO 4: Discovery FALLBACK (Si llegamos aquí con casi nada) ---
                if (finalQueue.size < 5 && youtubeRelated.isNotEmpty()) {
                    Log.d(TAG, "Repo: Smart Radio entrando en modo Discovery (Fallback)")
                    for (s in youtubeRelated) {
                        if (seenIds.contains(s.id)) continue
                        seenIds.add(s.id)
                        finalQueue.add(s)
                        if (finalQueue.size >= 12) break
                    }
                }

                withContext(Dispatchers.Main) {
                    if (finalQueue.isEmpty()) {
                        Log.w(TAG, "Repo: Radio totalmente vacía. Usando Mix Diario.")
                        getDailyMixes(callback)
                    } else {
                        Log.d(TAG, "Repo Smart Radio: ✅ Descubiertos ${finalQueue.size} temas (Artist: $artistName, Genre: $genreHint).")
                        callback.onSuccess(finalQueue)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Repo: Error crítico en Smart Radio", e)
                withContext(Dispatchers.Main) {
                    getDailyMixes(callback)
                }
            }
        }
    }

    fun getArtistFullMix(artistName: String, callback: MusicCallback) {
        scope.launch {
            try {
                var service: StreamingService
                var info: SearchInfo
                try {
                    service = NewPipe.getService(1)
                    val query = "$artistName official top songs"
                    info = SearchInfo.getInfo(service, service.searchQHFactory.fromQuery(query))
                } catch (e: Exception) {
                    service = NewPipe.getService(0)
                    val query = "$artistName auto-generated official audio"
                    info = SearchInfo.getInfo(service, service.searchQHFactory.fromQuery(query))
                }
                
                val songs = processNewPipeItems(info.relatedItems)
                val finalArtistSongs = songs.filter { isArtistMatch(artistName, it.artist, it.title) }.toMutableList()
                
                if (finalArtistSongs.isEmpty() && songs.isNotEmpty()) {
                    finalArtistSongs.addAll(songs.take(10))
                }
                
                withContext(Dispatchers.Main) {
                    callback.onSuccess(finalArtistSongs)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching artist full mix", e)
                withContext(Dispatchers.Main) {
                    callback.onError(e)
                }
            }
        }
    }

    /**
     * Converts bare YouTube video IDs (e.g. "Pj5Wkb7O1tg") to a full watch URL.
     * Also normalizes music.youtube.com -> youtube.com for NewPipe compatibility.
     */
    private fun normalizeToFullUrl(url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://") ||
            url.startsWith("/") || url.startsWith("file://") || url.startsWith("content://")) {
            // Already a full URL – just normalize music.youtube.com if needed
            return url
        }
        // Bare YouTube video ID: 11 chars, alphanumeric + dash + underscore
        val bareIdRegex = Regex("^[A-Za-z0-9_-]{11}\$")
        if (bareIdRegex.matches(url)) {
            val fullUrl = "https://www.youtube.com/watch?v=$url"
            Log.d(TAG, "URL_NORMALIZE: Bare ID '$url' -> '$fullUrl'")
            return fullUrl
        }
        // Fallback: return as-is (SoundCloud, local, etc.)
        return url
    }

    private fun extractVideoId(url: String?): String? {
        if (url == null || url.isBlank()) return "unknown_id_${System.currentTimeMillis()}"
        return try {
            when {
                url.contains("v=") -> url.split("v=")[1].split("&")[0]
                url.contains("youtu.be/") -> url.split("youtu.be/")[1].split("?")[0]
                url.contains("/shorts/") -> url.split("/shorts/")[1].split("?")[0]
                else -> {
                    val hashId = "url_${Math.abs(url.hashCode())}"
                    Log.w(TAG, "Repo: No se pudo extraer ID tradicional de $url. Usando fallback ID: $hashId")
                    hashId
                }
            }
        } catch (e: Exception) {
            "error_id_${Math.abs(url.hashCode())}"
        }
    }

    private fun extractRealArtist(song: Song?): String {
        if (song == null) return ""
        val title = song.title
        val uploader = song.artist
        val uploaderClean = uploader.replace("(?i)\\- topic|vevo|oficial|official".toRegex(), "").trim()
        if (title.contains(" - ")) {
            val parts = title.split(" - ")
            val part1 = parts[0].trim()
            val part2 = parts[1].trim()
            if (part1.lowercase().contains(uploaderClean.lowercase()) || 
                uploaderClean.lowercase().contains(part1.lowercase())) {
                return part1
            }
            if (part2.lowercase().contains(uploaderClean.lowercase()) || 
                uploaderClean.lowercase().contains(part2.lowercase())) {
                return part2
            }
            if (uploader.lowercase().contains("topic") || uploader.lowercase().contains("vevo")) {
                return part1
            }
        }
        return uploaderClean
    }

    private fun normalizeTitle(title: String?): String {
        if (title == null) return ""
        return title.lowercase()
            .replace("(?i)\\(official video\\)".toRegex(), "")
            .replace("(?i)\\[official video\\]".toRegex(), "")
            .replace("(?i)\\(lyrics\\)".toRegex(), "")
            .replace("(?i)\\[lyrics\\]".toRegex(), "")
            .replace("(?i)\\(lyric video\\)".toRegex(), "")
            .replace("(?i)\\(oficial\\)".toRegex(), "")
            .replace("(?i)oficial".toRegex(), "")
            .replace("(?i)video".toRegex(), "")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    interface LyricsCallback {
        fun onSuccess(lyrics: @JvmSuppressWildcards List<LyricLine>)
        fun onError(e: Exception)
    }

    fun getLyrics(song: Song, callback: LyricsCallback) {
        if (song.isLocal) {
            callback.onSuccess(emptyList())
            return
        }

        scope.launch {
            try {
                val artist = song.artist
                val title = normalizeTitle(song.title)
                val query = "$title $artist"
                
                val url = "https://lrclib.net/api/search?q=${android.net.Uri.encode(query)}"

                Log.d(TAG, "Repo: Fetching lyrics from $url")
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "MusicVM/1.0 (https://github.com/victgolozkiv/musicVM-android)")
                    .build()
                    
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful && response.body != null) {
                        val jsonStr = response.body!!.string()
                        val results = JSONArray(jsonStr)
                        if (results.length() > 0) {
                            var json: JSONObject? = null
                            for (i in 0 until results.length()) {
                                val obj = results.getJSONObject(i)
                                if (obj.optString("syncedLyrics", "").isNotEmpty()) {
                                    json = obj
                                    break
                                }
                            }
                            if (json == null) {
                                json = results.getJSONObject(0)
                            }
                            
                            val syncedLyrics = json!!.optString("syncedLyrics", "")
                            if (syncedLyrics.isNotEmpty()) {
                                val parsed = LrcParser.parseLrc(syncedLyrics)
                                val converted = parsed.map { LyricLine(it.timeMs, it.text) }
                                withContext(Dispatchers.Main) { callback.onSuccess(converted) }
                            } else {
                                val plain = json.optString("plainLyrics", "")
                                val lines = if (plain.isNotEmpty()) plain.split("\n").map { LyricLine(0, it) } else emptyList()
                                withContext(Dispatchers.Main) { callback.onSuccess(lines) }
                            }
                        } else {
                            withContext(Dispatchers.Main) { callback.onSuccess(emptyList()) }
                        }
                    } else {
                        withContext(Dispatchers.Main) { callback.onSuccess(emptyList()) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Repo: Lyrics fetch fallido", e)
                withContext(Dispatchers.Main) { callback.onError(e) }
            }
        }
    }

    private fun isArtistMatch(targetArtist: String?, uploader: String, title: String): Boolean {
        if (targetArtist == null || targetArtist.isEmpty() || targetArtist.length < 2) return true
        val target = targetArtist.lowercase().trim()
        val uploaderLower = uploader.lowercase()
        val titleLower = title.lowercase()
        if (uploaderLower.contains(target) || titleLower.contains(target)) return true
        val words = target.split("\\s+".toRegex()).toTypedArray()
        var matches = 0
        for (w in words) {
            if (w.length < 2) continue
            if (uploaderLower.contains(w) || titleLower.contains(w)) matches++
        }
        return matches.toDouble() / Math.max(1, words.size).toDouble() >= 0.3
    }

    private fun detectGenreHint(song: Song): String {
        val content = (song.title + " " + song.artist).lowercase()
        return when {
            content.contains("rap") || content.contains("hip hop") || content.contains("trap") || content.contains("canserbero") || content.contains("nach") || content.contains("residente") -> "rap hiphop"
            content.contains("rock") || content.contains("metal") || content.contains("indie") -> "rock music"
            content.contains("reggaeton") || content.contains("urbano") || content.contains("bad bunny") -> "reggaeton"
            content.contains("pop") || content.contains("hits") -> "pop music"
            content.contains("regional") || content.contains("mexicano") || content.contains("corridos") || content.contains("banda") -> "mexicano"
            content.contains("lofi") || content.contains("chill") || content.contains("estudio") -> "lofi chill"
            else -> ""
        }
    }

    private fun isGenreMatch(genreHint: String?, candidateTitle: String): Boolean {
        if (genreHint.isNullOrEmpty()) return true 
        val title = candidateTitle.lowercase()
        val keywords = genreHint.split("\\s+".toRegex()).toTypedArray()
        for (k in keywords) {
            if (title.contains(k)) return true
        }
        if (genreHint.contains("rap") && title.contains("rock")) return false
        if (genreHint.contains("rock") && (title.contains("reggaeton") || title.contains("trap"))) return false
        return true 
    }
    
    fun preResolveSearchResults(songs: List<Song>?) {
        if (songs.isNullOrEmpty() || !isNetworkAvailable()) return
        
        val limit = Math.min(songs.size, 3)
        for (i in 0 until limit) {
            val song = songs[i]
            if (song.streamUrl == null) {
                scope.launch {
                    resolveStreamNitro(song.url)?.let { streamUrl ->
                        song.streamUrl = streamUrl
                        Log.d(TAG, "Nitro: Pre-resolución de búsqueda exitosa para ${song.title}")
                    }
                }
            }
        }
    }
}
