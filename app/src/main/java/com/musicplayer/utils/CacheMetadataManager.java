package com.musicplayer.utils;

import android.content.Context;
import android.content.SharedPreferences;
import com.musicplayer.model.Song;
import org.json.JSONObject;

public class CacheMetadataManager {
    private static final String PREF_NAME = "cache_metadata";

    public static void saveMetadata(Context context, Song song) {
        if (context == null || song == null || song.getUrl() == null) return;
        
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        try {
            JSONObject obj = new JSONObject();
            obj.put("id", song.getId());
            obj.put("title", song.getTitle());
            obj.put("artist", song.getArtist());
            obj.put("url", song.getUrl());
            obj.put("duration", song.getDuration());
            obj.put("thumbnailUrl", song.getThumbnailUrl());
            
            // Usamos la URL original como clave principal, ya que es lo que se envía al CacheDataSource
            prefs.edit().putString(song.getUrl(), obj.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static Song getMetadata(Context context, String url) {
        if (context == null || url == null) return null;
        
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(url, null);
        if (json == null) return null;
        
        try {
            JSONObject obj = new JSONObject(json);
            Song song = new Song(
                obj.optString("id"),
                obj.optString("title"),
                obj.optString("artist"),
                obj.optString("url"),
                obj.optInt("duration"),
                obj.optString("thumbnailUrl")
            );
            song.setLocal(false); // Es remotamente local, pero es cacheado
            return song;
        } catch (Exception e) {
            return null;
        }
    }
}
