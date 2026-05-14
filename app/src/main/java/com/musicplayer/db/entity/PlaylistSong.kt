package com.musicplayer.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import kotlin.jvm.JvmField

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    foreignKeys = [ForeignKey(
        entity = Playlist::class,
        parentColumns = ["id"],
        childColumns = ["playlistId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("playlistId"), Index("songId")]
)
data class PlaylistSong(
    @JvmField var playlistId: Int = 0,
    @JvmField var songId: String = "",
    @JvmField var title: String? = null,
    @JvmField var artist: String? = null,
    @JvmField var thumbnailUrl: String? = null,
    @JvmField var url: String? = null,
    @JvmField var isLocal: Boolean = false,
    @JvmField var cachedStreamUrl: String? = null,
    @JvmField var cachedAt: Long = 0
)
