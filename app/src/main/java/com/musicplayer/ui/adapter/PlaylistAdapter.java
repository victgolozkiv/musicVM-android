package com.musicplayer.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.musicplayer.R;
import com.musicplayer.db.entity.Playlist;
import java.util.ArrayList;
import java.util.List;

public class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.PlaylistViewHolder> {
    private List<Playlist> playlists = new ArrayList<>();
    private final OnPlaylistInteractionListener listener;

    public interface OnPlaylistInteractionListener {
        void onPlaylistClick(Playlist playlist);
        void onPlaylistDelete(Playlist playlist);
    }

    public PlaylistAdapter(OnPlaylistInteractionListener listener) {
        this.listener = listener;
    }

    public void updatePlaylists(List<Playlist> playlists) {
        this.playlists = playlists;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public PlaylistViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_playlist, parent, false);
        return new PlaylistViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PlaylistViewHolder holder, int position) {
        Playlist playlist = playlists.get(position);
        holder.tvName.setText(playlist.name);
        holder.itemView.setOnClickListener(v -> listener.onPlaylistClick(playlist));
        holder.btnDelete.setOnClickListener(v -> listener.onPlaylistDelete(playlist));
    }

    @Override
    public int getItemCount() {
        return playlists.size();
    }

    static class PlaylistViewHolder extends RecyclerView.ViewHolder {
        TextView tvName;
        android.widget.ImageButton btnDelete;
        PlaylistViewHolder(View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tvPlaylistName);
            btnDelete = itemView.findViewById(R.id.btnDeletePlaylist);
        }
    }
}
