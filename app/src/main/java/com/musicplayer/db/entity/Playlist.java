package com.musicplayer.db.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "playlists")
public class Playlist {
    @PrimaryKey(autoGenerate = true)
    public int id;
    
    public String name;
    public long createdAt;

    public Playlist(String name, long createdAt) {
        this.name = name;
        this.createdAt = createdAt;
    }
}
