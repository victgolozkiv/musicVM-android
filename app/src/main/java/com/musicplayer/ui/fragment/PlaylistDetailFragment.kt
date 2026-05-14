package com.musicplayer.ui.fragment

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.musicplayer.R
import com.musicplayer.model.Song
import com.musicplayer.ui.adapter.SongAdapter
import com.musicplayer.ui.viewmodel.MusicViewModel
import com.musicplayer.utils.DownloadHelper

class PlaylistDetailFragment : Fragment() {
    private var playlistId: Int = 0
    private var playlistName: String? = null
    private var viewModel: MusicViewModel? = null
    private var adapter: SongAdapter? = null

    companion object {
        private const val TAG = "DEBUG_PLAYER"
        private const val ARG_PLAYLIST_ID = "playlist_id"
        private const val ARG_PLAYLIST_NAME = "playlist_name"

        @JvmStatic
        fun newInstance(id: Int, name: String?): PlaylistDetailFragment {
            return PlaylistDetailFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_PLAYLIST_ID, id)
                    putString(ARG_PLAYLIST_NAME, name)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            playlistId = it.getInt(ARG_PLAYLIST_ID)
            playlistName = it.getString(ARG_PLAYLIST_NAME)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_playlist_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        viewModel = ViewModelProvider(requireActivity()).get(MusicViewModel::class.java)
        
        val tvName = view.findViewById<TextView>(R.id.tvPlaylistName)
        tvName.text = playlistName
        
        val btnBack = view.findViewById<ImageButton>(R.id.btnBack)
        btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        
        val btnDeletePlaylist = view.findViewById<ImageButton>(R.id.btnDeletePlaylist)
        val btnDownloadPlaylist = view.findViewById<ImageButton>(R.id.btnDownloadPlaylist)

        btnDeletePlaylist.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Eliminar Playlist")
                .setMessage("¿Estás seguro de que deseas eliminar la playlist '$playlistName'? Las canciones no se borrarán de la base de datos.")
                .setPositiveButton("Eliminar") { _, _ ->
                    viewModel?.deletePlaylist(playlistId)
                    Toast.makeText(requireContext(), "Playlist eliminada", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        btnDownloadPlaylist.setOnClickListener {
            val currentSongs = adapter?.songs
            if (currentSongs.isNullOrEmpty()) {
                Toast.makeText(requireContext(), "La playlist está vacía", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val formats = arrayOf("mp3 (Alta Calidad)", "m4a (Normal)", "wav (Sin pérdida - Pesado)")
            val formatCodes = arrayOf("mp3", "m4a", "wav")

            AlertDialog.Builder(requireContext())
                .setTitle("Descargar ${currentSongs.size} canciones")
                .setSingleChoiceItems(formats, 0, null)
                .setPositiveButton("Comenzar Descargas") { dialog, _ ->
                    val selectedPosition = (dialog as AlertDialog).listView.checkedItemPosition
                    val chosenFormat = formatCodes[selectedPosition]
                    Toast.makeText(requireContext(), "Se iniciarán ${currentSongs.size} descargas en segundo plano", Toast.LENGTH_LONG).show()
                    for (s in currentSongs) {
                        DownloadHelper.downloadSong(requireContext(), s, s.url, chosenFormat)
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        val rv = view.findViewById<RecyclerView>(R.id.rvPlaylistSongs)
        rv.layoutManager = GridLayoutManager(requireContext(), 2)
        
        adapter = SongAdapter(object : SongAdapter.OnSongActionListener {
            override fun onPlay(song: Song) {
                val currentList = adapter?.songs ?: emptyList()
                val index = currentList.indexOf(song)
                viewModel?.setPlaylistAndPlay(currentList, index)
                
                parentFragmentManager.beginTransaction()
                    .setCustomAnimations(R.anim.slide_up, 0, 0, R.anim.slide_down)
                    .add(android.R.id.content, PlayerFragment())
                    .addToBackStack(null)
                    .commit()
            }

            override fun onDownload(song: Song) {
                viewModel?.getStreamUrlForDownload(song)
            }

            override fun onDelete(song: Song) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Eliminar de Playlist")
                    .setMessage("¿Eliminar \"${song.title}\" de esta lista?")
                    .setPositiveButton("Eliminar") { _, _ ->
                        viewModel?.removeSongFromPlaylist(playlistId, song.id)
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }

            override fun onAddToPlaylist(song: Song) {
                Toast.makeText(requireContext(), "Ya estás en esta playlist", Toast.LENGTH_SHORT).show()
            }
        })
        adapter?.setPlaylistMode(true)
        rv.adapter = adapter

        val tvEmpty = view.findViewById<TextView>(R.id.tvEmptyMessage)

        viewModel?.playlistSongs?.observe(viewLifecycleOwner) { songs ->
            songs?.let {
                adapter?.updateSongs(it)
                tvEmpty.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        viewModel?.downloadUrl?.observe(viewLifecycleOwner) { url ->
            url?.let { downloadUrl ->
                val song = viewModel?.selectedSongForDownload
                song?.let { s ->
                    val formats = arrayOf("mp3 (Alta Calidad)", "m4a (Normal)", "wav (Sin pérdida - Pesado)")
                    val formatCodes = arrayOf("mp3", "m4a", "wav")

                    AlertDialog.Builder(requireContext())
                        .setTitle("Elegir formato (${s.title})")
                        .setSingleChoiceItems(formats, 0, null)
                        .setPositiveButton("Descargar") { dialog, _ ->
                            val selectedPosition = (dialog as AlertDialog).listView.checkedItemPosition
                            val chosenFormat = formatCodes[selectedPosition]
                            Toast.makeText(requireContext(), "Iniciando descarga...", Toast.LENGTH_SHORT).show()
                            DownloadHelper.downloadSong(requireContext(), s, downloadUrl, chosenFormat)
                            viewModel?.clearDownloadUrl()
                        }
                        .setNegativeButton("Cancelar") { _, _ ->
                            viewModel?.clearDownloadUrl()
                        }
                        .setOnCancelListener { viewModel?.clearDownloadUrl() }
                        .show()
                }
            }
        }

        // Cargar las canciones de la playlist
        viewModel?.loadSongsFromPlaylist(playlistId)
    }
}
