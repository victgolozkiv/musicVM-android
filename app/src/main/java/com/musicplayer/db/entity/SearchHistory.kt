package com.musicplayer.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.jvm.JvmField

@Entity(
    tableName = "search_history",
    indices = [Index("keyword"), Index("timestamp")]
)
data class SearchHistory(
    @PrimaryKey(autoGenerate = true)
    @JvmField var id: Int = 0,
    @JvmField var keyword: String? = null,
    @JvmField var timestamp: Long = 0
)
