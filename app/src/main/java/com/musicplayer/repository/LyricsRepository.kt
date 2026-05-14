package com.musicplayer.repository

import android.util.Log
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class LyricsRepository private constructor() {
    private val client: OkHttpClient = OkHttpClient.Builder().build()

    companion object {
        private const val TAG = "LyricsRepository"
        
        @Volatile
        private var instance: LyricsRepository? = null

        @JvmStatic
        fun getInstance(): LyricsRepository {
            return instance ?: synchronized(this) {
                instance ?: LyricsRepository().also { instance = it }
            }
        }
    }

    interface LyricsCallback {
        fun onSuccess(syncedLyrics: String?, plainLyrics: String?)
        fun onError(e: Exception)
    }

    fun fetchLyrics(title: String, artist: String, callback: LyricsCallback) {
        // Limpiar título de tags innecesarias (Official Video, etc) que estropean la búsqueda
        val cleanTitle = title.replace("(?i)\\(.*\\)".toRegex(), "")
            .replace("(?i)\\[.*\\]".toRegex(), "").trim()
        val cleanArtist = artist.replace(" - Topic", "").trim()

        // 1. Intentar GET directo
        val urlPattern = "https://lrclib.net/api/get?track_name=%s&artist_name=%s"
        val directUrl = String.format(urlPattern, android.net.Uri.encode(cleanTitle), android.net.Uri.encode(cleanArtist))

        val request = Request.Builder()
            .url(directUrl)
            .header("User-Agent", "JavaMusicPlayer/1.0.0")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                fallbackSearch(cleanTitle + " " + cleanArtist, callback)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val json = response.body?.string()
                            if (json != null) {
                                val obj = JSONObject(json)
                                val syncedList = obj.optString("syncedLyrics", "")
                                val plainList = obj.optString("plainLyrics", "")

                                if (syncedList.isNotEmpty() || plainList.isNotEmpty()) {
                                    callback.onSuccess(syncedList, plainList)
                                    return
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing GET: ", e)
                        }
                    }
                    // Si GET no fue exitoso o no trajo letras, intentar buscador
                    fallbackSearch("$cleanTitle $cleanArtist", callback)
                }
            }
        })
    }

    private fun fallbackSearch(query: String, callback: LyricsCallback) {
        val searchUrl = "https://lrclib.net/api/search?q=" + android.net.Uri.encode(query)
        val request = Request.Builder()
            .url(searchUrl)
            .header("User-Agent", "JavaMusicPlayer/1.0.0")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback.onError(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val json = response.body?.string()
                            if (json != null) {
                                val array = JSONArray(json)
                                for (i in 0 until array.length()) {
                                    val obj = array.getJSONObject(i)
                                    val syncedList = obj.optString("syncedLyrics", "")
                                    val plainList = obj.optString("plainLyrics", "")
                                    
                                    // Preferimos synced
                                    if (syncedList.isNotEmpty()) {
                                        callback.onSuccess(syncedList, plainList)
                                        return
                                    }
                                }
                                
                                // Si no hay synced, retornar plain de la primera si existe
                                if (array.length() > 0) {
                                    val first = array.getJSONObject(0)
                                    val plainList = first.optString("plainLyrics", "")
                                    if (plainList.isNotEmpty()) {
                                        callback.onSuccess("", plainList)
                                        return
                                    }
                                }
                                
                                callback.onError(Exception("No lyrics found"))
                            } else {
                                callback.onError(Exception("Empty body"))
                            }
                        } catch (e: Exception) {
                            callback.onError(e)
                        }
                    } else {
                        callback.onError(Exception("API Error ${response.code}"))
                    }
                }
            }
        })
    }
}
