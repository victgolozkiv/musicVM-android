package com.musicplayer.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.musicplayer.db.AppDatabase
import com.musicplayer.model.LyricLine
import com.musicplayer.model.Song
import com.musicplayer.repository.MusicRepository
import com.musicplayer.db.entity.Playlist
import com.musicplayer.db.entity.PlaylistSong
import com.musicplayer.manager.CastManager
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "DEBUG_PLAYER"
    }

    private val repository = MusicRepository.getInstance(application)
    private val musicDao = AppDatabase.getInstance(application).musicDao()
    private val castManager = CastManager.getInstance(application)

    fun clearSuggestions() {
        _suggestions.postValue(emptyList())
    }

    private val _songs = MutableLiveData<List<Song>>(ArrayList())
    val songs: LiveData<List<Song>> = _songs

    private val _searchResults = MutableLiveData<List<Song>>(ArrayList())
    val searchResults: LiveData<List<Song>> = _searchResults

    private val _pendingSearchQuery = MutableLiveData<String?>()
    val pendingSearchQuery: LiveData<String?> = _pendingSearchQuery

    private val _playRequest = MutableLiveData<PlaylistPlayRequest?>()
    val playRequest: LiveData<PlaylistPlayRequest?> = _playRequest

    private val _downloadUrl = MutableLiveData<String?>()
    val downloadUrl: LiveData<String?> = _downloadUrl

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _selectedSong = MutableLiveData<Song?>()
    val selectedSong: LiveData<Song?> = _selectedSong

    private val _playlists = MutableLiveData<List<Playlist>>(ArrayList())
    val playlists: LiveData<List<Playlist>> = _playlists

    private val _playlistSongs = MutableLiveData<List<Song>>(ArrayList())
    val playlistSongs: LiveData<List<Song>> = _playlistSongs

    private val _suggestions = MutableLiveData<List<String>>(ArrayList())
    val suggestions: LiveData<List<String>> = _suggestions

    private val _rockMix = MutableLiveData<List<Song>>(ArrayList())
    val rockMix: LiveData<List<Song>> = _rockMix

    private val _popMix = MutableLiveData<List<Song>>(ArrayList())
    val popMix: LiveData<List<Song>> = _popMix

    private val _latinMix = MutableLiveData<List<Song>>(ArrayList())
    val latinMix: LiveData<List<Song>> = _latinMix

    private val _currentLyrics = MutableLiveData<List<LyricLine>>(ArrayList())
    val currentLyrics: LiveData<List<LyricLine>> = _currentLyrics

    private val _activeLyricLine = MutableLiveData<LyricLine?>()
    val activeLyricLine: LiveData<LyricLine?> = _activeLyricLine

    private val _castDevices = MutableLiveData<List<CastManager.CastDevice>>()
    val castDevices: LiveData<List<CastManager.CastDevice>> = _castDevices

    private val _isOnline = MutableLiveData(false)
    @get:JvmName("getIsOnline")
    val isOnline: LiveData<Boolean> = _isOnline

    private val _isLoading = MutableLiveData(false)
    @get:JvmName("getIsLoading")
    val isLoading: LiveData<Boolean> = _isLoading

    private val isRefreshing = AtomicBoolean(false)
    private var lastLoadTime: Long = 0
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    private val _feedTitle = MutableLiveData("Para ti")
    val feedTitle: LiveData<String> = _feedTitle

    var selectedSongForDownload: Song? = null
        private set

    private var currentPlaylist = ArrayList<Song>()
    private var currentIndex = -1

    init {
        castManager.discoveredDevices.observeForever { _castDevices.postValue(it) }
        setupNetworkMonitoring(application)
    }

    private fun setupNetworkMonitoring(app: Application) {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
        if (cm != null) {
            _isOnline.postValue(repository.isNetworkAvailable())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        val currentOnline = _isOnline.value
                        if (hasInternet && (currentOnline == null || !currentOnline)) {
                            Log.d(TAG, "Network: Internet RE-CONECTADO.")
                            _isOnline.postValue(true)
                            _feedTitle.postValue("Para ti")
                            if (System.currentTimeMillis() - lastLoadTime > 5000) loadSongs()
                        } else if (!hasInternet && (currentOnline == null || currentOnline)) {
                            Log.d(TAG, "Network: Internet PERDIDO (Capabilities).")
                            _isOnline.postValue(false)
                            _feedTitle.postValue("Para ti (OFFLINE)")
                            loadSongs()
                        }
                    }

                    override fun onLost(network: Network) {
                        val currentOnline = _isOnline.value
                        if (currentOnline == null || currentOnline) {
                            Log.d(TAG, "Network: Internet PERDIDO.")
                            _isOnline.postValue(false)
                            _feedTitle.postValue("Para ti (OFFLINE)")
                            loadSongs()
                        }
                    }
                })
            }
        }
    }

    class PlaylistPlayRequest(@JvmField val playlist: List<Song>, @JvmField val index: Int)

    fun startDeviceScan() = castManager.startScan()
    fun getFeedSeedInfo(): LiveData<String> = repository.feedSeedInfo
    fun getAudioSessionId(): LiveData<Int> = repository.activeAudioSessionId

    fun loadSongs() {
        val online = repository.isNetworkAvailable()
        if (isRefreshing.get() && online == _isOnline.value) {
            Log.d(TAG, "ViewModel: Ya hay una carga en curso del mismo tipo. Ignorando.")
            return
        }

        isRefreshing.set(true)
        lastLoadTime = System.currentTimeMillis()
        _isLoading.postValue(true)

        Handler(Looper.getMainLooper()).postDelayed({
            if (isRefreshing.get()) {
                Log.w(TAG, "Failsafe: Forzando fin de carga por timeout.")
                isRefreshing.set(false)
                _isLoading.postValue(false)
            }
        }, 12000)

        if (!online) {
            _feedTitle.postValue("Para ti")
            _isLoading.postValue(false)
            isRefreshing.set(false)
            return
        }

        repository.loadSongs(object : MusicRepository.MusicCallback {
            override fun onSuccess(songs: List<Song>) {
                _songs.postValue(songs)
                _isLoading.postValue(false)
                isRefreshing.set(false)
                
                // ⚡ ULTRA-LATENCY: Pre-calentar el primer tema inmediatamente
                if (songs.isNotEmpty()) {
                    val firstSong = songs.find { !it.isHeader } ?: songs[0]
                    prePrepareFirstSong(firstSong)
                }
            }

            override fun onError(e: Exception) {
                _isLoading.postValue(false)
                isRefreshing.set(false)
            }
        })
    }

    fun forceRefreshFeed() {
        if (!repository.isNetworkAvailable()) {
            loadSongs()
            return
        }

        if (!isRefreshing.compareAndSet(false, true)) return

        _isLoading.postValue(true)
        repository.forceRefreshFeed(object : MusicRepository.MusicCallback {
            override fun onSuccess(songs: List<Song>) {
                _songs.postValue(songs)
                _isLoading.postValue(false)
                isRefreshing.set(false)
                lastLoadTime = System.currentTimeMillis()
            }

            override fun onError(e: Exception) {
                isRefreshing.set(false)
                loadSongs()
            }
        })
    }

    private fun loadGenreMix(query: String, target: MutableLiveData<List<Song>>) {
        repository.searchSongs(query, object : MusicRepository.MusicCallback {
            override fun onSuccess(songs: List<Song>) {
                if (songs.size > 15) {
                    target.postValue(songs.subList(0, 15))
                } else {
                    target.postValue(songs)
                }
            }

            override fun onError(e: Exception) {
                Log.e(TAG, "Error loading mix: $query", e)
            }
        })
    }

    fun searchAndAutoPlay(query: String) {
        repository.searchSongs(query, object : MusicRepository.MusicCallback {
            override fun onSuccess(songs: List<Song>) {
                if (songs.isNotEmpty()) {
                    playSongWithRadio(songs[0])
                } else {
                    _error.postValue("No se encontraron resultados para la voz: $query")
                }
            }

            override fun onError(e: Exception) {
                _error.postValue(e.message)
            }
        })
    }

    fun clearSearch() {
        _searchResults.postValue(ArrayList())
    }

    fun triggerProgrammaticSearch(query: String) {
        _pendingSearchQuery.postValue(query)
        searchSongs(query)
    }

    fun clearPendingSearch() {
        _pendingSearchQuery.postValue(null)
    }

    fun searchSongs(query: String?) {
        if (query == null || query.trim().length < 2) return
        
        // NITRO-FIX: Sanitización de texto largo para evitar errores del motor
        val sanitizedQuery = if (query.length > 150) {
            Log.d(TAG, "ViewModel: Query demasiado larga (${query.length}). Truncando a 150 para estabilidad.")
            query.substring(0, 150)
        } else {
            query
        }

        searchRunnable?.let { searchHandler.removeCallbacks(it) }
        searchRunnable = Runnable {
            _isLoading.postValue(true)
            repository.searchSongs(sanitizedQuery, object : MusicRepository.MusicCallback {
                override fun onSuccess(songs: List<Song>) {
                    _searchResults.postValue(songs)
                    _suggestions.postValue(ArrayList())
                    _isLoading.postValue(false)
                    repository.preResolveSearchResults(songs)
                }

                override fun onError(e: Exception) {
                    _error.postValue(e.message)
                    _isLoading.postValue(false)
                }
            })
        }
        searchHandler.postDelayed(searchRunnable!!, 400)
    }

    fun fetchSearchSuggestions(query: String?) {
        if (query == null || query.length < 2) {
            _suggestions.postValue(ArrayList())
            return
        }
        repository.getSearchSuggestions(query, object : MusicRepository.SuggestionCallback {
            override fun onSuccess(list: List<String>) {
                _suggestions.postValue(list)
            }
        })
    }

    fun setPlaylistAndPlay(playlist: List<Song>?, index: Int) {
        if (playlist == null || index < 0 || index >= playlist.size) return
        currentPlaylist = ArrayList(playlist)
        currentIndex = index
        _selectedSong.postValue(currentPlaylist[currentIndex])
        _playRequest.postValue(PlaylistPlayRequest(currentPlaylist, index))
    }

    fun playSongWithRadio(song: Song?) {
        Log.d(TAG, "ViewModel: playSongWithRadio solicitado para: ${song?.title ?: "NULL"}")
        if (song == null) {
            Log.e(TAG, "ViewModel: Intento de reproducir una canción nula.")
            return
        }
        val initialQueue = ArrayList<Song>()
        initialQueue.add(song)
        
        // NITRO-QUEUE: Si viene del feed principal, añadir el resto para "Next" instantáneo
        val feed = _songs.value
        if (feed != null && feed.any { it.id == song.id }) {
            val idx = feed.indexOfFirst { it.id == song.id }
            if (idx != -1) {
                for (i in idx + 1 until feed.size) {
                    if (!feed[i].isHeader) {
                        val s = feed[i]
                        if (s.id != song.id) initialQueue.add(s)
                    }
                }
            }
        }

        currentPlaylist = initialQueue
        currentIndex = 0
        _selectedSong.postValue(song)
        _playRequest.postValue(PlaylistPlayRequest(ArrayList(initialQueue), 0))

        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "ViewModel: Pre-resolviendo stream para Nitro-Boost: ${song.url}")
                repository.getStreamUrlSyncBlocking(song.url)
                val currentFeed = _songs.value
                if (currentFeed != null) {
                    val pos = currentFeed.indexOfFirst { it.id == song.id }
                    if (pos != -1) {
                        for (i in pos + 1 until pos + 4) {
                            if (i < currentFeed.size) {
                                repository.getStreamUrlSyncBlocking(currentFeed[i].url)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ViewModel: Error en pre-resolución Nitro", e)
            }
        }

        if (_isOnline.value == false) {
            Log.d(TAG, "Radio: Modo Offline. Rellenando cola con biblioteca local...")
            repository.getMergedOfflineSongs(object : MusicRepository.MusicCallback {
                override fun onSuccess(songs: List<Song>) {
                    synchronized(currentPlaylist) {
                        songs.forEach { s ->
                            if (s.id != song.id) currentPlaylist.add(s)
                        }
                    }
                    _playRequest.postValue(PlaylistPlayRequest(ArrayList(currentPlaylist), 0))
                }

                override fun onError(e: Exception) {
                    Log.e(TAG, "Error poblando cola offline", e)
                }
            })
            return
        }

        repository.getRelatedSongs(song, object : MusicRepository.MusicCallback {
            override fun onSuccess(songs: List<Song>) {
                if (songs.isNotEmpty()) {
                    synchronized(currentPlaylist) {
                        songs.forEach { rSong ->
                            val isDuplicate = currentPlaylist.any { 
                                it.title.equals(rSong.title, ignoreCase = true) || it.id == rSong.id 
                            }
                            if (!isDuplicate) currentPlaylist.add(rSong)
                        }
                    }
                    _playRequest.postValue(PlaylistPlayRequest(ArrayList(currentPlaylist), currentIndex))
                }
            }

            override fun onError(e: Exception) {
                Log.e(TAG, "Error precargando radio", e)
            }
        })
    }

    fun skipToNext() {
        _selectedSong.value ?: return
        synchronized(currentPlaylist) {
            var nextIndex = currentIndex + 1
            while (nextIndex < currentPlaylist.size && currentPlaylist[nextIndex].isHeader) {
                nextIndex++
            }

            if (nextIndex < currentPlaylist.size) {
                currentIndex = nextIndex
                val next = currentPlaylist[currentIndex]
                _selectedSong.postValue(next)
                _playRequest.postValue(PlaylistPlayRequest(ArrayList(currentPlaylist), currentIndex))
            } else {
                Log.d(TAG, "ViewModel: Cola vacía. Saltando al Buffer Nuclear...")
                repository.getPersonalizedMix(object : MusicRepository.MusicCallback {
                    override fun onSuccess(songs: List<Song>) {
                        synchronized(currentPlaylist) {
                            val currentId = _selectedSong.value?.id
                            // NITRO-SKIP: Filtrar la canción actual para no repetirla al saltar
                            val filtered = songs.filter { !it.isHeader && it.id != currentId }
                            
                            if (filtered.isNotEmpty()) {
                                currentPlaylist.addAll(filtered)
                                if (currentIndex < currentPlaylist.size - 1) {
                                    currentIndex++
                                    val safeCopy = ArrayList(currentPlaylist)
                                    _playRequest.postValue(PlaylistPlayRequest(safeCopy, currentIndex))
                                    if (currentIndex in safeCopy.indices) {
                                        _selectedSong.postValue(safeCopy[currentIndex])
                                    }
                                }
                            }
                        }
                    }

                    override fun onError(e: Exception) {
                        Log.e(TAG, "Error saltando al buffer", e)
                    }
                })
            }
        }
    }

    fun updateSelectedSong(song: Song) {
        _selectedSong.postValue(song)
        loadLyrics(song)
        currentPlaylist.indexOfFirst { it.id == song.id }.let {
            if (it != -1) currentIndex = it
        }
    }

    fun loadLyrics(song: Song) {
        _activeLyricLine.postValue(null)
        _currentLyrics.postValue(emptyList()) // NITRO-CLEAN: Limpiar letras previas inmediatamente
        repository.getLyrics(song, object : MusicRepository.LyricsCallback {
            override fun onSuccess(lyrics: List<LyricLine>) {
                _currentLyrics.postValue(lyrics)
            }

            override fun onError(e: Exception) {
                Log.e(TAG, "Error loading lyrics", e)
            }
        })
    }

    fun updateLyricsSync(currentPositionMs: Long, durationMs: Long = 0) {
        // NITRO-PROACTIVE-REFILL: Si faltan menos de 20s para acabar y la cola es pequeña, rellenar.
        if (durationMs > 0 && currentPositionMs > durationMs - 20000 && currentPlaylist.size - 1 == currentIndex) {
             val lastSong = currentPlaylist.lastOrNull()
             if (lastSong != null && !isRefreshing.get()) {
                 Log.d(TAG, "ViewModel: Proactive Refill Triggered for ${lastSong.title}")
                 loadRadioForRefill(lastSong)
             }
        }

        val lyrics = _currentLyrics.value ?: return
        var bestMatch: LyricLine? = null
        for (line in lyrics) {
            if (line.timeMs > currentPositionMs) break
            bestMatch = line
        }
        if (bestMatch != null && bestMatch != _activeLyricLine.value) {
            _activeLyricLine.postValue(bestMatch)
        }
    }

    private fun loadRadioForRefill(song: Song) {
        if (!isRefreshing.compareAndSet(false, true)) return
        repository.getRelatedSongs(song, object : MusicRepository.MusicCallback {
            override fun onSuccess(songs: List<Song>) {
                isRefreshing.set(false)
                if (songs.isNotEmpty()) {
                    synchronized(currentPlaylist) {
                        songs.forEach { rSong ->
                            val isDuplicate = currentPlaylist.any { 
                                it.title.equals(rSong.title, ignoreCase = true) || it.id == rSong.id 
                            }
                            if (!isDuplicate) currentPlaylist.add(rSong)
                        }
                    }
                    _playRequest.postValue(PlaylistPlayRequest(ArrayList(currentPlaylist), currentIndex))
                }
            }
            override fun onError(e: Exception) {
                isRefreshing.set(false)
            }
        })
    }

    fun clearPlayRequest() = _playRequest.postValue(null)
    fun hasPlaylist() = currentPlaylist.isNotEmpty()

    fun getStreamUrlForDownload(song: Song) {
        selectedSongForDownload = song
        _downloadUrl.postValue(song.url)
    }

    fun clearDownloadUrl() = _downloadUrl.postValue(null)

    fun loadPlaylists() {
        viewModelScope.launch(Dispatchers.IO) {
            _playlists.postValue(musicDao.getAllPlaylists())
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            musicDao.insertPlaylist(Playlist(name = name, createdAt = System.currentTimeMillis()))
            loadPlaylists()
        }
    }

    fun addSongToPlaylist(playlistId: Int, song: Song) {
        viewModelScope.launch(Dispatchers.IO) {
            musicDao.insertPlaylistSong(PlaylistSong(
                playlistId,
                song.id,
                song.title,
                song.artist,
                song.thumbnailUrl,
                song.url,
                song.isLocal
            ))
        }
    }

    fun removeSongFromPlaylist(playlistId: Int, songId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            musicDao.removeSongFromPlaylist(playlistId, songId)
            loadSongsFromPlaylist(playlistId)
        }
    }

    fun deletePlaylist(playlistId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            musicDao.deleteSongsByPlaylistId(playlistId)
            musicDao.deletePlaylistById(playlistId)
            loadPlaylists()
        }
    }

    fun loadSongsFromPlaylist(playlistId: Int) {
        Log.d(TAG, "ViewModel: Cargando playlist ID $playlistId")
        viewModelScope.launch(Dispatchers.IO) {
            val pSongs = musicDao.getSongsForPlaylist(playlistId)
            val songList = pSongs.map { ps ->
                Song(
                    ps.songId,
                    ps.title ?: "Unknown",
                    ps.artist ?: "Unknown Artist",
                    ps.url ?: "",
                    0,
                    ps.thumbnailUrl ?: ""
                ).apply {
                    isLocal = ps.isLocal
                    ps.cachedStreamUrl?.let { streamUrl = it }
                }
            }
            _playlistSongs.postValue(songList)
        }
    }

    fun clearSongs() = _songs.postValue(ArrayList())

    private fun prePrepareFirstSong(song: Song) {
        try {
            val intent = android.content.Intent(getApplication(), com.musicplayer.service.PlaybackService::class.java).apply {
                action = com.musicplayer.service.PlaybackService.ACTION_PREPARE_SONG
                putExtra("song_json", Gson().toJson(song))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getApplication<Application>().startForegroundService(intent)
            } else {
                getApplication<Application>().startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "ViewModel: Failed to pre-warm song", e)
        }
    }
}
