package com.musicplayer.ui.fragment

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.media.audiofx.AudioEffect
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.slider.Slider
import com.musicplayer.R
import com.musicplayer.model.Song
import com.musicplayer.ui.MainActivity
import com.musicplayer.ui.adapter.LyricsAdapter
import com.musicplayer.ui.view.VisualizerView
import com.musicplayer.ui.viewmodel.MusicViewModel

class PlayerFragment : Fragment() {
    private var viewModel: MusicViewModel? = null
    private var mediaController: MediaController? = null
    
    private var imgArt: ImageView? = null
    private var tvTitle: TextView? = null
    private var tvArtist: TextView? = null
    private var tvCurrentTime: TextView? = null
    private var tvTotalTime: TextView? = null
    private var tvCurrentLyric: TextView? = null
    private var slider: Slider? = null
    private var btnPlayPause: FloatingActionButton? = null
    private var btnDismiss: ImageButton? = null
    private var btnNext: ImageButton? = null
    private var btnPrev: ImageButton? = null
    private var btnQueue: ImageButton? = null
    private var btnEqualizer: ImageButton? = null
    private var llFullLyrics: View? = null
    private var btnCloseLyrics: ImageButton? = null
    private var rvLyrics: RecyclerView? = null
    private var lyricsAdapter: LyricsAdapter? = null
    private var discAnimator: ObjectAnimator? = null
    private var visualizerView: VisualizerView? = null
    private var visualizer: Visualizer? = null
    private var bufferAnimator: ValueAnimator? = null
    
    private var lastSkipTime: Long = 0
    
    private val handler = Handler(Looper.getMainLooper())
    private val updateProgressAction = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 250) // Resolución táctica de 250ms para fluidez de letras
        }
    }

    companion object {
        private const val TAG = "DEBUG_PLAYER"
        private const val PERMISSION_REQUEST_AUDIO = 1001
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_player, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initViews(view)
        setupAnimations(view)
        setupViewModel()
        setupMediaController()
    }

    private fun initViews(view: View) {
        imgArt = view.findViewById(R.id.imgFullPlayerArt)
        tvTitle = view.findViewById(R.id.tvFullPlayerTitle)
        tvArtist = view.findViewById(R.id.tvFullPlayerArtist)
        slider = view.findViewById(R.id.playerSlider)
        btnPlayPause = view.findViewById(R.id.btnFullPlayPause)
        btnDismiss = view.findViewById(R.id.btnDismiss)
        btnNext = view.findViewById(R.id.btnNext)
        btnPrev = view.findViewById(R.id.btnPrev)
        tvCurrentTime = view.findViewById(R.id.tvCurrentTime)
        tvTotalTime = view.findViewById(R.id.tvTotalTime)
        tvCurrentLyric = view.findViewById(R.id.tvCurrentLyric)
        llFullLyrics = view.findViewById(R.id.llFullLyrics)
        btnCloseLyrics = view.findViewById(R.id.btnCloseLyrics)
        rvLyrics = view.findViewById(R.id.rvLyrics)
        visualizerView = view.findViewById(R.id.visualizerView)
        btnQueue = view.findViewById(R.id.btnQueue)
        btnEqualizer = view.findViewById(R.id.btnEqualizer)
        val btnCast = view.findViewById<ImageButton>(R.id.btnCast)

        // Habilitar Marquee
        tvTitle?.isSelected = true

        btnDismiss?.setOnClickListener { parentFragmentManager.popBackStack() }
        
        btnPlayPause?.setOnClickListener {
            mediaController?.let { controller ->
                val state = controller.playbackState
                Log.d(TAG, "UI: Clic en Play/Pausa. Estado actual: $state")
                
                if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                    controller.prepare()
                    controller.play()
                } else {
                    if (controller.isPlaying) controller.pause()
                    else controller.play()
                }
            }
        }

        btnNext?.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSkipTime < 1000) return@setOnClickListener
            lastSkipTime = now

            Log.d(TAG, "UI: Clic en Siguiente (Lógica Estricta)")
            
            btnNext?.isEnabled = false
            btnNext?.alpha = 0.5f

            mediaController?.let { controller ->
                if (controller.hasNextMediaItem()) {
                    controller.seekToNextMediaItem()
                } else {
                    viewModel?.skipToNext()
                }
            }
        }
        btnPrev?.setOnClickListener {
            mediaController?.seekToPreviousMediaItem()
        }

        btnQueue?.setOnClickListener {
            val queueFragment = QueueBottomSheetFragment()
            queueFragment.show(parentFragmentManager, "queue")
        }

        btnEqualizer?.setOnClickListener {
            val context = context ?: return@setOnClickListener
            try {
                val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                val sessionId = viewModel?.getAudioSessionId()?.value ?: -1
                if (sessionId != -1) {
                    intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                }
                intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                startActivityForResult(intent, 0)
            } catch (e: Exception) {
                Toast.makeText(context, "Ecualizador no disponible", Toast.LENGTH_SHORT).show()
            }
        }

        slider?.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                handler.removeCallbacks(updateProgressAction)
            }

            override fun onStopTrackingTouch(slider: Slider) {
                mediaController?.seekTo(slider.value.toLong())
                handler.postDelayed(updateProgressAction, 1000)
            }
        })

        tvCurrentLyric?.setOnClickListener {
            if (llFullLyrics?.visibility == View.GONE) showFullLyrics()
        }

        btnCloseLyrics?.setOnClickListener { hideFullLyrics() }
        
        lyricsAdapter = LyricsAdapter().apply {
            setOnLyricClickListener { line ->
                mediaController?.seekTo(line.timeMs)
                Toast.makeText(requireContext(), "Saltando a: ${formatTime(line.timeMs)}", Toast.LENGTH_SHORT).show()
            }
        }
        rvLyrics?.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = lyricsAdapter
        }

        visualizerView?.setOnClickListener {
            visualizerView?.cycleMode()
            Toast.makeText(requireContext(), "Modo: ${visualizerView?.currentMode?.name}", Toast.LENGTH_SHORT).show()
        }

        btnCast?.setOnClickListener { showCastDialog() }
    }

    private fun showCastDialog() {
        val vm = viewModel ?: return
        val ctx = context ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 1002)
                Toast.makeText(ctx, "Se requiere permiso de Bluetooth", Toast.LENGTH_SHORT).show()
                return
            }
        }

        vm.startDeviceScan()
        
        val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
        builder.setTitle("Conectar a dispositivo")
        
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        var isBtEnabled = false
        try {
            isBtEnabled = adapter?.isEnabled == true
        } catch (e: SecurityException) {
            Log.e(TAG, "Excepción de seguridad al verificar Bluetooth", e)
        }

        if (!isBtEnabled) {
            builder.setMessage("El Bluetooth está desactivado. Actívalo para encontrar tu Echo Pop / Alexa.")
            builder.setPositiveButton("Activar Bluetooth") { _, _ ->
                try {
                    val enableBtIntent = Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    startActivity(enableBtIntent)
                } catch (e: SecurityException) {
                    Toast.makeText(ctx, "Falta permiso para activar Bluetooth", Toast.LENGTH_SHORT).show()
                }
            }
            builder.setNegativeButton("Cerrar", null)
            builder.show()
            return
        }

        val arrayAdapter = android.widget.ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_1)
        builder.setAdapter(arrayAdapter) { _, which ->
            val currentDevices = vm.castDevices.value
            if (currentDevices.isNullOrEmpty() || which >= currentDevices.size) {
                val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                startActivity(intent)
                return@setAdapter
            }
            
            val device = currentDevices[which]
            if (device.type.contains("Bluetooth")) {
                Toast.makeText(ctx, "Conectando a ${device.name} via Bluetooth...", Toast.LENGTH_SHORT).show()
                val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                startActivity(intent)
            } else {
                Toast.makeText(ctx, "Casting a ${device.name} (Wi-Fi)...", Toast.LENGTH_SHORT).show()
            }
        }

        vm.castDevices.observe(viewLifecycleOwner) { devices ->
            arrayAdapter.clear()
            if (devices.isNullOrEmpty()) {
                arrayAdapter.add("Buscando dispositivos...")
                arrayAdapter.add("(Si no aparece tu Echo, vincúlalo primero)")
            } else {
                for (d in devices) {
                    arrayAdapter.add("${d.name} (${d.type})")
                }
            }
            arrayAdapter.notifyDataSetChanged()
        }

        builder.setNegativeButton("Cerrar", null)
        builder.setNeutralButton("Ajustes Bluetooth") { _, _ ->
            val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
            startActivity(intent)
        }
        builder.show()
    }

    private fun showFullLyrics() {
        llFullLyrics?.apply {
            visibility = View.VISIBLE
            alpha = 0f
            animate().alpha(1f).setDuration(300).start()
        }
    }

    private fun hideFullLyrics() {
        llFullLyrics?.animate()?.alpha(0f)?.setDuration(300)?.withEndAction {
            llFullLyrics?.visibility = View.GONE
        }?.start()
    }

    private fun setupMediaController() {
        (activity as? MainActivity)?.let { mainActivity ->
            mediaController = mainActivity.musicMediaController
        }
        mediaController?.let {
            Log.d(TAG, "PlayerFragment: Usando MediaController de MainActivity.")
            setupControllerListeners()
            syncUI()
        } ?: Log.e(TAG, "PlayerFragment: MediaController de MainActivity es null")
    }

    private fun checkAudioPermissionAndInitVisualizer(sessionId: Int) {
        val ctx = context ?: return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_AUDIO)
        } else {
            initVisualizer(sessionId)
        }
    }

    private fun initVisualizer(sessionId: Int) {
        try {
            visualizer?.apply {
                enabled = false
                release()
            }
            if (sessionId == -1 || sessionId == androidx.media3.common.C.AUDIO_SESSION_ID_UNSET) {
                visualizer = null
                return
            }
            visualizer = Visualizer(sessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer, waveform: ByteArray, samplingRate: Int) {
                        visualizerView?.updateVisualizer(waveform)
                    }
                    override fun onFftDataCapture(v: Visualizer, fft: ByteArray, samplingRate: Int) {}
                }, Visualizer.getMaxCaptureRate() / 2, true, false)
                enabled = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Visualizer initialization failed", e)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == PERMISSION_REQUEST_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                val sessionId = viewModel?.getAudioSessionId()?.value ?: -1
                if (sessionId != -1) initVisualizer(sessionId)
            } else {
                Toast.makeText(requireContext(), "Permiso de audio necesario para el visualizador", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == 1002) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showCastDialog()
            } else {
                Toast.makeText(requireContext(), "Permiso de Bluetooth denegado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupControllerListeners() {
        mediaController?.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                btnNext?.isEnabled = true
                btnNext?.alpha = 1.0f
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d(TAG, "Motor Event: isPlaying -> $isPlaying")
                updatePlayPauseUI(isPlaying)
            }

            override fun onPlaybackStateChanged(state: Int) {
                Log.d(TAG, "Motor Event: State -> $state")
                if (state == Player.STATE_BUFFERING) {
                    btnPlayPause?.alpha = 0.6f
                    startBufferingGlow()
                } else {
                    btnPlayPause?.alpha = 1.0f
                    stopBufferingGlow()
                    updateProgress()
                }
            }
        })
        handler.post(updateProgressAction)
    }

    private fun syncUI() {
        mediaController?.let { updatePlayPauseUI(it.isPlaying) }
    }

    private fun updatePlayPauseUI(isPlaying: Boolean) {
        val controller = mediaController ?: return
        val shouldShowPause = controller.playWhenReady
        btnPlayPause?.setImageResource(if (shouldShowPause) R.drawable.ic_pause_mod else R.drawable.ic_play_mod)
        
        discAnimator?.let { animator ->
            if (isPlaying) {
                if (animator.isPaused) animator.resume()
                else if (!animator.isStarted) animator.start()
            } else {
                animator.pause()
            }
        }
    }

    private fun startBufferingGlow() {
        val ctx = context ?: return
        if (bufferAnimator == null) {
            bufferAnimator = ValueAnimator.ofArgb(
                ContextCompat.getColor(ctx, R.color.purple_primary),
                android.graphics.Color.WHITE
            ).apply {
                duration = 600
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { anim ->
                    slider?.let {
                        it.trackActiveTintList = android.content.res.ColorStateList.valueOf(anim.animatedValue as Int)
                    }
                }
            }
        }
        if (bufferAnimator?.isStarted != true) bufferAnimator?.start()
    }

    private fun stopBufferingGlow() {
        val ctx = context ?: return
        bufferAnimator?.let {
            if (it.isRunning) it.cancel()
        }
        slider?.let {
            it.trackActiveTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.purple_primary))
        }
    }

    private fun updateProgress() {
        mediaController?.let { controller ->
            val duration = controller.duration
            if (duration > 0) {
                try {
                    val position = controller.currentPosition
                    slider?.apply {
                        valueFrom = 0f
                        valueTo = duration.toFloat()
                        value = position.coerceIn(0, duration).toFloat()
                    }
                    tvCurrentTime?.text = formatTime(position)
                    tvTotalTime?.text = formatTime(duration)
                    viewModel?.updateLyricsSync(position, duration)
                } catch (ignored: Exception) {}
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }

    private fun setupAnimations(view: View) {
        val cardDisc = view.findViewById<View>(R.id.cardDisc)
        
        discAnimator = ObjectAnimator.ofFloat(cardDisc, "rotation", 0f, 360f).apply {
            duration = 15000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }

        val scaleX = ObjectAnimator.ofFloat(cardDisc, "scaleX", 1f, 1.05f, 1f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        val scaleY = ObjectAnimator.ofFloat(cardDisc, "scaleY", 1f, 1.05f, 1f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }

        scaleX.start()
        scaleY.start()
    }

    private fun setupViewModel() {
        viewModel = ViewModelProvider(requireActivity()).get(MusicViewModel::class.java)
        viewModel?.selectedSong?.observe(viewLifecycleOwner) { song ->
            if (song == null) return@observe
            
            tvTitle?.text = song.title
            tvArtist?.text = song.artist
            
            val originalThumb = song.thumbnailUrl
            val maxRes = originalThumb?.replace("hqdefault.jpg", "maxresdefault.jpg") ?: originalThumb
            val sdRes = originalThumb?.replace("hqdefault.jpg", "sddefault.jpg") ?: originalThumb

            Glide.with(this)
                .asBitmap()
                .load(maxRes)
                .error(Glide.with(this).asBitmap().load(sdRes)
                    .error(Glide.with(this).asBitmap().load(originalThumb)))
                .placeholder(R.drawable.player_bg_gradient)
                .transition(com.bumptech.glide.load.resource.bitmap.BitmapTransitionOptions.withCrossFade())
                .into(object : CustomTarget<android.graphics.Bitmap>() {
                    override fun onResourceReady(resource: android.graphics.Bitmap, transition: Transition<in android.graphics.Bitmap>?) {
                        imgArt?.setImageBitmap(resource)
                        applyPalette(resource)
                    }
                    override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {}
                })
        }

        viewModel?.currentLyrics?.observe(viewLifecycleOwner) { lyrics ->
            lyricsAdapter?.updateLyrics(lyrics)
        }

        viewModel?.activeLyricLine?.observe(viewLifecycleOwner) { line ->
            if (line != null) {
                tvCurrentLyric?.text = line.text
                val index = lyricsAdapter?.setActiveLine(line) ?: -1
                if (index != -1) rvLyrics?.smoothScrollToPosition(index)
            } else {
                tvCurrentLyric?.text = "♪"
            }
        }

        viewModel?.getAudioSessionId()?.observe(viewLifecycleOwner) { sessionId ->
            if (sessionId != null && sessionId != -1) {
                checkAudioPermissionAndInitVisualizer(sessionId)
            }
        }
    }

    private fun applyPalette(bitmap: android.graphics.Bitmap) {
        val ctx = context ?: return
        Palette.from(bitmap).generate { palette ->
            if (palette != null && view != null && context != null) {
                val defaultValue = ContextCompat.getColor(ctx, android.R.color.black)
                val dominantColor = palette.getDominantColor(defaultValue)
                val mutedColor = palette.getMutedColor(defaultValue)
                val vibrantColor = palette.getVibrantColor(dominantColor)
                
                visualizerView?.setColor(vibrantColor)
                
                val bgGradient = view?.findViewById<View>(R.id.bgGradient)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    bgGradient?.background = BitmapDrawable(resources, bitmap)
                    bgGradient?.setRenderEffect(RenderEffect.createBlurEffect(100f, 100f, Shader.TileMode.CLAMP))
                    bgGradient?.alpha = 0.6f
                } else {
                    val gd = GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(dominantColor, mutedColor, -0x1000000) // 0xFF000000 -> -0x1000000
                    )
                    bgGradient?.background = gd
                    bgGradient?.alpha = 1.0f
                }
            }
        }
    }

    override fun onDestroyView() {
        visualizer?.apply {
            enabled = false
            release()
        }
        visualizer = null
        handler.removeCallbacks(updateProgressAction)
        super.onDestroyView()
    }
}
