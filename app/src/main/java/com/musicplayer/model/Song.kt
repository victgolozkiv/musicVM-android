package com.musicplayer.model

data class Song(
    val id: String,
    val title: String,
    var artist: String,
    val url: String,
    val duration: Long,
    val thumbnailUrl: String
) {
    var streamUrl: String? = null
    var isLocal: Boolean = false
    var isHeader: Boolean = false

    companion object {
        @JvmStatic
        fun createHeader(title: String): Song {
            return Song("header_${System.currentTimeMillis()}", title, "", "", 0, "").apply {
                isHeader = true
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Song) return false
        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}
