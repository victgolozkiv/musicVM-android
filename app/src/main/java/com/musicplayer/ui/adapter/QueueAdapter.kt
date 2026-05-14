package com.musicplayer.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.musicplayer.R

class QueueAdapter(private val listener: OnQueueActionListener) :
    RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

    private var queue = mutableListOf<MediaItem>()

    interface OnQueueActionListener {
        fun onItemClick(position: Int)
        fun onItemRemove(position: Int)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_queue, parent, false)
        return QueueViewHolder(view)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        val item = queue[position]
        item.mediaMetadata.title?.let { holder.tvTitle.text = it }
        item.mediaMetadata.artist?.let { holder.tvArtist.text = it }

        item.mediaMetadata.artworkUri?.let { uri ->
            Glide.with(holder.itemView.context)
                .load(uri)
                .placeholder(android.R.drawable.ic_menu_report_image)
                .into(holder.imgArt)
        }

        holder.itemView.setOnClickListener { listener.onItemClick(position) }
    }

    override fun getItemCount(): Int = queue.size

    fun updateQueue(newQueue: List<MediaItem>) {
        this.queue = newQueue.toMutableList()
        notifyDataSetChanged()
    }

    class QueueViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imgArt: ImageView = itemView.findViewById(R.id.imgQueueArt)
        val tvTitle: TextView = itemView.findViewById(R.id.tvQueueTitle)
        val tvArtist: TextView = itemView.findViewById(R.id.tvQueueArtist)
        val ivDragHandle: ImageView = itemView.findViewById(R.id.ivDragHandle)
    }
}
