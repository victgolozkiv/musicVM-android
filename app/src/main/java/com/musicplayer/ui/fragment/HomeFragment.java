package com.musicplayer.ui.fragment;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.musicplayer.R;
import com.musicplayer.model.Song;
import com.musicplayer.ui.adapter.SongAdapter;
import com.musicplayer.ui.viewmodel.MusicViewModel;
import com.musicplayer.utils.DownloadHelper;
import java.util.List;

public class HomeFragment extends Fragment {
    private static final String TAG = "DEBUG_PLAYER";
    private MusicViewModel viewModel;
    private SongAdapter songAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        viewModel = new ViewModelProvider(requireActivity()).get(MusicViewModel.class);
        
        RecyclerView recyclerView = view.findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        
        songAdapter = new SongAdapter(new SongAdapter.OnSongActionListener() {
            @Override
            public void onPlay(Song song) {
                Log.d(TAG, "Home: Iniciando Smart Radio para " + song.getTitle());
                
                // 1. Usar Smart Radio (una sola canción + búsqueda automática de relacionadas)
                viewModel.playSongWithRadio(song);

                // 2. Navegar al reproductor
                getParentFragmentManager().beginTransaction()
                    .setCustomAnimations(R.anim.slide_up, 0, 0, R.anim.slide_down)
                    .add(android.R.id.content, new PlayerFragment())
                    .addToBackStack(null)
                    .commit();
            }

            @Override
            public void onDownload(Song song) {
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("¿Descargar canción?")
                    .setMessage("¿Deseas descargar \"" + song.getTitle() + "\" a tu biblioteca local?")
                    .setPositiveButton("Aceptar", (dialog, which) -> {
                        Toast.makeText(requireContext(), "Preparando descarga...", Toast.LENGTH_SHORT).show();
                        viewModel.getStreamUrlForDownload(song);
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
            }

            @Override
            public void onDelete(Song song) {
                // No se espera borrar desde aquí, pero por interfaz:
                Toast.makeText(requireContext(), "Acción no disponible aquí", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onAddToPlaylist(Song song) {
                showAddToPlaylistDialog(song);
            }
        });
        
        recyclerView.setAdapter(songAdapter);

        viewModel.getSongs().observe(getViewLifecycleOwner(), songs -> {
            if (songs != null) {
                songAdapter.updateSongs(songs);
            }
        });
        
        viewModel.getDownloadUrl().observe(getViewLifecycleOwner(), url -> {
            if (url != null) {
                DownloadHelper.downloadSong(requireContext(), viewModel.getSelectedSongForDownload(), url);
                viewModel.clearDownloadUrl();
            }
        });

        viewModel.getError().observe(getViewLifecycleOwner(), errorMsg -> {
            if (errorMsg != null) {
                Toast.makeText(requireContext(), "Error: " + errorMsg, Toast.LENGTH_LONG).show();
            }
        });

        if (viewModel.getSongs().getValue() == null || viewModel.getSongs().getValue().isEmpty()) {
            viewModel.loadSongs();
        }
        
        // Cargar playlists inicialmente para el diálogo
        viewModel.loadPlaylists();
        setupPlaylistObserver();
    }

    private void setupPlaylistObserver() {
        // Observador persistente para evitar duplicados
        viewModel.getPlaylists().observe(getViewLifecycleOwner(), playlists -> {
            // Solo para que el ViewModel tenga los datos listos cuando se necesiten
        });
    }

    private void showAddToPlaylistDialog(Song song) {
        List<com.musicplayer.db.entity.Playlist> playlists = viewModel.getPlaylists().getValue();
        
        if (playlists == null || playlists.isEmpty()) {
            Toast.makeText(requireContext(), "No tienes playlists creadas. Ve a la pestaña de Playlists.", Toast.LENGTH_LONG).show();
            viewModel.loadPlaylists();
            return;
        }

        String[] names = new String[playlists.size()];
        for (int i = 0; i < playlists.size(); i++) names[i] = playlists.get(i).name;

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Añadir a playlist")
            .setItems(names, (dialog, which) -> {
                viewModel.addSongToPlaylist(playlists.get(which).id, song);
                Toast.makeText(requireContext(), "Añadido a " + names[which], Toast.LENGTH_SHORT).show();
            })
            .show();
    }
}
