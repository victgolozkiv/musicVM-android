package com.musicplayer.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.musicplayer.R;
import com.musicplayer.db.entity.Playlist;
import com.musicplayer.ui.adapter.PlaylistAdapter;
import com.musicplayer.ui.viewmodel.MusicViewModel;

public class PlaylistsFragment extends Fragment {
    private MusicViewModel viewModel;
    private PlaylistAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_playlists, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(MusicViewModel.class);

        RecyclerView recyclerView = view.findViewById(R.id.rvPlaylists);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        
        adapter = new PlaylistAdapter(new PlaylistAdapter.OnPlaylistInteractionListener() {
            @Override
            public void onPlaylistClick(com.musicplayer.db.entity.Playlist playlist) {
                // Abrir detalle de la playlist con animación
                getParentFragmentManager().beginTransaction()
                    .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out, android.R.anim.fade_in, android.R.anim.fade_out)
                    .replace(R.id.fragmentContainer, PlaylistDetailFragment.newInstance(playlist.id, playlist.name))
                    .addToBackStack(null)
                    .commit();
            }

            @Override
            public void onPlaylistDelete(com.musicplayer.db.entity.Playlist playlist) {
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Eliminar Playlist")
                    .setMessage("¿Estás seguro de que quieres eliminar \"" + playlist.name + "\"?")
                    .setPositiveButton("Eliminar", (dialog, which) -> {
                        viewModel.deletePlaylist(playlist);
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
            }
        });
        recyclerView.setAdapter(adapter);

        view.findViewById(R.id.btnCreatePlaylist).setOnClickListener(v -> showCreatePlaylistDialog());

        viewModel.getPlaylists().observe(getViewLifecycleOwner(), playlists -> {
            adapter.updatePlaylists(playlists);
        });

        viewModel.loadPlaylists();
    }

    private void showCreatePlaylistDialog() {
        EditText input = new EditText(requireContext());
        new AlertDialog.Builder(requireContext())
            .setTitle("Nueva Playlist")
            .setMessage("Introduce el nombre:")
            .setView(input)
            .setPositiveButton("Crear", (dialog, which) -> {
                String name = input.getText().toString().trim();
                if (!name.isEmpty()) {
                    viewModel.createPlaylist(name);
                }
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }
}
