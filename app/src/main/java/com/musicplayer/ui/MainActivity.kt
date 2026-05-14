package com.musicplayer.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.common.util.concurrent.ListenableFuture
import com.musicplayer.R
import com.musicplayer.service.PlaybackService
import com.musicplayer.ui.fragment.*
import com.musicplayer.ui.viewmodel.MusicViewModel
import com.musicplayer.model.Song
import com.musicplayer.utils.CacheMetadataManager
import java.util.ArrayList

class MainActivity : AppCompatActivity() {
    private var viewModel: MusicViewModel? = null
    private var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    
    private var miniPlayerCard: MaterialCardView? = null
    private var tvTitle: TextView? = null
    private var tvArtist: TextView? = null
    private var imgMiniPlayer: ImageView? = null
    private var btnPlayPause: FloatingActionButton? = null

    companion object {
        private const val TAG = "DEBUG_PLAYER"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Premium Edge-to-Edge Experience
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        initViews()
        setupNavigation()
        setupViewModel()
        setupMediaController()
        requestNecessaryPermissions()

        if (savedInstanceState == null) {
            loadFragment(HomeFragment())
        }
        
        handleVoiceIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleVoiceIntent(intent)
    }

    private fun handleVoiceIntent(intent: Intent?) {
        if (intent == null) return
        if (android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH == intent.action) {
            val query = intent.getStringExtra(android.app.SearchManager.QUERY)
            if (!query.isNullOrEmpty()) {
                Log.d(TAG, "Voice intent recibido: $query")
                Toast.makeText(this, "Voz: Buscando y reproduciendo $query", Toast.LENGTH_LONG).show()
                viewModel?.searchAndAutoPlay(query)
            }
        }
    }

    private fun requestNecessaryPermissions() {
        val p = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            p.add(Manifest.permission.POST_NOTIFICATIONS)
            p.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            p.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            p.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        
        val list = mutableListOf<String>()
        for (s in p) {
            if (ContextCompat.checkSelfPermission(this, s) != PackageManager.PERMISSION_GRANTED) {
                list.add(s)
            }
        }
        if (list.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, list.toTypedArray(), 100)
        }
    }

    private fun initViews() {
        miniPlayerCard = findViewById(R.id.miniPlayerCard)
        tvTitle = findViewById(R.id.tvMiniPlayerTitle)
        tvArtist = findViewById(R.id.tvMiniPlayerArtist)
        imgMiniPlayer = findViewById(R.id.imgMiniPlayer)
        btnPlayPause = findViewById(R.id.btnPlayPause)

        btnPlayPause?.setOnClickListener {
            mediaController?.let { controller ->
                if (controller.isConnected) {
                    if (controller.isPlaying) controller.pause()
                    else controller.play()
                }
            }
        }

        miniPlayerCard?.setOnClickListener { openFullPlayer() }
    }

    private fun openFullPlayer() {
        val playerFragment = PlayerFragment()
        playerFragment.sharedElementEnterTransition = androidx.transition.TransitionInflater.from(this).inflateTransition(android.R.transition.move)
        
        imgMiniPlayer?.let { img ->
            supportFragmentManager.beginTransaction()
                .setReorderingAllowed(true)
                .addSharedElement(img, "cover_transition")
                .add(android.R.id.content, playerFragment)
                .addToBackStack(null)
                .commit()
        }
    }

    private fun setupNavigation() {
        val nav = findViewById<BottomNavigationView>(R.id.bottomNavigation)
        nav.setOnItemSelectedListener { item ->
            val id = item.itemId
            val currentFragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
            
            val f: Fragment? = when (id) {
                R.id.nav_home -> if (currentFragment !is HomeFragment) HomeFragment() else null
                R.id.nav_search -> if (currentFragment !is SearchFragment) SearchFragment() else null
                R.id.nav_downloads -> if (currentFragment !is DownloadsFragment) DownloadsFragment() else null
                R.id.nav_playlists -> if (currentFragment !is PlaylistsFragment) PlaylistsFragment() else null
                else -> null
            }
            
            if (f != null) loadFragment(f)
            true
        }

        nav.setOnItemReselectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    Log.d("DEBUG_PLAYER", "Double-Click: Forzando refresco de Para Ti ☢️")
                    Toast.makeText(this, "Actualizando recomendaciones...", Toast.LENGTH_SHORT).show()
                    viewModel?.forceRefreshFeed()
                }
                R.id.nav_downloads -> {
                    Log.d("DEBUG_PLAYER", "Double-Click: Refrescando descargas ⬇️")
                    // Enviar broadcast para refrescar fragmento
                    LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("com.musicplayer.DOWNLOAD_COMPLETE"))
                }
            }
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    private fun setupViewModel() {
        viewModel = ViewModelProvider(this).get(MusicViewModel::class.java)
        
        viewModel?.selectedSong?.observe(this) { song ->
            if (song == null) return@observe
            miniPlayerCard?.visibility = View.VISIBLE
            tvTitle?.text = song.title
            tvArtist?.text = song.artist
            val originalThumb = song.thumbnailUrl
            val maxRes = originalThumb?.replace("hqdefault.jpg", "maxresdefault.jpg") ?: originalThumb
            val sdRes = originalThumb?.replace("hqdefault.jpg", "sddefault.jpg") ?: originalThumb

            imgMiniPlayer?.let { img ->
                Glide.with(this)
                    .load(maxRes)
                    .error(Glide.with(this).load(sdRes).error(Glide.with(this).load(originalThumb)))
                    .placeholder(android.R.drawable.ic_menu_report_image)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .transition(DrawableTransitionOptions.withCrossFade(200))
                    .override(250, 250)
                    .centerCrop()
                    .into(img)
            }
        }

        viewModel?.playRequest?.observe(this) { request ->
            if (request != null && mediaController?.isConnected == true) {
                playPlaylist(request.playlist, request.index)
                viewModel?.clearPlayRequest()
            }
        }
    }

    private fun playPlaylist(playlist: List<Song>, startIndex: Int) {
        if (playlist.isEmpty() || startIndex < 0 || startIndex >= playlist.size) {
            Log.w(TAG, "MainActivity: Solicitud de reproducción inválida (cola vacía o índice fuera de rango)")
            return
        }
        try {
            mediaController?.let { controller ->
                // --- DEFENSIVE COPY ---
                val safePlaylist = ArrayList(playlist)
                
                // --- FILTRADO DE CABECERAS ---
                val actualSongs = mutableListOf<Song>()
                for (s in safePlaylist) {
                    if (!s.isHeader) {
                        actualSongs.add(s)
                    }
                }
                
                // Encontrar el nuevo índice de la canción seleccionada en la lista filtrada
                val targetId = if (startIndex < safePlaylist.size) safePlaylist[startIndex].id else ""
                var adjustedIndex = 0
                for (i in actualSongs.indices) {
                    if (actualSongs[i].id == targetId) {
                        adjustedIndex = i
                        break
                    }
                }

                val currentItemCount = controller.mediaItemCount
                
                // --- OPTIMIZACIÓN DE CAMBIO RÁPIDO ---
                if (currentItemCount > 0) {
                    for (i in 0 until currentItemCount) {
                        val item = controller.getMediaItemAt(i)
                        if (item.mediaId == targetId) {
                            Log.d(TAG, "MainActivity: ⚡ Salto interno detectado.")
                            controller.seekTo(i, 0)
                            controller.prepare()
                            controller.play()
                            return
                        }
                    }
                }

                // Si la cola ya es la misma, solo añadimos lo nuevo
                if (currentItemCount > 0 && actualSongs.isNotEmpty() && 
                    controller.getMediaItemAt(0).mediaId == actualSongs[0].id) {
                    
                    Log.d(TAG, "MainActivity: Añadiendo a la cola sin resetear.")
                    val toAdd = mutableListOf<MediaItem>()
                    for (i in currentItemCount until actualSongs.size) {
                        val s = actualSongs[i]
                        
                        val finalUrl = s.streamUrl ?: s.url
                        
                        toAdd.add(MediaItem.Builder()
                            .setMediaId(s.id ?: "temp_${System.currentTimeMillis()}")
                            .setUri(android.net.Uri.parse(finalUrl))
                            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder()
                                .setTitle(s.title ?: "Unknown")
                                .setArtist(s.artist ?: "Unknown")
                                .setArtworkUri(android.net.Uri.parse(s.thumbnailUrl ?: ""))
                                .build())
                            .build())
                    }
                    if (toAdd.isNotEmpty()) {
                        controller.addMediaItems(toAdd)
                    }
                    return
                }

                controller.stop()
                controller.clearMediaItems()
                
                val items = mutableListOf<MediaItem>()
                for (s in actualSongs) {
                    val finalUrl = s.streamUrl ?: s.url
                    
                    items.add(MediaItem.Builder()
                        .setMediaId(s.id ?: "temp_${System.currentTimeMillis()}")
                        .setUri(android.net.Uri.parse(finalUrl))
                        .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(s.title ?: "Unknown")
                            .setArtist(s.artist ?: "Unknown")
                            .setArtworkUri(android.net.Uri.parse(s.thumbnailUrl ?: ""))
                            .build())
                        .build())
                }
                
                if (items.isEmpty()) return
                
                controller.setMediaItems(items, adjustedIndex, 0)
                controller.prepare()
                controller.play()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing playlist", e)
        }
    }

    private fun setupMediaController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        
        controllerFuture?.addListener({
            try {
                mediaController = controllerFuture?.get()
                Log.d(TAG, "MainActivity: MediaController conectado.")
                
                // Si había una petición de juego pendiente mientras conectaba
                viewModel?.playRequest?.value?.let { pending ->
                    playPlaylist(pending.playlist, pending.index)
                    viewModel?.clearPlayRequest()
                }

                mediaController?.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        val shouldShowPause = mediaController?.playWhenReady ?: false
                        btnPlayPause?.setImageResource(if (shouldShowPause) R.drawable.ic_pause_mod else R.drawable.ic_play_mod)
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        mediaItem?.mediaMetadata?.let { metadata ->
                            Log.d(TAG, "MainActivity: Transición a nueva canción: ${metadata.title}")
                            
                            val title = metadata.title?.toString() ?: "Unknown"
                            val artist = metadata.artist?.toString() ?: "Unknown"
                            val thumb = metadata.artworkUri?.toString() ?: ""
                            
                            var originalUrl = ""
                            mediaItem.requestMetadata.mediaUri?.let {
                                originalUrl = it.toString()
                            } ?: run {
                                mediaItem.localConfiguration?.uri?.let {
                                    originalUrl = it.toString()
                                }
                            }

                            // Sincronizar el ViewModel
                            val song = Song(
                                mediaItem.mediaId,
                                title,
                                artist,
                                originalUrl,
                                0,
                                thumb
                            )
                            viewModel?.updateSelectedSong(song)
                            
                            // Guardar historial del Caché Offline
                            CacheMetadataManager.saveMetadata(this@MainActivity, song)
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.e(TAG, "MainActivity: ❌ Error de motor: ${error.message}")
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "MainActivity: Fallo en conexión", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
    
    @get:JvmName("getMusicMediaController")
    val musicMediaController: MediaController?
        get() = mediaController
}
