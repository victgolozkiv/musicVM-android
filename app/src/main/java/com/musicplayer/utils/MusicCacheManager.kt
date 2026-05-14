package com.musicplayer.utils

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

class MusicCacheManager private constructor(context: Context) {
    var cache: SimpleCache? = null
        private set

    init {
        val cacheDir = File(context.applicationContext.cacheDir, "media3_cache")
        val evictor = LeastRecentlyUsedCacheEvictor(500 * 1024 * 1024) // 500 MB max
        val databaseProvider = StandaloneDatabaseProvider(context.applicationContext)
        cache = SimpleCache(cacheDir, evictor, databaseProvider)
    }

    companion object {
        @Volatile
        private var instance: MusicCacheManager? = null

        @JvmStatic
        fun getInstance(context: Context): MusicCacheManager {
            return instance ?: synchronized(this) {
                instance ?: MusicCacheManager(context).also { instance = it }
            }
        }
    }

    fun remove(key: String?) {
        if (cache != null && key != null) {
            cache!!.removeResource(key)
        }
    }

    fun clearCache() {
        cache?.let {
            val keys = it.keys
            for (key in keys) {
                it.removeResource(key)
            }
        }
    }

    fun release() {
        cache?.let {
            it.release()
            cache = null
        }
        instance = null
    }
}
