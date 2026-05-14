package com.musicplayer.ui.fragment

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.musicplayer.R
import com.musicplayer.model.Song
import com.musicplayer.ui.adapter.SongAdapter
import com.musicplayer.ui.viewmodel.MusicViewModel
import com.musicplayer.utils.CacheMetadataManager
import com.musicplayer.utils.MusicCacheManager
import java.io.File

class DownloadsFragment : Fragment() {
    companion object {
        private const val TAG = "DownloadsFragment"
    }

    private var viewModel: MusicViewModel? = null
    private var adapter: SongAdapter? = null
    private var tvNoDownloads: TextView? = null
    private var tvDownloadsTitle: TextView? = null
    private val physicalSongs = mutableListOf<Song>()
    private val cachedSongsList = mutableListOf<Song>()
    private var showingCache = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaObserver: ContentObserver? = null

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "downloadReceiver: Notificación de refresco recibida")
            loadLocalSongs()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_downloads, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated: Iniciando vista de descargas")
        
        viewModel = ViewModelProvider(requireActivity()).get(MusicViewModel::class.java)
        tvNoDownloads = view.findViewById(R.id.tvNoDownloads)
        tvDownloadsTitle = view.findViewById(R.id.tvDownloadsTitle)
        val recyclerView = view.findViewById<RecyclerView>(R.id.downloadsRecyclerView)
        val toggleGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleGroup)
        val btnClearCache = view.findViewById<ImageButton>(R.id.btnClearCache)

        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                if (checkedId == R.id.btnPhysical) {
                    showingCache = false
                    tvDownloadsTitle?.text = "Descargas"
                    btnClearCache.visibility = View.GONE
                    displaySongs()
                } else if (checkedId == R.id.btnCache) {
                    showingCache = true
                    tvDownloadsTitle?.text = "Caché Offline"
                    btnClearCache.visibility = View.VISIBLE
                    displaySongs()
                }
            }
        }

        btnClearCache.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Borrar caché")
                .setMessage("¿Estás seguro de que deseas borrar todo el caché remoto? Las canciones tendrán que descargarse de nuevo al reproducirlas.")
                .setPositiveButton("Borrar") { _, _ ->
                    MusicCacheManager.getInstance(requireContext()).clearCache()
                    Toast.makeText(requireContext(), "Caché borrado", Toast.LENGTH_SHORT).show()
                    loadLocalSongs()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        
        recyclerView.layoutManager = GridLayoutManager(requireContext(), 2)
        adapter = SongAdapter(object : SongAdapter.OnSongActionListener {
            override fun onPlay(song: Song) {
                try {
                    Log.d(TAG, "onPlay: Reproduciendo descarga local: ${song.title}")
                    
                    val currentList = adapter?.songs ?: return
                    if (currentList.isEmpty()) {
                        Log.e(TAG, "onPlay: Lista de canciones vacía o nula")
                        return
                    }
                    
                    var index = currentList.indexOf(song)
                    if (index == -1) {
                        index = currentList.indexOfFirst { it.id == song.id }
                    }
                    if (index == -1) index = 0
                    
                    viewModel?.setPlaylistAndPlay(currentList, index)

                    parentFragmentManager.beginTransaction()
                        .setCustomAnimations(R.anim.slide_up, 0, 0, R.anim.slide_down)
                        .add(android.R.id.content, PlayerFragment())
                        .addToBackStack(null)
                        .commit()
                } catch (e: Exception) {
                    Log.e(TAG, "DownloadsFragment: Error fatal en onPlay", e)
                }
            }

            override fun onDownload(song: Song) {}

            override fun onDelete(song: Song) {
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Eliminar canción")
                    .setMessage("¿Estás seguro de que quieres eliminar \"${song.title}\"? Se borrará permanentemente del dispositivo.")
                    .setPositiveButton("Eliminar") { _, _ -> deleteSong(song) }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }

            override fun onAddToPlaylist(song: Song) {
                showAddToPlaylistDialog(song)
            }
        })
        
        recyclerView.adapter = adapter
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            downloadReceiver, IntentFilter("com.musicplayer.DOWNLOAD_COMPLETE")
        )
        loadLocalSongs()
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart: Registrando MediaObserver")
        mediaObserver = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                Log.d(TAG, "MediaObserver: Cambio detectado en MediaStore, refrescando...")
                loadLocalSongs()
            }
        }
        mediaObserver?.let {
            requireContext().contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                it
            )
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop: Desregistrando MediaObserver y Receiver")
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(downloadReceiver)
        mediaObserver?.let {
            Log.d(TAG, "onStop: Desregistrando MediaObserver")
            requireContext().contentResolver.unregisterContentObserver(it)
        }
    }

    private fun loadLocalSongs() {
        physicalSongs.clear()
        cachedSongsList.clear()
        
        try {
            val contentResolver = requireContext().contentResolver
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.ALBUM_ID
            )
            
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DATA} LIKE '%/NMusic/%'"
            val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            
            contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                    val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    
                    do {
                        val id = cursor.getLong(idCol)
                        val title = cursor.getString(titleCol)
                        val artist = cursor.getString(artistCol)
                        val data = cursor.getString(dataCol)
                        val duration = cursor.getInt(durationCol).toLong()
                        val albumId = cursor.getLong(albumIdCol)
                        
                        if (data != null) {
                            val file = File(data)
                            val uriStr = Uri.fromFile(file).toString()
                            
                            val sArtworkUri = Uri.parse("content://media/external/audio/albumart")
                            val thumbUri = ContentUris.withAppendedId(sArtworkUri, albumId).toString()
                            
                            val song = Song(
                                id.toString(),
                                if (!title.isNullOrEmpty()) title else file.name,
                                if (!artist.isNullOrEmpty() && artist != "<unknown>") artist else "Artista Desconocido",
                                uriStr,
                                duration,
                                thumbUri
                            ).apply {
                                isLocal = true
                            }
                            physicalSongs.add(song)
                        }
                    } while (cursor.moveToNext())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al cargar canciones desde MediaStore", e)
        }
        
        Log.d(TAG, "loadLocalSongs: Encontradas ${physicalSongs.size} canciones en el dispositivo")
        
        try {
            val cache = MusicCacheManager.getInstance(requireContext()).cache
            if (cache != null) {
                val keys = cache.keys
                for (key in keys) {
                    val cachedSong = CacheMetadataManager.getMetadata(requireContext(), key)
                    if (cachedSong != null) {
                        cachedSongsList.add(cachedSong)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cargando historia de cache offline", e)
        }
        
        displaySongs()
    }

    private fun displaySongs() {
        val activeList = if (showingCache) cachedSongsList else physicalSongs
        if (activeList.isEmpty()) {
            tvNoDownloads?.visibility = View.VISIBLE
            tvNoDownloads?.text = if (showingCache) "El caché de red está vacío" else "No hay archivos MP3 descargados"
            adapter?.updateSongs(emptyList())
        } else {
            tvNoDownloads?.visibility = View.GONE
            adapter?.updateSongs(activeList)
        }
    }

    private fun deleteSong(song: Song) {
        try {
            if (showingCache) {
                MusicCacheManager.getInstance(requireContext()).remove(song.url)
                Toast.makeText(requireContext(), "Eliminado del caché", Toast.LENGTH_SHORT).show()
            } else {
                val id = song.id.toLong()
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                requireContext().contentResolver.delete(uri, null, null)
                
                if (song.url.startsWith("file://")) {
                    Uri.parse(song.url).path?.let { path ->
                        val file = File(path)
                        if (file.exists()) file.delete()
                    }
                }
                Toast.makeText(requireContext(), "Archivo local eliminado", Toast.LENGTH_SHORT).show()
            }
            loadLocalSongs()
        } catch (e: Exception) {
            Log.e(TAG, "Error al eliminar canción", e)
            Toast.makeText(requireContext(), "Error al eliminar: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAddToPlaylistDialog(song: Song) {
        viewModel?.playlists?.observe(viewLifecycleOwner) { playlists ->
            if (playlists.isNullOrEmpty()) {
                Toast.makeText(requireContext(), "No tienes playlists creadas. Ve a la pestaña de Playlists.", Toast.LENGTH_LONG).show()
                return@observe
            }

            val names = playlists.mapNotNull { it.name }.toTypedArray()

            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Añadir a playlist")
                .setItems(names) { _, which ->
                    viewModel?.addSongToPlaylist(playlists[which].id, song)
                    Toast.makeText(requireContext(), "Añadido a ${names[which]}", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
        viewModel?.loadPlaylists()
    }
}
