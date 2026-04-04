package com.musicplayer.ui.fragment;

import android.content.ContentUris;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
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
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class DownloadsFragment extends Fragment {
    private static final String TAG = "DownloadsFragment";
    private MusicViewModel viewModel;
    private SongAdapter adapter;
    private TextView tvNoDownloads;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_downloads, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Log.d(TAG, "onViewCreated: Iniciando vista de descargas");
        
        viewModel = new ViewModelProvider(requireActivity()).get(MusicViewModel.class);
        tvNoDownloads = view.findViewById(R.id.tvNoDownloads);
        RecyclerView recyclerView = view.findViewById(R.id.downloadsRecyclerView);
        
        recyclerView.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        adapter = new SongAdapter(new SongAdapter.OnSongActionListener() {
            @Override
            public void onPlay(Song song) {
                try {
                    if (song == null) return;
                    Log.d(TAG, "onPlay: Reproduciendo descarga local: " + song.getTitle());
                    
                    List<Song> currentList = adapter.getSongs();
                    if (currentList == null || currentList.isEmpty()) {
                        Log.e(TAG, "onPlay: Lista de canciones vacía o nula");
                        return;
                    }
                    
                    int index = currentList.indexOf(song);
                    if (index == -1) {
                        // Fallback por ID si el objeto no es el mismo
                        for (int i = 0; i < currentList.size(); i++) {
                            if (currentList.get(i).getId().equals(song.getId())) {
                                index = i;
                                break;
                            }
                        }
                    }
                    if (index == -1) index = 0;
                    
                    viewModel.setPlaylistAndPlay(currentList, index);

                    getParentFragmentManager().beginTransaction()
                        .setCustomAnimations(R.anim.slide_up, 0, 0, R.anim.slide_down)
                        .add(android.R.id.content, new PlayerFragment())
                        .addToBackStack(null)
                        .commit();
                } catch (Exception e) {
                    Log.e(TAG, "DownloadsFragment: Error fatal en onPlay", e);
                }
            }

            @Override
            public void onDownload(Song song) {
                // Ya es local
            }

            @Override
            public void onDelete(Song song) {
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Eliminar canción")
                    .setMessage("¿Estás seguro de que quieres eliminar \"" + song.getTitle() + "\"? Se borrará permanentemente del dispositivo.")
                    .setPositiveButton("Eliminar", (dialog, which) -> deleteSong(song))
                    .setNegativeButton("Cancelar", null)
                    .show();
            }

            @Override
            public void onAddToPlaylist(Song song) {
                showAddToPlaylistDialog(song);
            }
        });
        
        recyclerView.setAdapter(adapter);
        loadLocalSongs();
    }

    private android.content.BroadcastReceiver downloadReceiver;

    @Override
    public void onStart() {
        super.onStart();
        downloadReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                Log.d(TAG, "onReceive: Descarga completada, refrescando lista");
                loadLocalSongs();
            }
        };
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
                .registerReceiver(downloadReceiver, new android.content.IntentFilter("com.musicplayer.DOWNLOAD_COMPLETE"));
    }

    @Override
    public void onStop() {
        super.onStop();
        if (downloadReceiver != null) {
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
                    .unregisterReceiver(downloadReceiver);
        }
    }

    private void loadLocalSongs() {
        List<Song> localSongs = new ArrayList<>();
        
        try {
            android.content.ContentResolver contentResolver = requireContext().getContentResolver();
            android.net.Uri uri = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                String[] projection = {
                    android.provider.MediaStore.Audio.Media._ID,
                    android.provider.MediaStore.Audio.Media.TITLE,
                    android.provider.MediaStore.Audio.Media.ARTIST,
                    android.provider.MediaStore.Audio.Media.DATA,
                    android.provider.MediaStore.Audio.Media.DURATION,
                    android.provider.MediaStore.Audio.Media.ALBUM_ID
                };
                
                // Filtrar solo los archivos que Android identifica como "Música" (excluye tonos y notas de voz)
                String selection = android.provider.MediaStore.Audio.Media.IS_MUSIC + " != 0";
                String sortOrder = android.provider.MediaStore.Audio.Media.DATE_ADDED + " DESC";
                
                try (android.database.Cursor cursor = contentResolver.query(uri, projection, selection, null, sortOrder)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media._ID);
                        int titleCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.TITLE);
                        int artistCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.ARTIST);
                        int dataCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATA);
                        int durationCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DURATION);
                        int albumIdCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.ALBUM_ID);
                        
                        do {
                            long id = cursor.getLong(idCol);
                            String title = cursor.getString(titleCol);
                            String artist = cursor.getString(artistCol);
                            String data = cursor.getString(dataCol); 
                            int duration = cursor.getInt(durationCol);
                            long albumId = cursor.getLong(albumIdCol);
                            
                            if (data != null) {
                                java.io.File file = new java.io.File(data);
                                String uriStr = android.net.Uri.fromFile(file).toString();
                                
                                // Construir URI de la carátula del álbum
                                android.net.Uri sArtworkUri = android.net.Uri.parse("content://media/external/audio/albumart");
                                String thumbUri = android.content.ContentUris.withAppendedId(sArtworkUri, albumId).toString();
                                
                                Song song = new Song(
                                    String.valueOf(id),
                                    title != null && !title.isEmpty() ? title : file.getName(),
                                    artist != null && !artist.equals("<unknown>") ? artist : "Artista Desconocido",
                                    uriStr,
                                    duration,
                                    thumbUri
                                );
                            song.setLocal(true);
                            localSongs.add(song);
                        }
                    } while (cursor.moveToNext());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error al cargar canciones desde MediaStore", e);
        }
        
        Log.d(TAG, "loadLocalSongs: Encontradas " + localSongs.size() + " canciones en el dispositivo");
        
        if (localSongs.isEmpty()) {
            tvNoDownloads.setVisibility(View.VISIBLE);
            adapter.updateSongs(new ArrayList<>());
        } else {
            tvNoDownloads.setVisibility(View.GONE);
            adapter.updateSongs(localSongs);
        }
    }

    private void deleteSong(Song song) {
        try {
            // 1. Obtener la URI del MediaStore usando el ID
            long id = Long.parseLong(song.getId());
            android.net.Uri uri = android.content.ContentUris.withAppendedId(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
            
            // 2. Borrar del MediaStore (esto suele borrar el archivo físico también en versiones modernas de Android)
            int deletedRows = requireContext().getContentResolver().delete(uri, null, null);
            
            // 3. Respaldo: Borrar archivo físico si MediaStore no lo hizo (para compatibilidad)
            if (song.getUrl().startsWith("file://")) {
                String path = android.net.Uri.parse(song.getUrl()).getPath();
                if (path != null) {
                    java.io.File file = new java.io.File(path);
                    if (file.exists()) file.delete();
                }
            }

            android.widget.Toast.makeText(requireContext(), "Canción eliminada", android.widget.Toast.LENGTH_SHORT).show();
            loadLocalSongs(); // Refrescar lista
            
        } catch (Exception e) {
            Log.e(TAG, "Error al eliminar canción", e);
            android.widget.Toast.makeText(requireContext(), "Error al eliminar: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void showAddToPlaylistDialog(Song song) {
        viewModel.getPlaylists().observe(getViewLifecycleOwner(), playlists -> {
            if (playlists == null || playlists.isEmpty()) {
                android.widget.Toast.makeText(requireContext(), "No tienes playlists creadas. Ve a la pestaña de Playlists.", android.widget.Toast.LENGTH_LONG).show();
                return;
            }

            String[] names = new String[playlists.size()];
            for (int i = 0; i < playlists.size(); i++) names[i] = playlists.get(i).name;

            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Añadir a playlist")
                .setItems(names, (dialog, which) -> {
                    viewModel.addSongToPlaylist(playlists.get(which).id, song);
                    android.widget.Toast.makeText(requireContext(), "Añadido a " + names[which], android.widget.Toast.LENGTH_SHORT).show();
                })
                .show();
        });
        viewModel.loadPlaylists();
    }
}
