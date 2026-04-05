package com.musicplayer.model;

public class Song {
    private String id;
    private String title;
    private String artist;
    private String url;
    private String streamUrl;
    private String thumbnailUrl;
    private long duration;
    private boolean isLocal = false;
    
    public Song(String id, String title, String artist, String url, long duration, String thumbnailUrl) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.url = url;
        this.duration = duration;
        this.thumbnailUrl = thumbnailUrl;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public void setArtist(String artist) { this.artist = artist; }
    public String getUrl() { return url; }
    public String getStreamUrl() { return streamUrl; }
    public void setStreamUrl(String streamUrl) { this.streamUrl = streamUrl; }
    public String getThumbnailUrl() { return thumbnailUrl; }
    public long getDuration() { return duration; }
    public boolean isLocal() { return isLocal; }
    public void setLocal(boolean local) { isLocal = local; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Song song = (Song) o;
        return id != null ? id.equals(song.id) : song.id == null;
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }
}
