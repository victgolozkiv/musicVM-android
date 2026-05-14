package com.musicplayer.utils

import android.content.Context
import com.musicplayer.model.Song
import org.json.JSONObject

object CacheMetadataManager {
    private const val PREF_NAME = "cache_metadata"

    @JvmStatic
    fun saveMetadata(context: Context?, song: Song?) {
        if (context == null || song == null || song.url == null) return
        
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        try {
            val obj = JSONObject()
            obj.put("id", song.id)
            obj.put("title", song.title)
            obj.put("artist", song.artist)
            obj.put("url", song.url)
            obj.put("duration", song.duration)
            obj.put("thumbnailUrl", song.thumbnailUrl)
            
            prefs.edit().putString(song.url, obj.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @JvmStatic
    fun getMetadata(context: Context?, url: String?): Song? {
        if (context == null || url == null) return null
        
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(url, null) ?: return null
        
        return try {
            val obj = JSONObject(json)
            Song(
                obj.optString("id"),
                obj.optString("title"),
                obj.optString("artist"),
                obj.optString("url"),
                obj.optLong("duration", 0),
                obj.optString("thumbnailUrl")
            ).apply {
                isLocal = false
            }
        } catch (e: Exception) {
            null
        }
    }

    @JvmStatic
    fun getAllCachedSongs(context: Context?): List<Song> {
        val songs = mutableListOf<Song>()
        if (context == null) return songs
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        for (entry in prefs.all.entries) {
            try {
                val json = entry.value as String
                val obj = JSONObject(json)
                songs.add(
                    Song(
                        obj.optString("id"),
                        obj.optString("title"),
                        obj.optString("artist"),
                        obj.optString("url"),
                        obj.optLong("duration", 0),
                        obj.optString("thumbnailUrl")
                    ).apply {
                        isLocal = false
                    }
                )
            } catch (ignored: Exception) {}
        }
        return songs
    }
}
