package com.musicplayer.ui.fragment;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.textfield.TextInputEditText;
import com.musicplayer.R;
import com.musicplayer.model.Song;
import com.musicplayer.ui.adapter.SongAdapter;
import com.musicplayer.ui.viewmodel.MusicViewModel;
import com.musicplayer.utils.DownloadHelper;

public class SearchFragment extends Fragment {
    private MusicViewModel viewModel;
    private SongAdapter searchAdapter;
    private ProgressBar progressBar;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_search, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        viewModel = new ViewModelProvider(requireActivity()).get(MusicViewModel.class);
        
        TextInputEditText etSearch = view.findViewById(R.id.etSearch);
        progressBar = view.findViewById(R.id.searchProgressBar);
        RecyclerView recyclerView = view.findViewById(R.id.searchRecyclerView);
        
        recyclerView.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        
        searchAdapter = new SongAdapter(new SongAdapter.OnSongActionListener() {
            @Override
            public void onPlay(Song song) {
                Log.d("DEBUG_PLAYER", "Search: Iniciando Smart Radio para " + song.getTitle());
                
                // 1. Usar Smart Radio para descubrimiento del mismo género/artista
                viewModel.playSongWithRadio(song);
                
                // NAVEGAR AL REPRODUCTOR
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
                // No se espera borrar desde aquí
            }

            @Override
            public void onAddToPlaylist(Song song) {
                showAddToPlaylistDialog(song);
            }
        });
        
        recyclerView.setAdapter(searchAdapter);

        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                String query = etSearch.getText().toString();
                if (!query.isEmpty()) {
                    progressBar.setVisibility(View.VISIBLE);
                    viewModel.searchSongs(query);
                }
                return true;
            }
            return false;
        });

        viewModel.getSearchResults().observe(getViewLifecycleOwner(), songs -> {
            progressBar.setVisibility(View.GONE);
            searchAdapter.updateSongs(songs);
        });

        // Corregido también en SearchFragment: observar la URL de descarga
        viewModel.getDownloadUrl().observe(getViewLifecycleOwner(), url -> {
            if (url != null) {
                DownloadHelper.downloadSong(requireContext(), viewModel.getSelectedSongForDownload(), url);
                viewModel.clearDownloadUrl();
            }
        });

        viewModel.getError().observe(getViewLifecycleOwner(), errorMsg -> {
            if (errorMsg != null) {
                progressBar.setVisibility(View.GONE);
                Toast.makeText(requireContext(), "Error de búsqueda: " + errorMsg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showAddToPlaylistDialog(Song song) {
        viewModel.getPlaylists().observe(getViewLifecycleOwner(), playlists -> {
            if (playlists == null || playlists.isEmpty()) {
                Toast.makeText(requireContext(), "No tienes playlists creadas. Ve a la pestaña de Playlists.", Toast.LENGTH_LONG).show();
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
        });
        viewModel.loadPlaylists();
    }
}
