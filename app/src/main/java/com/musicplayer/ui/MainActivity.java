package com.musicplayer.ui;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import com.bumptech.glide.Glide;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.common.util.concurrent.ListenableFuture;
import com.musicplayer.R;
import com.musicplayer.service.PlaybackService;
import com.musicplayer.ui.fragment.DownloadsFragment;
import com.musicplayer.ui.fragment.HomeFragment;
import com.musicplayer.ui.fragment.PlayerFragment;
import com.musicplayer.ui.fragment.SearchFragment;
import com.musicplayer.ui.fragment.PlaylistsFragment;
import com.musicplayer.ui.viewmodel.MusicViewModel;
import com.musicplayer.model.Song;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "DEBUG_PLAYER";
    private MusicViewModel viewModel;
    private MediaController mediaController;
    private ListenableFuture<MediaController> controllerFuture;
    
    private MaterialCardView miniPlayerCard;
    private TextView tvTitle, tvArtist;
    private ImageView imgMiniPlayer;
    private FloatingActionButton btnPlayPause;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        if (getSupportActionBar() != null) getSupportActionBar().hide();
        setContentView(R.layout.activity_main);

        initViews();
        setupNavigation();
        setupViewModel();
        setupMediaController();
        requestNecessaryPermissions();

        if (savedInstanceState == null) {
            loadFragment(new HomeFragment());
        }
    }

    private void requestNecessaryPermissions() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            p.add(Manifest.permission.POST_NOTIFICATIONS);
            p.add(Manifest.permission.READ_MEDIA_AUDIO);
        } else {
            p.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            p.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        
        List<String> list = new ArrayList<>();
        for (String s : p) {
            if (ContextCompat.checkSelfPermission(this, s) != PackageManager.PERMISSION_GRANTED) {
                list.add(s);
            }
        }
        if (!list.isEmpty()) {
            ActivityCompat.requestPermissions(this, list.toArray(new String[0]), 100);
        }
    }

    private void initViews() {
        miniPlayerCard = findViewById(R.id.miniPlayerCard);
        tvTitle = findViewById(R.id.tvMiniPlayerTitle);
        tvArtist = findViewById(R.id.tvMiniPlayerArtist);
        imgMiniPlayer = findViewById(R.id.imgMiniPlayer);
        btnPlayPause = findViewById(R.id.btnPlayPause);

        btnPlayPause.setOnClickListener(v -> {
            if (mediaController != null && mediaController.isConnected()) {
                if (mediaController.isPlaying()) mediaController.pause();
                else mediaController.play();
            }
        });

        miniPlayerCard.setOnClickListener(v -> openFullPlayer());
    }

    private void openFullPlayer() {
        getSupportFragmentManager().beginTransaction()
            .setCustomAnimations(R.anim.slide_up, 0, 0, R.anim.slide_down)
            .add(android.R.id.content, new PlayerFragment())
            .addToBackStack(null)
            .commit();
    }

    private void setupNavigation() {
        BottomNavigationView nav = findViewById(R.id.bottomNavigation);
        nav.setOnItemSelectedListener(item -> {
            Fragment f = null;
            int id = item.getItemId();
            if (id == R.id.nav_home) f = new HomeFragment();
            else if (id == R.id.nav_search) f = new SearchFragment();
            else if (id == R.id.nav_downloads) f = new DownloadsFragment();
            else if (id == R.id.nav_playlists) f = new PlaylistsFragment();
            if (f != null) loadFragment(f);
            return true;
        });
    }

    private void loadFragment(Fragment fragment) {
        getSupportFragmentManager().beginTransaction().replace(R.id.fragmentContainer, fragment).commit();
    }

    private void setupViewModel() {
        viewModel = new ViewModelProvider(this).get(MusicViewModel.class);
        
        viewModel.getSelectedSong().observe(this, song -> {
            if (song == null) return;
            miniPlayerCard.setVisibility(View.VISIBLE);
            tvTitle.setText(song.getTitle());
            tvArtist.setText(song.getArtist());
            Glide.with(this).load(song.getThumbnailUrl()).into(imgMiniPlayer);
        });

        viewModel.getPlayRequest().observe(this, request -> {
            if (request != null && mediaController != null && mediaController.isConnected()) {
                playPlaylist(request.playlist, request.index);
                viewModel.clearPlayRequest();
            }
        });
    }

    private void playPlaylist(List<Song> playlist, int startIndex) {
        try {
            if (mediaController != null) {
                // ESTRATEGIA DE ACTUALIZACIÓN SIN CORTES (GAPLESS UPDATE)
                MediaItem currentlyPlaying = mediaController.getCurrentMediaItem();
                if (currentlyPlaying != null && !playlist.isEmpty() && 
                    currentlyPlaying.mediaId.equals(playlist.get(0).getId()) && startIndex == 0) {
                    
                    Log.d(TAG, "MainActivity: Actualizando cola sin interrumpir (Mode: Radio/Related)");
                    
                    // Solo añadir las que no están ya en la cola (para evitar duplicados infinitos)
                    int count = mediaController.getMediaItemCount();
                    List<MediaItem> newItems = new ArrayList<>();
                    for (int i = 1; i < playlist.size(); i++) { // Empezamos desde 1 porque la 0 ya suena
                        Song song = playlist.get(i);
                        boolean alreadyInQueue = false;
                        for (int j = 0; j < count; j++) {
                            if (mediaController.getMediaItemAt(j).mediaId.equals(song.getId())) {
                                alreadyInQueue = true;
                                break;
                            }
                        }
                        if (!alreadyInQueue) {
                            newItems.add(new MediaItem.Builder()
                                .setUri(song.getUrl())
                                .setMediaId(song.getId())
                                .setMediaMetadata(new androidx.media3.common.MediaMetadata.Builder()
                                        .setTitle(song.getTitle())
                                        .setArtist(song.getArtist())
                                        .setArtworkUri(android.net.Uri.parse(song.getThumbnailUrl()))
                                        .build()).build());
                        }
                    }
                    if (!newItems.isEmpty()) {
                        mediaController.addMediaItems(newItems);
                        Log.d(TAG, "MainActivity: Añadidas " + newItems.size() + " canciones adicionales.");
                    }
                    return;
                }

                // REPRODUCCIÓN NUEVA (PLAYLIST O BÚSQUEDA)
                Log.d(TAG, "MainActivity: Cargando playlist completa: " + playlist.size() + " temas");
                mediaController.stop();
                mediaController.clearMediaItems();
                
                List<MediaItem> mediaItems = new ArrayList<>();
                for (Song song : playlist) {
                    MediaItem.Builder builder = new MediaItem.Builder()
                            .setUri(song.getUrl())
                            .setMediaId(song.getId())
                            .setMediaMetadata(new androidx.media3.common.MediaMetadata.Builder()
                                    .setTitle(song.getTitle())
                                    .setArtist(song.getArtist())
                                    .setArtworkUri(android.net.Uri.parse(song.getThumbnailUrl()))
                                    .build());
                    mediaItems.add(builder.build());
                }

                mediaController.setMediaItems(mediaItems, startIndex, 0);
                mediaController.prepare();
                mediaController.play();
            }
        } catch (Exception e) {
            Log.e(TAG, "MainActivity: Error en playPlaylist", e);
        }
    }

    private void setupMediaController() {
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        
        controllerFuture.addListener(() -> {
            try {
                mediaController = controllerFuture.get();
                Log.d(TAG, "MainActivity: MediaController conectado.");
                
                // Si había una petición de juego pendiente mientras conectaba
                MusicViewModel.PlaylistPlayRequest pending = viewModel.getPlayRequest().getValue();
                if (pending != null) {
                    playPlaylist(pending.playlist, pending.index);
                    viewModel.clearPlayRequest();
                }

                mediaController.addListener(new Player.Listener() {
                    @Override
                    public void onIsPlayingChanged(boolean isPlaying) {
                        boolean shouldShowPause = mediaController.getPlayWhenReady();
                        btnPlayPause.setImageResource(shouldShowPause ? R.drawable.ic_pause_mod : R.drawable.ic_play_mod);
                    }

                    @Override
                    public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                        if (mediaItem != null && mediaItem.mediaMetadata != null) {
                            Log.d(TAG, "MainActivity: Transición a nueva canción: " + mediaItem.mediaMetadata.title);
                            
                            String title = mediaItem.mediaMetadata.title != null ? mediaItem.mediaMetadata.title.toString() : "Unknown";
                            String artist = mediaItem.mediaMetadata.artist != null ? mediaItem.mediaMetadata.artist.toString() : "Unknown";
                            String thumb = mediaItem.mediaMetadata.artworkUri != null ? mediaItem.mediaMetadata.artworkUri.toString() : "";
                            
                            // Sincronizar el ViewModel
                            Song song = new Song(
                                mediaItem.mediaId,
                                title,
                                artist,
                                mediaItem.localConfiguration != null ? mediaItem.localConfiguration.uri.toString() : "",
                                0,
                                thumb
                            );
                            viewModel.updateSelectedSong(song);
                        }
                    }

                    @Override
                    public void onPlayerError(androidx.media3.common.PlaybackException error) {
                        Log.e(TAG, "MainActivity: ❌ Error de motor: " + error.getMessage());
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "MainActivity: Fallo en conexión", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    protected void onDestroy() {
        if (controllerFuture != null) MediaController.releaseFuture(controllerFuture);
        super.onDestroy();
    }
    
    public MediaController getMusicMediaController() {
        return mediaController;
    }
}
