package com.musicplayer.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.jvm.JvmField

@Entity(tableName = "playback_history")
data class PlaybackHistory(
    @PrimaryKey
    @JvmField var songId: String = "",
    @JvmField var title: String? = null,
    @JvmField var artist: String? = null,
    @JvmField var url: String? = null,
    @JvmField var duration: Long = 0,
    @JvmField var thumbnailUrl: String? = null,
    @JvmField var timestamp: Long = 0
)
