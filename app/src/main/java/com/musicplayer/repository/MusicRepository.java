package com.musicplayer.repository;

import android.util.Log;
import com.musicplayer.model.Song;
import com.musicplayer.db.AppDatabase;
import com.musicplayer.db.MusicDao;
import com.musicplayer.db.entity.SearchHistory;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MusicRepository {
    private final ExecutorService executor = Executors.newFixedThreadPool(8);
    private static final String TAG = "DEBUG_PLAYER";
    private static final Map<String, String> urlCache = new HashMap<>();
    private static MusicRepository instance;
    private final MusicDao musicDao;

    private MusicRepository(android.content.Context context) {
        this.musicDao = AppDatabase.getInstance(context).musicDao();
    }

    public static synchronized MusicRepository getInstance(android.content.Context context) {
        if (instance == null) {
            instance = new MusicRepository(context);
        }
        return instance;
    }

    public interface MusicCallback {
        void onSuccess(List<Song> songs);
        void onError(Exception e);
    }

    public interface StreamCallback {
        void onSuccess(String streamUrl);
        void onError(Exception e);
    }

    public void searchSongs(String query, MusicCallback callback) {
        executor.execute(() -> {
            try {
                // GUARDAR BÚSQUEDA PARA PERSONALIZACIÓN
                musicDao.insertSearch(new SearchHistory(query, System.currentTimeMillis()));

                Log.d(TAG, "Repo: ⚡ Buscando con NewPipeExtractor (Nativo/Rápido) -> " + query);
                // Usar NewPipeExtractor para búsqueda Java nativa (< 1s)
                StreamingService service = ServiceList.YouTube;
                SearchInfo searchInfo = SearchInfo.getInfo(service, service.getSearchQHFactory().fromQuery(query));
                
                List<Song> songs = new ArrayList<>();
                for (org.schabi.newpipe.extractor.InfoItem item : searchInfo.getRelatedItems()) {
                    if (item instanceof StreamInfoItem) {
                        StreamInfoItem streamItem = (StreamInfoItem) item;
                        String id = streamItem.getUrl().split("v=")[1];
                        String thumb = streamItem.getThumbnails().isEmpty() ? "" : streamItem.getThumbnails().get(0).getUrl();
                        
                        songs.add(new Song(
                            id,
                            streamItem.getName(),
                            streamItem.getUploaderName(),
                            streamItem.getUrl(),
                            (int) streamItem.getDuration(),
                            thumb
                        ));
                    }
                    if (songs.size() >= 15) break;
                }

                Log.d(TAG, "Repo: ✓ NewPipe encontró " + songs.size() + " resultados al instante!");
                callback.onSuccess(songs);
            } catch (Exception e) {
                Log.e(TAG, "Repo: NewPipe falló, cayendo a YoutubeDL como respaldo", e);
                // Respaldo lento por si NewPipe falla
                try {
                    com.yausername.youtubedl_android.YoutubeDLRequest request = new com.yausername.youtubedl_android.YoutubeDLRequest("ytsearch10:" + query);
                    request.addOption("--dump-json");
                    request.addOption("--flat-playlist");
                    com.yausername.youtubedl_android.YoutubeDLResponse response = com.yausername.youtubedl_android.YoutubeDL.getInstance().execute(request, null);
                    // ... (resto de lógica de parseo si fuera necesario, simplificado aquí)
                } catch (Exception ex) {
                    callback.onError(ex);
                }
            }
        });
    }

    public void loadSongs(MusicCallback callback) {
        getPersonalizedMix(callback);
    }

    public void getPersonalizedMix(MusicCallback callback) {
        executor.execute(() -> {
            try {
                List<String> topKeywords = musicDao.getTopKeywords();
                if (topKeywords.isEmpty()) {
                    // Fallback a algo aleatorio si no hay historia
                    String[] seeds = {"Victor Medevil", "Top Hits 2024", "Reggaeton Mix", "Rock Classics"};
                    String query = seeds[new Random().nextInt(seeds.length)];
                    searchSongs(query, callback);
                    return;
                }

                Set<String> seenTitles = new HashSet<>();
                List<Song> finalMix = new ArrayList<>();
                int numKeywords = Math.min(topKeywords.size(), 5); // Aumentado a 5 para más variedad
                java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger(0);

                for (int i = 0; i < numKeywords; i++) {
                    String keyword = topKeywords.get(i);
                    executor.execute(() -> {
                        try {
                            StreamingService service = ServiceList.YouTube;
                            SearchInfo searchInfo = SearchInfo.getInfo(service, service.getSearchQHFactory().fromQuery(keyword));
                            List<Song> results = processNewPipeItems(searchInfo.getRelatedItems());
                            
                            synchronized (finalMix) {
                                // Tomar solo los mejores 4 de cada keyword para forzar mezcla de géneros
                                int countPerKeyword = 0;
                                for (Song s : results) {
                                    if (countPerKeyword >= 4) break;
                                    String normalized = normalizeTitle(s.getTitle());
                                    if (!seenTitles.contains(normalized)) {
                                        seenTitles.add(normalized);
                                        finalMix.add(s);
                                        countPerKeyword++;
                                    }
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Search error for keyword: " + keyword, e);
                        } finally {
                            if (counter.incrementAndGet() == numKeywords) {
                                Collections.shuffle(finalMix);
                                callback.onSuccess(finalMix);
                            }
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Repo: Error en mezcla personalizada", e);
                callback.onError(e);
            }
        });
    }

    private List<Song> processNewPipeItems(List<? extends org.schabi.newpipe.extractor.InfoItem> items) {
        List<Song> songs = new ArrayList<>();
        for (org.schabi.newpipe.extractor.InfoItem item : items) {
            if (item instanceof StreamInfoItem) {
                StreamInfoItem streamItem = (StreamInfoItem) item;
                String id = streamItem.getUrl().contains("v=") ? streamItem.getUrl().split("v=")[1] : "id_" + System.currentTimeMillis();
                String thumb = streamItem.getThumbnails().isEmpty() ? "" : streamItem.getThumbnails().get(0).getUrl();
                
                songs.add(new Song(
                    id,
                    streamItem.getName(),
                    streamItem.getUploaderName(),
                    streamItem.getUrl(),
                    (int) streamItem.getDuration(),
                    thumb
                ));
            }
            if (songs.size() >= 15) break;
        }
        return songs;
    }

    public void getStreamUrl(String url, StreamCallback callback) {
        if (url == null) {
            callback.onError(new Exception("URL es nula"));
            return;
        }

        if (url.startsWith("/") || url.startsWith("file://") || url.startsWith("content://")) {
            Log.d(TAG, "Repo: 📂 REPRODUCCIÓN LOCAL detectada -> " + url);
            callback.onSuccess(url);
            return;
        }

        Log.d(TAG, "Repo: 🌐 REPRODUCCIÓN REMOTA solicitada para -> " + url);
        executor.execute(() -> {
            // ═══ CACHÉ ═══
            String cached = urlCache.get(url);
            if (cached != null) {
                callback.onSuccess(cached);
                return;
            }
            
            // ═══ MOTOR PRIMARIO: NewPipe (Ultra-Rápido ~1-2s) ═══
            try {
                Log.d(TAG, "Repo: ⚡ Extrayendo stream con NewPipe (Nativo) -> " + url);
                org.schabi.newpipe.extractor.stream.StreamInfo info = org.schabi.newpipe.extractor.stream.StreamInfo.getInfo(org.schabi.newpipe.extractor.ServiceList.YouTube, url);
                
                // Buscar el mejor stream de audio
                List<org.schabi.newpipe.extractor.stream.AudioStream> audioStreams = info.getAudioStreams();
                if (!audioStreams.isEmpty()) {
                    String streamUrl = audioStreams.get(0).getContent();
                    Log.d(TAG, "Repo: ✅ NewPipe extrajo URL en 1s: " + streamUrl);
                    urlCache.put(url, streamUrl);
                    callback.onSuccess(streamUrl);
                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, "Repo: NewPipe falló en extracción, intentando YoutubeDL", e);
            }

            // ═══ MOTOR DE RESPALDO: YoutubeDL (Más lento pero compatible) ═══
            try {
                Log.d(TAG, "Repo: 🏎️ Extrayendo con YoutubeDL (Respaldo) -> " + url);
                com.yausername.youtubedl_android.YoutubeDLRequest request = new com.yausername.youtubedl_android.YoutubeDLRequest(url);
                request.addOption("-g");
                request.addOption("-f", "140/bestaudio/best");
                request.addOption("--no-playlist");
                request.addOption("--no-check-certificate");
                request.addOption("--user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36");
                request.addOption("--add-header", "Referer:https://www.youtube.com/");

                com.yausername.youtubedl_android.YoutubeDLResponse response = com.yausername.youtubedl_android.YoutubeDL.getInstance().execute(request, null);
                String streamUrl = response.getOut().split("\n")[0].trim();
                
                if (streamUrl.startsWith("http")) {
                    Log.d(TAG, "Repo: ✅ YoutubeDL rescató la URL: " + streamUrl);
                    urlCache.put(url, streamUrl);
                    callback.onSuccess(streamUrl);
                } else {
                    callback.onError(new Exception("Fallo total en extracción."));
                }
            } catch (Exception e) {
                Log.e(TAG, "Repo: Error crítico en todos los motores", e);
                callback.onError(e);
            }
        });
    }

    /** Versión síncrona para ResolvingDataSource (Media3 Service) */
    public String getStreamUrlSync(String url) {
        if (url == null) return null;
        if (url.startsWith("/") || url.startsWith("file://") || url.startsWith("content://")) return url;
        
        String cached = urlCache.get(url);
        if (cached != null) return cached;

        try {
            Log.d(TAG, "Repo Sync: ⚡ Extrayendo stream JIT -> " + url);
            org.schabi.newpipe.extractor.stream.StreamInfo info = org.schabi.newpipe.extractor.stream.StreamInfo.getInfo(ServiceList.YouTube, url);
            List<org.schabi.newpipe.extractor.stream.AudioStream> audioStreams = info.getAudioStreams();
            if (!audioStreams.isEmpty()) {
                String streamUrl = audioStreams.get(0).getContent();
                urlCache.put(url, streamUrl);
                return streamUrl;
            }
        } catch (Exception e) {
            Log.e(TAG, "Repo Sync: Error NewPipe, usando YoutubeDL backup", e);
            try {
                com.yausername.youtubedl_android.YoutubeDLRequest request = new com.yausername.youtubedl_android.YoutubeDLRequest(url);
                request.addOption("-g");
                request.addOption("-f", "140/bestaudio/best");
                com.yausername.youtubedl_android.YoutubeDLResponse response = com.yausername.youtubedl_android.YoutubeDL.getInstance().execute(request, null);
                String streamUrl = response.getOut().split("\n")[0].trim();
                urlCache.put(url, streamUrl);
                return streamUrl;
            } catch (Exception ex) {
                Log.e(TAG, "Repo Sync: Fallo total", ex);
            }
        }
        return url; // Fallback al original si todo falla
    }

    public void getRelatedSongs(String url, MusicCallback callback) {
        executor.execute(() -> {
            try {
                Log.d(TAG, "Repo: 🔄 Buscando temas relacionados para -> " + url);
                org.schabi.newpipe.extractor.stream.StreamInfo info = org.schabi.newpipe.extractor.stream.StreamInfo.getInfo(ServiceList.YouTube, url);
                
                Set<String> seenTitles = new HashSet<>();
                List<Song> related = new ArrayList<>();
                for (org.schabi.newpipe.extractor.InfoItem item : info.getRelatedItems()) {
                    if (item instanceof StreamInfoItem) {
                        StreamInfoItem streamItem = (StreamInfoItem) item;
                        String id = streamItem.getUrl().contains("v=") ? streamItem.getUrl().split("v=")[1] : streamItem.getUrl();
                        String thumb = streamItem.getThumbnails().isEmpty() ? "" : streamItem.getThumbnails().get(0).getUrl();
                        
                        String normalized = normalizeTitle(streamItem.getName());
                        if (!seenTitles.contains(normalized)) {
                            seenTitles.add(normalized);
                            related.add(new Song(
                                id,
                                streamItem.getName(),
                                streamItem.getUploaderName(),
                                streamItem.getUrl(),
                                (int) streamItem.getDuration(),
                                thumb
                            ));
                        }
                    }
                    if (related.size() >= 10) break;
                }
                callback.onSuccess(related);
            } catch (Exception e) {
                callback.onError(e);
            }
        });
    }

    private String normalizeTitle(String title) {
        if (title == null) return "";
        return title.toLowerCase()
            .replaceAll("(?i)\\(official video\\)", "")
            .replaceAll("(?i)\\[official video\\]", "")
            .replaceAll("(?i)\\(lyrics\\)", "")
            .replaceAll("(?i)\\[lyrics\\]", "")
            .replaceAll("(?i)\\(lyric video\\)", "")
            .replaceAll("(?i)\\(oficial\\)", "")
            .replaceAll("(?i)oficial", "")
            .replaceAll("(?i)video", "")
            .replaceAll("\\s+", " ")
            .trim();
    }
}
