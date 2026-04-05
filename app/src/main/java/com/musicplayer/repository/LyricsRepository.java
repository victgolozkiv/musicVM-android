package com.musicplayer.repository;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.io.IOException;

public class LyricsRepository {
    private static final String TAG = "LyricsRepository";
    private static LyricsRepository instance;
    private final OkHttpClient client;

    private LyricsRepository() {
        this.client = new OkHttpClient.Builder().build();
    }

    public static synchronized LyricsRepository getInstance() {
        if (instance == null) {
            instance = new LyricsRepository();
        }
        return instance;
    }

    public interface LyricsCallback {
        void onSuccess(String syncedLyrics, String plainLyrics);
        void onError(Exception e);
    }

    public void fetchLyrics(String title, String artist, LyricsCallback callback) {
        // Limpiar título de tags innecesarias (Official Video, etc) que estropean la búsqueda
        String cleanTitle = title.replaceAll("(?i)\\(.*\\)", "")
                                 .replaceAll("(?i)\\[.*\\]", "").trim();
        String cleanArtist = artist.replace(" - Topic", "").trim();

        // 1. Intentar GET directo
        String urlPattern = "https://lrclib.net/api/get?track_name=%s&artist_name=%s";
        String directUrl = String.format(urlPattern, android.net.Uri.encode(cleanTitle), android.net.Uri.encode(cleanArtist));

        Request request = new Request.Builder()
                .url(directUrl)
                .header("User-Agent", "JavaMusicPlayer/1.0.0")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                fallbackSearch(cleanTitle + " " + cleanArtist, callback);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String json = response.body().string();
                        JSONObject obj = new JSONObject(json);
                        String syncedList = obj.optString("syncedLyrics", "");
                        String plainList = obj.optString("plainLyrics", "");

                        if (!syncedList.isEmpty() || !plainList.isEmpty()) {
                            callback.onSuccess(syncedList, plainList);
                            return;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing GET: ", e);
                    }
                }
                // Si GET no fue exitoso o no trajo letras, intentar buscador
                fallbackSearch(cleanTitle + " " + cleanArtist, callback);
            }
        });
    }

    private void fallbackSearch(String query, LyricsCallback callback) {
        String searchUrl = "https://lrclib.net/api/search?q=" + android.net.Uri.encode(query);
        Request request = new Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "JavaMusicPlayer/1.0.0")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String json = response.body().string();
                        JSONArray array = new JSONArray(json);
                        for (int i = 0; i < array.length(); i++) {
                            JSONObject obj = array.getJSONObject(i);
                            String syncedList = obj.optString("syncedLyrics", "");
                            String plainList = obj.optString("plainLyrics", "");
                            
                            // Preferimos synced
                            if (!syncedList.isEmpty()) {
                                callback.onSuccess(syncedList, plainList);
                                return;
                            }
                        }
                        
                        // Si no hay synced, retornar plain de la primera si existe
                        if (array.length() > 0) {
                            JSONObject first = array.getJSONObject(0);
                            String plainList = first.optString("plainLyrics", "");
                            if (!plainList.isEmpty()) {
                                callback.onSuccess("", plainList);
                                return;
                            }
                        }
                        
                        callback.onError(new Exception("No lyrics found"));
                    } catch (Exception e) {
                        callback.onError(e);
                    }
                } else {
                    callback.onError(new Exception("API Error " + response.code()));
                }
            }
        });
    }
}
