package com.musicplayer.ui.fragment

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import java.util.*

class HomeFragment : Fragment() {
    private var viewModel: MusicViewModel? = null
    private var mainAdapter: SongAdapter? = null
    private var progressBar: View? = null

    companion object {
        private const val TAG = "DEBUG_PLAYER"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(requireActivity()).get(MusicViewModel::class.java)

        progressBar = view.findViewById(R.id.homeProgressBar)
        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerView)

        val tvHeader = view.findViewById<TextView>(R.id.tvHomeHeader)
        tvHeader?.let {
            val tz = TimeZone.getTimeZone("America/Chihuahua")
            val hour = Calendar.getInstance(tz).get(Calendar.HOUR_OF_DAY)
            it.text = when (hour) {
                in 0..11 -> "Buenos días"
                in 12..19 -> "Buenas tardes"
                else -> "Buenas noches"
            }
        }

        val layoutManager = GridLayoutManager(requireContext(), 2)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (mainAdapter?.getItemViewType(position) == 1) 2 else 1
            }
        }

        recyclerView?.apply {
            this.layoutManager = layoutManager
            itemAnimator = null // NITRO: Evitar "brincos" visuales 🚀
            setHasFixedSize(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                suppressLayout(false)
            }
            setItemViewCacheSize(20)
        }

        val actionListener = object : SongAdapter.OnSongActionListener {
            override fun onPlay(song: Song) {
                Log.d(TAG, "Home: Iniciando Smart Radio para ${song.title}")
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
                    .setMessage("¿Deseas descargar \"${song.title}\"?")
                    .setPositiveButton("Descargar") { _, _ ->
                        viewModel?.getStreamUrlForDownload(song)
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }

            override fun onDelete(song: Song) {}
            override fun onAddToPlaylist(song: Song) {
                showAddToPlaylistDialog(song)
            }
        }

        mainAdapter = SongAdapter(actionListener)
        recyclerView?.adapter = mainAdapter

        // --- OBSERVERS ---
        viewModel?.songs?.observe(viewLifecycleOwner) { songs ->
            songs?.let {
                progressBar?.visibility = View.GONE
                mainAdapter?.updateSongs(it)
            }
        }

        val tvMainTitle = view.findViewById<TextView>(R.id.tvMainTitle)
        
        viewModel?.feedTitle?.observe(viewLifecycleOwner) { title ->
            val seed = viewModel?.getFeedSeedInfo()?.value
            if (!seed.isNullOrEmpty()) {
                tvMainTitle?.text = "Porque escuchaste $seed"
            } else {
                tvMainTitle?.text = title ?: getString(R.string.para_ti_title)
            }
        }

        viewModel?.getFeedSeedInfo()?.observe(viewLifecycleOwner) { seedName: String? ->
            if (!seedName.isNullOrEmpty()) {
                tvMainTitle?.text = "Porque escuchaste $seedName"
            } else {
                tvMainTitle?.text = getString(R.string.para_ti_title)
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

        viewModel?.isLoading?.observe(viewLifecycleOwner) { loading ->
            progressBar?.visibility = if (loading == true) View.VISIBLE else View.GONE
        }

        viewModel?.error?.observe(viewLifecycleOwner) { errorMsg ->
            errorMsg?.let {
                Toast.makeText(requireContext(), "Error: $it", Toast.LENGTH_LONG).show()
            }
        }

        // Siempre solicitar carga para activar la paginación masiva y actualizar el caché
        viewModel?.loadSongs()
        
        // Cargar playlists inicialmente para el diálogo
        viewModel?.loadPlaylists()
        setupPlaylistObserver()
    }

    private fun setupPlaylistObserver() {
        // Observador persistente para evitar duplicados
        viewModel?.playlists?.observe(viewLifecycleOwner) {
            // Solo para que el ViewModel tenga los datos listos cuando se necesiten
        }
    }

    override fun onResume() {
        super.onResume()
        // Fuerza la reconstrucción del buffer y el UI con la nueva semilla cuando el usuario regresa
        viewModel?.forceRefreshFeed()
    }

    private fun showAddToPlaylistDialog(song: Song) {
        val playlists = viewModel?.playlists?.value
        
        if (playlists.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "No tienes playlists creadas. Ve a la pestaña de Playlists.", Toast.LENGTH_LONG).show()
            viewModel?.loadPlaylists()
            return
        }

        val names = playlists.map { it.name }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("Añadir a playlist")
            .setItems(names) { _, which ->
                val playlistId = playlists[which].id
                viewModel?.addSongToPlaylist(playlistId, song)
                Toast.makeText(requireContext(), "Añadido a ${names[which]}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
