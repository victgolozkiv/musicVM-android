package com.musicplayer.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.musicplayer.R
import com.musicplayer.db.entity.Playlist

class PlaylistAdapter(private val listener: OnPlaylistInteractionListener) :
    ListAdapter<Playlist, PlaylistAdapter.PlaylistViewHolder>(PlaylistDiffCallback()) {

    interface OnPlaylistInteractionListener {
        fun onPlaylistClick(playlist: Playlist)
        fun onPlaylistDelete(playlist: Playlist)
    }

    fun updatePlaylists(newPlaylists: List<Playlist>) {
        submitList(newPlaylists.toList())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false)
        return PlaylistViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        val playlist = getItem(position)
        holder.tvName.text = playlist.name
        holder.itemView.setOnClickListener { listener.onPlaylistClick(playlist) }
        holder.btnDelete.setOnClickListener { listener.onPlaylistDelete(playlist) }
    }

    class PlaylistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tvPlaylistName)
        val btnDelete: ImageButton = itemView.findViewById(R.id.btnDeletePlaylist)
    }

    private class PlaylistDiffCallback : DiffUtil.ItemCallback<Playlist>() {
        override fun areItemsTheSame(oldItem: Playlist, newItem: Playlist): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Playlist, newItem: Playlist): Boolean {
            return oldItem.name == newItem.name
        }
    }
}
