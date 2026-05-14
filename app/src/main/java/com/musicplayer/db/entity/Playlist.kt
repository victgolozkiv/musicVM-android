package com.musicplayer.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.jvm.JvmField

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true)
    @JvmField var id: Int = 0,
    @JvmField var name: String? = null,
    @JvmField var createdAt: Long = 0
)
