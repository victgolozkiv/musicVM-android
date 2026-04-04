package com.musicplayer.ui.fragment;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import com.bumptech.glide.Glide;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.slider.Slider;
import com.google.common.util.concurrent.ListenableFuture;
import com.musicplayer.R;
import com.musicplayer.model.Song;
import com.musicplayer.service.PlaybackService;
import com.musicplayer.ui.MainActivity;
import com.musicplayer.ui.viewmodel.MusicViewModel;

public class PlayerFragment extends Fragment {
    private static final String TAG = "DEBUG_PLAYER";
    private MusicViewModel viewModel;
    private MediaController mediaController;
    
    private ImageView imgArt;
    private TextView tvTitle, tvArtist;
    private Slider slider;
    private FloatingActionButton btnPlayPause;
    private ImageButton btnDismiss, btnNext, btnPrev;
    private TextView tvCurrentTime, tvTotalTime;
    private ObjectAnimator discAnimator;
    
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable updateProgressAction = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, 1000);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_player, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        initViews(view);
        setupAnimations(view);
        setupViewModel();
        setupMediaController();
    }

    private void initViews(View view) {
        imgArt = view.findViewById(R.id.imgFullPlayerArt);
        tvTitle = view.findViewById(R.id.tvFullPlayerTitle);
        tvArtist = view.findViewById(R.id.tvFullPlayerArtist);
        slider = view.findViewById(R.id.playerSlider);
        btnPlayPause = view.findViewById(R.id.btnFullPlayPause);
        btnDismiss = view.findViewById(R.id.btnDismiss);
        btnNext = view.findViewById(R.id.btnNext);
        btnPrev = view.findViewById(R.id.btnPrev);
        tvCurrentTime = view.findViewById(R.id.tvCurrentTime);
        tvTotalTime = view.findViewById(R.id.tvTotalTime);

        // Habilitar Marquee
        tvTitle.setSelected(true);

        btnDismiss.setOnClickListener(v -> requireActivity().getSupportFragmentManager().popBackStack());
        
        btnPlayPause.setOnClickListener(v -> {
            if (mediaController != null) {
                int state = mediaController.getPlaybackState();
                Log.d(TAG, "UI: Clic en Play/Pausa. Estado actual: " + state);
                
                if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                    mediaController.prepare();
                    mediaController.play();
                } else {
                    if (mediaController.isPlaying()) {
                        mediaController.pause();
                    } else {
                        mediaController.play();
                    }
                }
            } else {
                Log.e(TAG, "UI: MediaController no conectado todavía");
            }
        });

        btnNext.setOnClickListener(v -> {
            if (mediaController != null) mediaController.seekToNextMediaItem();
        });
        btnPrev.setOnClickListener(v -> {
            if (mediaController != null) mediaController.seekToPreviousMediaItem();
        });

        slider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider slider) {
                // Dejar de actualizar automáticamente mientras el usuario arrastra
                handler.removeCallbacks(updateProgressAction);
            }

            @Override
            public void onStopTrackingTouch(@NonNull Slider slider) {
                if (mediaController != null) {
                    mediaController.seekTo((long) slider.getValue());
                }
                // Reanudar actualizaciones después de un pequeño delay
                handler.postDelayed(updateProgressAction, 1000);
            }
        });

        // Eliminar el addOnChangeListener que causaba seek en cada milímetro de movimiento
        // slider.addOnChangeListener(...) removed
    }

    private void setupMediaController() {
        MainActivity activity = (MainActivity) requireActivity();
        mediaController = activity.getMusicMediaController();
        if (mediaController != null) {
            Log.d(TAG, "PlayerFragment: Usando MediaController de MainActivity.");
            setupControllerListeners();
            syncUI();
        } else {
            Log.e(TAG, "PlayerFragment: MediaController de MainActivity es null");
            // Nota: En un flujo normal, MainActivity lo inicializa antes de abrir el Fragment
        }
    }

    private void setupControllerListeners() {
        if (mediaController == null) return;
        mediaController.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                Log.d(TAG, "Motor Event: isPlaying -> " + isPlaying);
                updatePlayPauseUI(isPlaying);
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                Log.d(TAG, "Motor Event: State -> " + state);
                // Si está cargando (buffering), damos feedback visual
                if (state == Player.STATE_BUFFERING) {
                    btnPlayPause.setAlpha(0.6f);
                } else {
                    btnPlayPause.setAlpha(1.0f);
                    updateProgress();
                }
            }
        });
        handler.post(updateProgressAction);
    }

    private void syncUI() {
        if (mediaController != null) {
            updatePlayPauseUI(mediaController.isPlaying());
        }
    }

    private void updatePlayPauseUI(boolean isPlaying) {
        if (mediaController == null) return;
        
        // Si el usuario quiere que suene (playWhenReady) mostramos pausa, 
        // incluso si está cargando (buffering).
        boolean shouldShowPause = mediaController.getPlayWhenReady();
        btnPlayPause.setImageResource(shouldShowPause ? R.drawable.ic_pause_mod : R.drawable.ic_play_mod);
        
        if (isPlaying) {
            if (discAnimator.isPaused()) discAnimator.resume(); 
            else if (!discAnimator.isStarted()) discAnimator.start();
        } else {
            discAnimator.pause();
        }
    }

    private void updateProgress() {
        if (mediaController != null && mediaController.getDuration() > 0) {
            try {
                long duration = mediaController.getDuration();
                long position = mediaController.getCurrentPosition();

                slider.setValueFrom(0);
                slider.setValueTo(duration);
                slider.setValue(Math.max(0, Math.min(position, duration)));

                tvCurrentTime.setText(formatTime(position));
                tvTotalTime.setText(formatTime(duration));
            } catch (Exception ignored) {}
        }
    }

    private String formatTime(long ms) {
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }

    private void setupAnimations(View view) {
        View cardDisc = view.findViewById(R.id.cardDisc);
        
        // Animación de rotación
        discAnimator = ObjectAnimator.ofFloat(cardDisc, "rotation", 0f, 360f);
        discAnimator.setDuration(15000);
        discAnimator.setRepeatCount(ValueAnimator.INFINITE);
        discAnimator.setInterpolator(new LinearInterpolator());

        // Animación de PULSO suave
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(cardDisc, "scaleX", 1f, 1.05f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(cardDisc, "scaleY", 1f, 1.05f, 1f);
        scaleX.setDuration(3000);
        scaleY.setDuration(3000);
        scaleX.setRepeatCount(ValueAnimator.INFINITE);
        scaleY.setRepeatCount(ValueAnimator.INFINITE);
        scaleX.setInterpolator(new LinearInterpolator());
        scaleY.setInterpolator(new LinearInterpolator());

        scaleX.start();
        scaleY.start();
    }

    private void setupViewModel() {
        viewModel = new ViewModelProvider(requireActivity()).get(MusicViewModel.class);
        viewModel.getSelectedSong().observe(getViewLifecycleOwner(), song -> {
            tvTitle.setText(song.getTitle());
            tvArtist.setText(song.getArtist());
            
            Glide.with(this)
                .asBitmap()
                .load(song.getThumbnailUrl())
                .into(new com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull android.graphics.Bitmap resource, @Nullable com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> transition) {
                        imgArt.setImageBitmap(resource);
                        applyPalette(resource);
                    }

                    @Override
                    public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {}
                });
        });
    }

    private void applyPalette(android.graphics.Bitmap bitmap) {
        androidx.palette.graphics.Palette.from(bitmap).generate(palette -> {
            if (palette != null && getView() != null) {
                int defaultValue = ContextCompat.getColor(requireContext(), android.R.color.black);
                int dominantColor = palette.getDominantColor(defaultValue);
                int mutedColor = palette.getMutedColor(defaultValue);
                
                View bgGradient = getView().findViewById(R.id.bgGradient);
                android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] { dominantColor, mutedColor, 0xFF000000 }
                );
                bgGradient.setBackground(gd);
            }
        });
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacks(updateProgressAction);
        super.onDestroyView();
    }
}
