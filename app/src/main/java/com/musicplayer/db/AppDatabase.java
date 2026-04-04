package com.musicplayer.db;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import com.musicplayer.db.entity.Playlist;
import com.musicplayer.db.entity.PlaylistSong;
import com.musicplayer.db.entity.SearchHistory;

@Database(entities = {Playlist.class, PlaylistSong.class, SearchHistory.class}, version = 1)
public abstract class AppDatabase extends RoomDatabase {
    private static AppDatabase instance;
    public abstract MusicDao musicDao();

    public static synchronized AppDatabase getInstance(Context context) {
        if (instance == null) {
            instance = Room.databaseBuilder(context.getApplicationContext(),
                    AppDatabase.class, "music_database")
                    .fallbackToDestructiveMigration()
                    .build();
        }
        return instance;
    }
}
