package com.musicplayer.ui.fragment

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.musicplayer.R
import com.musicplayer.model.Song
import com.musicplayer.ui.MainActivity
import com.musicplayer.ui.adapter.SongAdapter
import com.musicplayer.ui.adapter.SuggestionAdapter
import com.musicplayer.ui.viewmodel.MusicViewModel
import com.musicplayer.utils.DownloadHelper

class SearchFragment : Fragment() {
    private var viewModel: MusicViewModel? = null
    private var searchAdapter: SongAdapter? = null
    private var suggestionAdapter: SuggestionAdapter? = null
    private var progressBar: View? = null
    private var recyclerViewSuggestions: RecyclerView? = null
    
    private val speechResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val results = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!results.isNullOrEmpty()) {
                val spokenTextOriginal = results[0]
                val spokenText = spokenTextOriginal.lowercase()
                
                val mediaController = (requireActivity() as MainActivity).musicMediaController
                
                when {
                    spokenText.contains("pausa") || spokenText.contains("detener") || spokenText.contains("para la música") -> {
                        mediaController?.pause()
                        Toast.makeText(requireContext(), "🤖 Comprendido: Pausando...", Toast.LENGTH_SHORT).show()
                    }
                    spokenText.contains("siguiente") || spokenText.contains("otra canción") || spokenText.contains("pon la que sigue") -> {
                        mediaController?.seekToNext()
                        Toast.makeText(requireContext(), "🤖 Comprendido: Siguiente pista", Toast.LENGTH_SHORT).show()
                    }
                    spokenText.contains("anterior") || spokenText.contains("regresa") || spokenText.contains("canción de atrás") -> {
                        mediaController?.seekToPrevious()
                        Toast.makeText(requireContext(), "🤖 Comprendido: Pista anterior", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        var query = spokenText.replace("reproduce", "")
                            .replace("reproducir", "")
                            .replace("pon música de", "")
                            .replace("pon", "")
                            .replace("busca", "")
                            .replace("buscar", "")
                            .trim()
                            
                        if (query.isEmpty()) query = spokenTextOriginal
                        
                        if (spokenText.contains("reproduce") || spokenText.contains("reproducir") || spokenText.contains("pon ")) {
                            Toast.makeText(requireContext(), "🤖 Comprendido: Reproduciendo $query", Toast.LENGTH_SHORT).show()
                            viewModel?.searchAndAutoPlay(query)
                            try {
                                parentFragmentManager.beginTransaction()
                                    .setCustomAnimations(R.anim.slide_up, 0, 0, R.anim.slide_down)
                                    .add(android.R.id.content, PlayerFragment())
                                    .addToBackStack(null)
                                    .commit()
                            } catch (ignored: Exception) {}
                        } else {
                            Toast.makeText(requireContext(), "🤖 Comprendido: Buscando $query", Toast.LENGTH_SHORT).show()
                            val etSearch = view?.findViewById<TextInputEditText>(R.id.etSearch)
                            
                            if (etSearch != null) {
                                etSearch.setText(query as CharSequence)
                                etSearch.setSelection(query.length)
                                recyclerViewSuggestions?.visibility = View.GONE
                                performSearch(query, etSearch)
                            } else {
                                viewModel?.searchSongs(query)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_search, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        viewModel = ViewModelProvider(requireActivity()).get(MusicViewModel::class.java)
        
        val etSearch = view.findViewById<TextInputEditText>(R.id.etSearch)
        progressBar = view.findViewById(R.id.searchProgressBar)
        val recyclerView = view.findViewById<RecyclerView>(R.id.searchRecyclerView)
        val btnVoiceSearch = view.findViewById<ImageButton>(R.id.btnVoiceSearch)
        val btnDownloadSelected = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDownloadSelected)
        
        btnVoiceSearch.setOnClickListener {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Dime qué canción quieres buscar...")
            }
            try {
                speechResultLauncher.launch(intent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Tu dispositivo no soporta búsqueda por voz", Toast.LENGTH_SHORT).show()
            }
        }
        
        val gridLayoutManager = GridLayoutManager(requireContext(), 2)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val type = searchAdapter?.getItemViewType(position) ?: 0
                return if (type != 0) 2 else 1 // 0 es TYPE_SONG. Los demás (Header/Master) ocupan 2 columnas
            }
        }
        recyclerView.layoutManager = gridLayoutManager
        recyclerView.itemAnimator = null 
        recyclerView.setHasFixedSize(true)
        recyclerView.setItemViewCacheSize(20)
        
        searchAdapter = SongAdapter(object : SongAdapter.OnSongActionListener {
            override fun onPlay(song: Song) {
                Log.d("DEBUG_PLAYER", "Search: Iniciando Smart Radio para ${song.title}")
                viewModel?.playSongWithRadio(song)
                parentFragmentManager.beginTransaction()
                    .setCustomAnimations(R.anim.slide_up, 0, 0, R.anim.slide_down)
                    .add(android.R.id.content, PlayerFragment())
                    .addToBackStack(null)
                    .commit()
            }

            override fun onDownload(song: Song) {
                AlertDialog.Builder(requireContext())
                    .setTitle("¿Descargar canción?")
                    .setMessage("¿Deseas descargar \"${song.title}\" a tu biblioteca local?")
                    .setPositiveButton("Aceptar") { _, _ ->
                        Toast.makeText(requireContext(), "Preparando descarga...", Toast.LENGTH_SHORT).show()
                        viewModel?.getStreamUrlForDownload(song)
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }

            override fun onDelete(song: Song) {}
            override fun onAddToPlaylist(song: Song) {
                showAddToPlaylistDialog(song)
            }
        })
        
        recyclerView.adapter = searchAdapter

        val isSelectionMode = arguments?.getBoolean("selection_mode", false) ?: false
        if (isSelectionMode) {
            searchAdapter?.setSelectionMode(true)
            btnDownloadSelected.visibility = View.VISIBLE
            
            searchAdapter?.onSelectionChanged = { count ->
                btnDownloadSelected.text = "Descargar Seleccionados ($count)"
            }
        }

        btnDownloadSelected.setOnClickListener {
            val selected = searchAdapter?.getSelectedSongs() ?: emptyList()
            if (selected.isNotEmpty()) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Descarga Masiva")
                    .setMessage("¿Deseas descargar ${selected.size} canciones seleccionadas?")
                    .setPositiveButton("Descargar todo") { _, _ ->
                        Toast.makeText(requireContext(), "Iniciando descarga en serie...", Toast.LENGTH_SHORT).show()
                        selected.forEach { song ->
                            viewModel?.getStreamUrlForDownload(song)
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            } else {
                Toast.makeText(requireContext(), "Selecciona al menos una canción", Toast.LENGTH_SHORT).show()
            }
        }

        // SUGGESTIONS SETUP
        recyclerViewSuggestions = view.findViewById(R.id.suggestionsRecyclerView)
        recyclerViewSuggestions?.layoutManager = LinearLayoutManager(requireContext())
        var isSearchingFromSuggestion = false
        suggestionAdapter = SuggestionAdapter { suggestion ->
            isSearchingFromSuggestion = true
            etSearch.setText(suggestion as CharSequence)
            etSearch.setSelection(suggestion.length)
            recyclerViewSuggestions?.visibility = View.GONE
            viewModel?.clearSuggestions()
            performSearch(suggestion, etSearch)
            isSearchingFromSuggestion = false
        }
        recyclerViewSuggestions?.adapter = suggestionAdapter

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (isSearchingFromSuggestion) return
                
                if ((s?.length ?: 0) > 1) {
                    viewModel?.fetchSearchSuggestions(s.toString())
                } else {
                    recyclerViewSuggestions?.visibility = View.GONE
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = etSearch.text.toString()
                if (query.isNotEmpty()) {
                    recyclerViewSuggestions?.visibility = View.GONE
                    performSearch(query, etSearch)
                }
                true
            } else {
                false
            }
        }

        viewModel?.searchResults?.observe(viewLifecycleOwner) { songs ->
            progressBar?.visibility = View.GONE
            
            // ⚡ Polish: Si estamos en modo selección, la primera canción manda el grupo
            val processedSongs = if (isSelectionMode && songs.isNotEmpty()) {
                val list = songs.toMutableList()
                list[0].isHeader = true
                list
            } else {
                songs
            }
            
            searchAdapter?.updateSongs(processedSongs)
            
            if (isSelectionMode && songs.isNotEmpty()) {
                btnDownloadSelected.text = "Descargar Seleccionados (0)"
            }
        }

        viewModel?.suggestions?.observe(viewLifecycleOwner) { suggestions ->
            if (!suggestions.isNullOrEmpty() && etSearch.hasFocus()) {
                suggestionAdapter?.updateSuggestions(suggestions)
                recyclerViewSuggestions?.visibility = View.VISIBLE
            } else {
                recyclerViewSuggestions?.visibility = View.GONE
            }
        }

        viewModel?.pendingSearchQuery?.observe(viewLifecycleOwner) { query ->
            if (query != null) {
                etSearch.setText(query)
                etSearch.setSelection(query.length)
                recyclerViewSuggestions?.visibility = View.GONE
                performSearch(query, etSearch)
                viewModel?.clearPendingSearch()
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

        viewModel?.error?.observe(viewLifecycleOwner) { errorMsg ->
            errorMsg?.let {
                progressBar?.visibility = View.GONE
                Toast.makeText(requireContext(), "Error de búsqueda: $it", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun performSearch(query: String, etSearch: TextInputEditText) {
        // CERRAR TECLADO
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etSearch.windowToken, 0)

        progressBar?.visibility = View.VISIBLE
        viewModel?.searchSongs(query)
    }

    private fun showAddToPlaylistDialog(song: Song) {
        viewModel?.playlists?.observe(viewLifecycleOwner) { playlists ->
            if (playlists.isNullOrEmpty()) {
                Toast.makeText(requireContext(), "No tienes playlists creadas. Ve a la pestaña de Playlists.", Toast.LENGTH_LONG).show()
                return@observe
            }

            val names = playlists.map { it.name ?: "" }.toTypedArray()

            AlertDialog.Builder(requireContext())
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
