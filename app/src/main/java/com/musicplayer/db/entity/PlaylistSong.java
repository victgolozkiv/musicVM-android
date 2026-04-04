package com.musicplayer.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;

@Entity(tableName = "playlist_songs", primaryKeys = {"playlistId", "songId"})
public class PlaylistSong {
    @NonNull
    public int playlistId; // Aunque int no es nulo, Room lo pide a veces para claves primarias compuestas
    @NonNull
    public String songId;
    public String title;
    public String artist;
    public String thumbnailUrl;
    public String url;
    public boolean isLocal;

    public PlaylistSong(int playlistId, String songId, String title, String artist, String thumbnailUrl, String url, boolean isLocal) {
        this.playlistId = playlistId;
        this.songId = songId;
        this.title = title;
        this.artist = artist;
        this.thumbnailUrl = thumbnailUrl;
        this.url = url;
        this.isLocal = isLocal;
    }
}
