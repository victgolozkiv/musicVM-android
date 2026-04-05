package com.musicplayer.db;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.musicplayer.db.entity.Playlist;
import com.musicplayer.db.entity.PlaylistSong;
import com.musicplayer.db.entity.SearchHistory;
import java.util.List;

@Dao
public interface MusicDao {
    // Playlists
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertPlaylist(Playlist playlist);
    
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    List<Playlist> getAllPlaylists();
    
    @Delete
    void deletePlaylist(Playlist playlist);

    // Playlist Songs
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertPlaylistSong(PlaylistSong song);
    
    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId")
    List<PlaylistSong> getSongsForPlaylist(int playlistId);
    
    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    void removeSongFromPlaylist(int playlistId, String songId);

    // Search History
    @Insert
    void insertSearch(SearchHistory history);
    
    @Query("SELECT keyword FROM search_history ORDER BY timestamp DESC LIMIT 10")
    List<String> getRecentKeywords();

    @Query("SELECT keyword FROM search_history GROUP BY keyword ORDER BY COUNT(*) DESC LIMIT 10")
    List<String> getTopKeywords();
}
