package com.musicplayer.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.musicplayer.R
import com.musicplayer.model.LyricLine

class LyricsAdapter : RecyclerView.Adapter<LyricsAdapter.LyricViewHolder>() {
    private var lyrics: List<LyricLine> = ArrayList()
    private var activeLine: LyricLine? = null
    private var listener: OnLyricClickListener? = null

    interface OnLyricClickListener {
        fun onLyricClick(line: LyricLine)
    }

    fun setOnLyricClickListener(listener: (LyricLine) -> Unit) {
        this.listener = object : OnLyricClickListener {
            override fun onLyricClick(line: LyricLine) {
                listener(line)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LyricViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lyric, parent, false)
        return LyricViewHolder(view)
    }

    override fun onBindViewHolder(holder: LyricViewHolder, position: Int) {
        val line = lyrics[position]
        holder.tvText.text = line.text
        
        if (line == activeLine) {
            holder.tvText.setTextColor(-0x1) // 0xFFFFFFFF
            holder.tvText.alpha = 1.0f
            holder.tvText.scaleX = 1.1f
            holder.tvText.scaleY = 1.1f
        } else {
            holder.tvText.setTextColor(0x4DFFFFFF)
            holder.tvText.alpha = 0.5f
            holder.tvText.scaleX = 1.0f
            holder.tvText.scaleY = 1.0f
        }

        holder.itemView.setOnClickListener {
            listener?.onLyricClick(line)
        }
    }

    override fun getItemCount(): Int = lyrics.size

    fun updateLyrics(newLyrics: List<LyricLine>) {
        this.lyrics = ArrayList(newLyrics)
        notifyDataSetChanged()
    }

    fun setActiveLine(line: LyricLine): Int {
        this.activeLine = line
        val index = lyrics.indexOf(line)
        notifyDataSetChanged()
        return index
    }

    class LyricViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvText: TextView = itemView as TextView
    }
}
