package com.musicplayer.db.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "search_history")
public class SearchHistory {
    @PrimaryKey(autoGenerate = true)
    public int id;
    
    public String keyword;
    public long timestamp;

    public SearchHistory(String keyword, long timestamp) {
        this.keyword = keyword;
        this.timestamp = timestamp;
    }
}
