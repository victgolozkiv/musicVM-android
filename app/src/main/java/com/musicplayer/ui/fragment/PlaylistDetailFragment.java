package com.musicplayer.ui.fragment;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
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

public class PlaylistDetailFragment extends Fragment {
    private static final String TAG = "DEBUG_PLAYER";
    private static final String ARG_PLAYLIST_ID = "playlist_id";
    private static final String ARG_PLAYLIST_NAME = "playlist_name";

    private int playlistId;
    private String playlistName;
    private MusicViewModel viewModel;
    private SongAdapter adapter;

    public static PlaylistDetailFragment newInstance(int id, String name) {
        PlaylistDetailFragment fragment = new PlaylistDetailFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_PLAYLIST_ID, id);
        args.putString(ARG_PLAYLIST_NAME, name);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            playlistId = getArguments().getInt(ARG_PLAYLIST_ID);
            playlistName = getArguments().getString(ARG_PLAYLIST_NAME);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_playlist_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        viewModel = new ViewModelProvider(requireActivity()).get(MusicViewModel.class);
        
        TextView tvName = view.findViewById(R.id.tvPlaylistName);
        tvName.setText(playlistName);
        
        ImageButton btnBack = view.findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> requireActivity().getSupportFragmentManager().popBackStack());

        RecyclerView rv = view.findViewById(R.id.rvPlaylistSongs);
        rv.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        
        adapter = new SongAdapter(new SongAdapter.OnSongActionListener() {
            @Override
            public void onPlay(Song song) {
                List<Song> currentList = adapter.getSongs();
                int index = currentList.indexOf(song);
                viewModel.setPlaylistAndPlay(currentList, index);
                
                getParentFragmentManager().beginTransaction()
                    .setCustomAnimations(R.anim.slide_up, 0, 0, R.anim.slide_down)
                    .add(android.R.id.content, new PlayerFragment())
                    .addToBackStack(null)
                    .commit();
            }

            @Override
            public void onDownload(Song song) {
                viewModel.getStreamUrlForDownload(song);
            }

            @Override
            public void onDelete(Song song) {
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Eliminar de Playlist")
                    .setMessage("¿Eliminar \"" + song.getTitle() + "\" de esta lista?")
                    .setPositiveButton("Eliminar", (dialog, which) -> {
                        viewModel.removeSongFromPlaylist(playlistId, song.getId());
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
            }

            @Override
            public void onAddToPlaylist(Song song) {
                Toast.makeText(requireContext(), "Ya estás en esta playlist", Toast.LENGTH_SHORT).show();
            }
        });
        adapter.setPlaylistMode(true);
        rv.setAdapter(adapter);

        TextView tvEmpty = view.findViewById(R.id.tvEmptyMessage);

        viewModel.getPlaylistSongs().observe(getViewLifecycleOwner(), songs -> {
            if (songs != null) {
                adapter.updateSongs(songs);
                tvEmpty.setVisibility(songs.isEmpty() ? View.VISIBLE : View.GONE);
            }
        });

        viewModel.getDownloadUrl().observe(getViewLifecycleOwner(), url -> {
            if (url != null) {
                DownloadHelper.downloadSong(requireContext(), viewModel.getSelectedSongForDownload(), url);
                viewModel.clearDownloadUrl();
            }
        });

        // Cargar las canciones de la playlist
        viewModel.loadSongsFromPlaylist(playlistId);
    }
}
