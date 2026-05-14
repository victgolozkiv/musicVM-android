package com.musicplayer.db

import androidx.room.*
import com.musicplayer.db.entity.*

@Dao
interface MusicDao {
    // Playlists
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: Playlist): Long
    
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    suspend fun getAllPlaylists(): List<Playlist>
    
    @Delete
    suspend fun deletePlaylist(playlist: Playlist)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylistById(playlistId: Int)

    // Playlist Songs
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistSong(song: PlaylistSong)
    
    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun getSongsForPlaylist(playlistId: Int): List<PlaylistSong>
    
    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: Int, songId: String)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun deleteSongsByPlaylistId(playlistId: Int)

    // Search History
    @Insert
    suspend fun insertSearch(history: SearchHistory)
    
    @Query("SELECT keyword FROM search_history ORDER BY timestamp DESC LIMIT 10")
    suspend fun getRecentKeywords(): List<String>

    @Query("SELECT keyword FROM search_history GROUP BY keyword ORDER BY COUNT(*) DESC LIMIT 10")
    suspend fun getTopKeywords(): List<String>

    // Personalized Feed Buffer
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFeedSongs(songs: List<FeedSong>)

    @Query("SELECT * FROM personalized_feed ORDER BY createdAt DESC")
    suspend fun getFeedSongs(): List<FeedSong>

    @Query("DELETE FROM personalized_feed")
    suspend fun clearFeed()

    @Query("SELECT COUNT(*) FROM personalized_feed")
    suspend fun getFeedCount(): Int

    @Query("SELECT * FROM personalized_feed WHERE songId = :songId LIMIT 1")
    suspend fun getFeedSongBySongId(songId: String): FeedSong?

    @Query("UPDATE personalized_feed SET cachedStreamUrl = :streamUrl, cachedAt = :timestamp WHERE songId = :songId")
    suspend fun updateFeedSongStreamUrl(songId: String, streamUrl: String?, timestamp: Long)

    @Query("SELECT cachedStreamUrl FROM personalized_feed WHERE songId = :songId AND cachedStreamUrl IS NOT NULL")
    suspend fun getCachedFeedStreamUrl(songId: String): String?

    @Query("UPDATE playlist_songs SET cachedStreamUrl = :streamUrl, cachedAt = :timestamp WHERE songId = :songId")
    suspend fun updatePlaylistSongStreamUrl(songId: String, streamUrl: String?, timestamp: Long)

    @Query("SELECT cachedStreamUrl FROM playlist_songs WHERE songId = :songId AND cachedStreamUrl IS NOT NULL")
    suspend fun getCachedPlaylistStreamUrl(songId: String): String?

    @Query("SELECT * FROM playlist_songs")
    suspend fun getAllPlaylistSongs(): List<PlaylistSong>

    @Query("SELECT * FROM playlist_songs WHERE songId = :songId LIMIT 1")
    suspend fun getPlaylistSongBySongId(songId: String): PlaylistSong?

    // Playback History
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaybackHistory(history: PlaybackHistory)

    @Query("SELECT * FROM playback_history ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastPlayed(): PlaybackHistory?

    @Query("SELECT * FROM playback_history GROUP BY artist ORDER BY timestamp DESC LIMIT 3")
    suspend fun getRecentHistory(): List<PlaybackHistory>
}
