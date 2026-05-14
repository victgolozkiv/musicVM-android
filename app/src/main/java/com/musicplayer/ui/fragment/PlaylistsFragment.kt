package com.musicplayer.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.musicplayer.R
import com.musicplayer.db.entity.Playlist
import android.view.inputmethod.EditorInfo
import com.musicplayer.ui.adapter.PlaylistAdapter
import com.musicplayer.ui.viewmodel.MusicViewModel

class PlaylistsFragment : Fragment() {
    private var viewModel: MusicViewModel? = null
    private var adapter: PlaylistAdapter? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_playlists, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity()).get(MusicViewModel::class.java)

        val recyclerView = view.findViewById<RecyclerView>(R.id.rvPlaylists)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        
        adapter = PlaylistAdapter(object : PlaylistAdapter.OnPlaylistInteractionListener {
            override fun onPlaylistClick(playlist: Playlist) {
                // Abrir detalle de la playlist con animación
                parentFragmentManager.beginTransaction()
                    .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out, android.R.anim.fade_in, android.R.anim.fade_out)
                    .replace(R.id.fragmentContainer, PlaylistDetailFragment.newInstance(playlist.id, playlist.name))
                    .addToBackStack(null)
                    .commit()
            }

            override fun onPlaylistDelete(playlist: Playlist) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Eliminar Playlist")
                    .setMessage("¿Estás seguro de que quieres eliminar \"${playlist.name}\"?")
                    .setPositiveButton("Eliminar") { _, _ ->
                        viewModel?.deletePlaylist(playlist.id)
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        })
        recyclerView.adapter = adapter
        
        setupSearchDiscovery(view)

        view.findViewById<View>(R.id.btnCreatePlaylist).setOnClickListener { showCreatePlaylistDialog() }

        viewModel?.playlists?.observe(viewLifecycleOwner) { playlists ->
            playlists?.let { adapter?.updatePlaylists(it) }
        }

        viewModel?.loadPlaylists()
    }

    private fun setupSearchDiscovery(view: View) {
        val etSearch = view.findViewById<EditText>(R.id.etOnlineSearch)
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = etSearch.text.toString().trim()
                if (query.isNotEmpty()) {
                    val fullQuery = "$query full mix official"
                    viewModel?.triggerProgrammaticSearch(fullQuery)
                    
                    // Navegar a SearchFragment
                    val searchFragment = SearchFragment().apply {
                        arguments = Bundle().apply {
                            putBoolean("selection_mode", true)
                        }
                    }
                    parentFragmentManager.beginTransaction()
                        .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
                        .replace(R.id.fragmentContainer, searchFragment)
                        .addToBackStack(null)
                        .commit()
                }
                true
            } else {
                false
            }
        }
    }

    private fun showCreatePlaylistDialog() {
        val input = EditText(requireContext())
        AlertDialog.Builder(requireContext())
            .setTitle("Nueva Playlist")
            .setMessage("Introduce el nombre:")
            .setView(input)
            .setPositiveButton("Crear") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel?.createPlaylist(name)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
