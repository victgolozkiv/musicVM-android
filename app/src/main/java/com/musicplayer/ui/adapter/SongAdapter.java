package com.musicplayer.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.musicplayer.R;
import com.musicplayer.model.Song;
import java.util.ArrayList;
import java.util.List;

public class SongAdapter extends RecyclerView.Adapter<SongAdapter.SongViewHolder> {
    private List<Song> songs = new ArrayList<>();
    private final OnSongActionListener listener;
    private boolean playlistMode = false;

    public interface OnSongActionListener {
        void onPlay(Song song);
        void onDownload(Song song);
        void onDelete(Song song);
        void onAddToPlaylist(Song song);
    }

    public SongAdapter(OnSongActionListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public SongViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_song, parent, false);
        return new SongViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SongViewHolder holder, int position) {
        Song song = songs.get(position);
        holder.tvTitle.setText(song.getTitle());
        holder.tvArtist.setText(song.getArtist());
        
        if (song.isLocal()) {
            holder.btnDownload.setImageResource(android.R.drawable.ic_menu_delete);
            holder.btnDownload.setVisibility(View.VISIBLE);
            holder.btnDownload.setOnClickListener(v -> listener.onDelete(song));
        } else {
            holder.btnDownload.setImageResource(android.R.drawable.stat_sys_download_done); // O el que tuviera antes
            // Nota: El XML original probablemente tiene el icono de descarga.
            // Vamos a usar uno estándar de descarga si no es local.
            holder.btnDownload.setImageResource(android.R.drawable.stat_sys_download);
            holder.btnDownload.setVisibility(View.VISIBLE);
            holder.btnDownload.setOnClickListener(v -> listener.onDownload(song));
        }
        
        Glide.with(holder.itemView.getContext())
            .load(song.getThumbnailUrl())
            .placeholder(android.R.drawable.ic_menu_report_image)
            .transition(DrawableTransitionOptions.withCrossFade())
            .centerCrop()
            .into(holder.imgThumbnail);
            
        holder.itemView.setOnClickListener(v -> listener.onPlay(song));
        holder.btnMore.setOnClickListener(v -> {
            androidx.appcompat.widget.PopupMenu popup = new androidx.appcompat.widget.PopupMenu(v.getContext(), v);
            popup.getMenu().add("Añadir a Playlist");
            popup.getMenu().add("Descargar");
            if (playlistMode) {
                popup.getMenu().add("Eliminar de esta Playlist");
            }
            
            popup.setOnMenuItemClickListener(item -> {
                String title = item.getTitle().toString();
                if (title.equals("Añadir a Playlist")) {
                    listener.onAddToPlaylist(song);
                } else if (title.equals("Descargar")) {
                    listener.onDownload(song);
                } else if (title.equals("Eliminar de esta Playlist")) {
                    listener.onDelete(song);
                }
                return true;
            });
            popup.show();
        });
        
        // Ya no necesitamos botones de descarga directos si usamos el menú
        holder.btnDownload.setVisibility(View.GONE);
    }

    @Override
    public int getItemCount() {
        return songs.size();
    }

    public void updateSongs(List<Song> newSongs) {
        songs.clear();
        songs.addAll(newSongs);
        notifyDataSetChanged();
    }

    public List<Song> getSongs() {
        return songs;
    }

    public void setPlaylistMode(boolean playlistMode) {
        this.playlistMode = playlistMode;
    }

    static class SongViewHolder extends RecyclerView.ViewHolder {
        ImageView imgThumbnail;
        TextView tvTitle;
        TextView tvArtist;
        ImageButton btnDownload;
        ImageButton btnMore;

        public SongViewHolder(@NonNull View itemView) {
            super(itemView);
            imgThumbnail = itemView.findViewById(R.id.imgSongThumbnail);
            tvTitle = itemView.findViewById(R.id.tvSongTitle);
            tvArtist = itemView.findViewById(R.id.tvSongArtist);
            btnDownload = itemView.findViewById(R.id.btnDownloadItem);
            btnMore = itemView.findViewById(R.id.btnMoreItem);
        }
    }
}
