package com.musicplayer.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.musicplayer.R

data class OnlinePlaylist(val title: String, val query: String, val imageUrl: String)

class OnlinePlaylistAdapter(
    private val playlists: List<OnlinePlaylist>,
    private val clickListener: (OnlinePlaylist) -> Unit
) : RecyclerView.Adapter<OnlinePlaylistAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imgThumb: ImageView = view.findViewById(R.id.imgPlaylistThumb)
        val tvTitle: TextView = view.findViewById(R.id.tvPlaylistTitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_online_playlist, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val playlist = playlists[position]
        holder.tvTitle.text = playlist.title
        Glide.with(holder.itemView.context)
            .load(playlist.imageUrl)
            .centerCrop()
            .placeholder(R.drawable.player_bg_gradient)
            .into(holder.imgThumb)
            
        holder.itemView.setOnClickListener { clickListener(playlist) }
    }

    override fun getItemCount() = playlists.size
}
