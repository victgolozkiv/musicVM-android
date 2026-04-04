package com.musicplayer.ui.viewmodel;

import android.app.Application;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.musicplayer.model.Song;
import com.musicplayer.repository.MusicRepository;
import com.musicplayer.db.AppDatabase;
import com.musicplayer.db.MusicDao;
import com.musicplayer.db.entity.Playlist;
import com.musicplayer.db.entity.PlaylistSong;
import java.util.ArrayList;
import java.util.List;

public class MusicViewModel extends AndroidViewModel {
    private static final String TAG = "DEBUG_PLAYER";
    private final MusicRepository repository;
    private final MusicDao musicDao;
    
    private final MutableLiveData<List<Song>> songs = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<List<Song>> searchResults = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<PlaylistPlayRequest> playRequest = new MutableLiveData<>();
    private final MutableLiveData<String> downloadUrl = new MutableLiveData<>();
    private final MutableLiveData<String> error = new MutableLiveData<>();
    private final MutableLiveData<Song> selectedSong = new MutableLiveData<>();
    private final MutableLiveData<List<Playlist>> playlists = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<List<Song>> playlistSongs = new MutableLiveData<>(new ArrayList<>());
    
    public MusicViewModel(@NonNull Application application) {
        super(application);
        this.repository = MusicRepository.getInstance(application);
        this.musicDao = AppDatabase.getInstance(application).musicDao();
    }
    
    public static class PlaylistPlayRequest {
        public final List<Song> playlist;
        public final int index;
        public PlaylistPlayRequest(List<Song> playlist, int index) {
            this.playlist = playlist;
            this.index = index;
        }
    }

    private List<Song> currentPlaylist = new ArrayList<>();
    private int currentIndex = -1;
    private Song selectedSongForDownload;

    public LiveData<List<Song>> getSongs() { return songs; }
    public LiveData<List<Song>> getSearchResults() { return searchResults; }
    public LiveData<PlaylistPlayRequest> getPlayRequest() { return playRequest; }
    public LiveData<String> getDownloadUrl() { return downloadUrl; }
    public LiveData<String> getError() { return error; }
    public LiveData<Song> getSelectedSong() { return selectedSong; }
    public LiveData<List<Playlist>> getPlaylists() { return playlists; }
    public LiveData<List<Song>> getPlaylistSongs() { return playlistSongs; }
    public Song getSelectedSongForDownload() { return selectedSongForDownload; }

    public void loadSongs() {
        Log.d(TAG, "ViewModel: Cargando recomendadas...");
        songs.postValue(new ArrayList<>()); // Limpiar antes de cargar
        repository.loadSongs(new MusicRepository.MusicCallback() {
            @Override
            public void onSuccess(List<Song> songList) {
                songs.postValue(songList);
            }
            @Override
            public void onError(Exception e) {
                error.postValue(e.getMessage());
            }
        });
    }

    public void searchSongs(String query) {
        repository.searchSongs(query, new MusicRepository.MusicCallback() {
            @Override
            public void onSuccess(List<Song> songList) {
                searchResults.postValue(songList);
            }
            @Override
            public void onError(Exception e) {
                error.postValue(e.getMessage());
            }
        });
    }

    public void setPlaylistAndPlay(List<Song> playlist, int index) {
        if (playlist == null || index < 0 || index >= playlist.size()) return;
        this.currentPlaylist = new ArrayList<>(playlist);
        this.currentIndex = index;
        this.selectedSong.postValue(currentPlaylist.get(currentIndex));
        this.playRequest.postValue(new PlaylistPlayRequest(currentPlaylist, index));
    }

    public void playSongWithRadio(Song song) {
        if (song == null) return;
        List<Song> singleSongList = new ArrayList<>();
        singleSongList.add(song);
        this.currentPlaylist = singleSongList;
        this.currentIndex = 0;
        this.selectedSong.postValue(song);
        
        // 1. Iniciar reproducción del tema principal
        this.playRequest.postValue(new PlaylistPlayRequest(singleSongList, 0));
        
        // 2. Cargar temas relacionados de inmediato para habilitar botones "Siguiente"
        repository.getRelatedSongs(song.getUrl(), new MusicRepository.MusicCallback() {
            @Override
            public void onSuccess(List<Song> related) {
                if (related != null && !related.isEmpty()) {
                    // Añadir a la lista actual (ViewModel) para consistencia
                    synchronized (currentPlaylist) {
                        currentPlaylist.addAll(related);
                    }
                    // Notificar al reproductor para que actualice su cola
                    playRequest.postValue(new PlaylistPlayRequest(currentPlaylist, 0));
                    // No cambiamos el índice, seguimos en 0 (la canción actual)
                }
            }
            @Override public void onError(Exception e) {
                Log.e(TAG, "Error precargando radio", e);
            }
        });
    }

    public void updateSelectedSong(Song song) {
        this.selectedSong.postValue(song);
        // Actualizar el índice si la canción está en la playlist actual
        for (int i = 0; i < currentPlaylist.size(); i++) {
            if (currentPlaylist.get(i).getId().equals(song.getId())) {
                this.currentIndex = i;
                break;
            }
        }
    }

    public void clearPlayRequest() {
        playRequest.postValue(null);
    }

    public boolean hasPlaylist() {
        return !currentPlaylist.isEmpty();
    }

    public void getStreamUrlForDownload(Song song) {
        this.selectedSongForDownload = song;
        // Para descargar con YoutubeDL internamente, pasamos la URL original de YouTube,
        // no el stream efímero extríado.
        downloadUrl.postValue(song.getUrl());
    }

    public void clearDownloadUrl() {
        downloadUrl.postValue(null);
    }

    // --- PLAYLIST MANAGEMENT ---

    public void loadPlaylists() {
        new Thread(() -> {
            playlists.postValue(musicDao.getAllPlaylists());
        }).start();
    }

    public void createPlaylist(String name) {
        new Thread(() -> {
            musicDao.insertPlaylist(new Playlist(name, System.currentTimeMillis()));
            loadPlaylists();
        }).start();
    }

    public void addSongToPlaylist(int playlistId, Song song) {
        new Thread(() -> {
            musicDao.insertPlaylistSong(new PlaylistSong(
                playlistId,
                song.getId(),
                song.getTitle(),
                song.getArtist(),
                song.getThumbnailUrl(),
                song.getUrl(),
                song.isLocal()
            ));
            // No cargamos de nuevo aquí para evitar saltos bruscos si el usuario está en otra pantalla
        }).start();
    }

    public void removeSongFromPlaylist(int playlistId, String songId) {
        new Thread(() -> {
            musicDao.removeSongFromPlaylist(playlistId, songId);
            loadSongsFromPlaylist(playlistId); // Recargar la lista de la playlist
        }).start();
    }

    public void deletePlaylist(Playlist playlist) {
        new Thread(() -> {
            musicDao.deletePlaylist(playlist);
            loadPlaylists(); // Recargar la lista de playlists
        }).start();
    }

    public void loadSongsFromPlaylist(int playlistId) {
        Log.d(TAG, "ViewModel: Cargando playlist ID " + playlistId);
        playlistSongs.postValue(new ArrayList<>()); // Limpiar para dar feedback visual
        new Thread(() -> {
            List<PlaylistSong> pSongs = musicDao.getSongsForPlaylist(playlistId);
            Log.d(TAG, "ViewModel: Encontradas " + (pSongs != null ? pSongs.size() : 0) + " canciones en DB");
            List<Song> songList = new ArrayList<>();
            if (pSongs != null) {
                for (PlaylistSong ps : pSongs) {
                    Song s = new Song(ps.songId, ps.title, ps.artist, ps.url, 0, ps.thumbnailUrl);
                    s.setLocal(ps.isLocal);
                    songList.add(s);
                }
            }
            playlistSongs.postValue(songList);
        }).start();
    }
    
    public void clearSongs() {
        songs.postValue(new ArrayList<>());
    }
}
