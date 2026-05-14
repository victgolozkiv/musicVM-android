package com.musicplayer.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.jvm.JvmField

@Entity(tableName = "personalized_feed")
data class FeedSong(
    @PrimaryKey(autoGenerate = true)
    @JvmField var id: Int = 0,
    @JvmField var songId: String = "",
    @JvmField var title: String? = null,
    @JvmField var artist: String? = null,
    @JvmField var url: String? = null,
    @JvmField var duration: Long = 0,
    @JvmField var thumbnailUrl: String? = null,
    @JvmField var seedKeyword: String? = null,
    @JvmField var createdAt: Long = 0,
    @JvmField var cachedStreamUrl: String? = null,
    @JvmField var cachedAt: Long = 0,
    @JvmField var isHeader: Boolean = false
) {
    companion object {
        @JvmStatic
        fun createHeader(title: String): FeedSong {
            return FeedSong(
                songId = "header_" + System.currentTimeMillis(),
                title = title,
                artist = "",
                url = "",
                duration = 0,
                thumbnailUrl = "",
                seedKeyword = "",
                createdAt = System.currentTimeMillis(),
                isHeader = true
            )
        }
    }
}
