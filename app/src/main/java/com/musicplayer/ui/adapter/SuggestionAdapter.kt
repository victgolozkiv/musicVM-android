package com.musicplayer.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SuggestionAdapter(private val listener: OnSuggestionClickListener) :
    RecyclerView.Adapter<SuggestionAdapter.SuggestionViewHolder>() {

    private var suggestions = listOf<String>()

    fun interface OnSuggestionClickListener {
        fun onSuggestionClick(suggestion: String)
    }

    fun updateSuggestions(newSuggestions: List<String>) {
        this.suggestions = newSuggestions
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SuggestionViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false)
        return SuggestionViewHolder(view)
    }

    override fun onBindViewHolder(holder: SuggestionViewHolder, position: Int) {
        val suggestion = suggestions[position]
        holder.textView.text = suggestion
        holder.textView.setTextColor(Color.WHITE)
        holder.itemView.setOnClickListener { listener.onSuggestionClick(suggestion) }
    }

    override fun getItemCount(): Int = suggestions.size

    class SuggestionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textView: TextView = itemView.findViewById(android.R.id.text1)
    }
}
