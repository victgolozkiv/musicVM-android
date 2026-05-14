package com.musicplayer.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.musicplayer.R
import com.musicplayer.model.Song
import java.util.*

class SongAdapter(private val listener: OnSongActionListener) :
    ListAdapter<Song, RecyclerView.ViewHolder>(SongDiffCallback()) {

    private var playlistMode = false
    private var selectionMode = false
    private val selectedSongs = mutableSetOf<Song>()
    var onSelectionChanged: ((Int) -> Unit)? = null

    fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        if (!enabled) selectedSongs.clear()
        notifyDataSetChanged()
    }

    fun getSelectedSongs(): List<Song> = selectedSongs.toList()

    interface OnSongActionListener {
        fun onPlay(song: Song)
        fun onDownload(song: Song)
        fun onDelete(song: Song)
        fun onAddToPlaylist(song: Song)
    }

    companion object {
        private const val TYPE_SONG = 0
        private const val TYPE_HEADER = 1
        private const val TYPE_MASTER = 2
    }

    init {
        setHasStableIds(true)
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    override fun getItemId(position: Int): Long {
        val s = getItem(position) ?: return RecyclerView.NO_ID
        return s.id.hashCode().toLong()
    }

    override fun getItemViewType(position: Int): Int {
        val song = getItem(position)
        return when {
            song.isHeader && selectionMode -> TYPE_MASTER
            song.isHeader -> TYPE_HEADER
            else -> TYPE_SONG
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_HEADER -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_header, parent, false)
                HeaderViewHolder(view)
            }
            TYPE_MASTER -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song_master, parent, false)
                SongViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
                SongViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val song = getItem(position)
        if (holder is HeaderViewHolder) {
            holder.tvTitle.text = song.title
            return
        }

        val songHolder = holder as SongViewHolder
        songHolder.tvTitle.text = song.title
        songHolder.tvArtist.text = song.artist

        val originalThumb = song.thumbnailUrl
        val maxRes = originalThumb?.replace("hqdefault.jpg", "maxresdefault.jpg") ?: originalThumb
        val sdRes = originalThumb?.replace("hqdefault.jpg", "sddefault.jpg") ?: originalThumb

        Glide.with(holder.itemView.context)
            .load(maxRes)
            .error(Glide.with(holder.itemView.context).load(sdRes)
                .error(Glide.with(holder.itemView.context).load(originalThumb)))
            .placeholder(R.drawable.circle_background)
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .transition(DrawableTransitionOptions.withCrossFade(150))
            .override(200, 200)
            .centerCrop()
            .into(songHolder.imgThumbnail)

        songHolder.checkBox?.let { cb ->
            cb.visibility = if (selectionMode) View.VISIBLE else View.GONE
            cb.setOnCheckedChangeListener(null)
            cb.isChecked = selectedSongs.any { it.id == song.id }
            cb.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedSongs.add(song) else selectedSongs.remove(song)
                onSelectionChanged?.invoke(selectedSongs.size)
            }
        }

        holder.itemView.setOnClickListener { 
            if (selectionMode) {
                songHolder.checkBox?.toggle()
            } else {
                listener.onPlay(song) 
            }
        }
        holder.itemView.setOnLongClickListener {
            listener.onDelete(song)
            true
        }
        
        songHolder.btnMore?.setOnClickListener { v ->
            val popup = androidx.appcompat.widget.PopupMenu(v.context, v)
            popup.menu.add("Añadir a Playlist")
            popup.menu.add("Descargar")
            if (playlistMode) {
                popup.menu.add("Eliminar de esta Playlist")
            }

            popup.setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Añadir a Playlist" -> listener.onAddToPlaylist(song)
                    "Descargar" -> listener.onDownload(song)
                    "Eliminar de esta Playlist" -> listener.onDelete(song)
                }
                true
            }
            popup.show()
        }

        songHolder.btnDownload?.visibility = View.GONE
    }

    fun updateSongs(newSongs: List<Song>) {
        submitList(newSongs.toList())
    }

    val songs: List<Song>
        get() = currentList

    fun setPlaylistMode(playlistMode: Boolean) {
        this.playlistMode = playlistMode
    }

    class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imgThumbnail: ImageView = itemView.findViewById(R.id.imgSongThumbnail)
        val tvTitle: TextView = itemView.findViewById(R.id.tvSongTitle)
        val tvArtist: TextView = itemView.findViewById(R.id.tvSongArtist)
        val btnDownload: ImageButton? = itemView.findViewById(R.id.btnDownloadItem)
        val btnMore: ImageButton? = itemView.findViewById(R.id.btnMoreItem)
        val checkBox: android.widget.CheckBox? = itemView.findViewById(R.id.cbSelect)
    }

    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTitle: TextView = itemView.findViewById(R.id.tvHeaderTitle)
    }

    private class SongDiffCallback : DiffUtil.ItemCallback<Song>() {
        override fun areItemsTheSame(oldItem: Song, newItem: Song): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Song, newItem: Song): Boolean {
            return oldItem.title == newItem.title &&
                    oldItem.artist == newItem.artist &&
                    oldItem.thumbnailUrl == newItem.thumbnailUrl
        }
    }
}
