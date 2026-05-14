package com.musicplayer.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.musicplayer.db.entity.*

@Database(
    entities = [Playlist::class, PlaylistSong::class, SearchHistory::class, FeedSong::class, PlaybackHistory::class],
    version = 10,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun musicDao(): MusicDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `playback_history` (`songId` TEXT NOT NULL, `title` TEXT, `artist` TEXT, `url` TEXT, `duration` INTEGER NOT NULL, `thumbnailUrl` TEXT, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`songId`))")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `personalized_feed` ADD COLUMN `isHeader` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Reservado para futuras optimizaciones de índices
            }
        }

        @JvmStatic
        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "music_database"
                )
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
            }
        }
    }
}
