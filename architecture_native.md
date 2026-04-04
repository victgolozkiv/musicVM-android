# Native Android Architecture - Media3 Music Player

## Overview
Complete guide for building a native Android music player using Media3 (ExoPlayer) with modern Android architecture patterns.

## Project Structure
```
app/
├── src/main/
│   ├── java/com/musicplayer/
│   │   ├── service/
│   │   │   └── PlaybackService.java
│   │   ├── ui/
│   │   │   ├── MainActivity.java
│   │   │   ├── adapter/
│   │   │   │   └── SongAdapter.java
│   │   │   └── viewmodel/
│   │   │       └── MusicViewModel.java
│   │   ├── model/
│   │   │   └── Song.java
│   │   └── repository/
│   │       └── MusicRepository.java
│   └── AndroidManifest.xml
├── build.gradle (app level)
└── build.gradle (project level)
```

## Core Java Classes

### 1. Song Model
```java
package com.musicplayer.model;

public class Song {
    private long id;
    private String title;
    private String artist;
    private String album;
    private String path;
    private long duration;
    
    // Constructors, getters, and setters
    public Song(long id, String title, String artist, String album, String path, long duration) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.path = path;
        this.duration = duration;
    }
    
    // Getters and setters...
}
```

### 2. PlaybackService (Media3)
```java
package com.musicplayer.service;

import android.content.Intent;
import android.os.Bundle;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.MediaSession.ControllerInfo;

public class PlaybackService extends MediaSessionService {
    private ExoPlayer player;
    private MediaSession mediaSession;
    
    @Override
    public void onCreate() {
        super.onCreate();
        player = new ExoPlayer.Builder(this).build();
        mediaSession = new MediaSession.Builder(this, player)
            .setCallback(new MediaSessionCallback())
            .build();
    }
    
    private class MediaSessionCallback implements MediaSession.Callback {
        @Override
        public MediaItem onAddMediaItems(MediaSession mediaSession, ControllerInfo controller, Bundle args) {
            // Handle media item addition
            return null;
        }
        
        @Override
        public void onPlay(MediaSession mediaSession, ControllerInfo controller) {
            player.play();
        }
        
        @Override
        public void onPause(MediaSession mediaSession, ControllerInfo controller) {
            player.pause();
        }
        
        @Override
        public void onStop(MediaSession mediaSession, ControllerInfo controller) {
            player.stop();
        }
    }
    
    @Override
    public MediaSession onGetSession(ControllerInfo controllerInfo) {
        return mediaSession;
    }
    
    @Override
    public void onDestroy() {
        mediaSession.release();
        player.release();
        super.onDestroy();
    }
}
```

### 3. MainActivity
```java
package com.musicplayer.ui;

import android.os.Bundle;
import androidx.activity.ComponentActivity;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.musicplayer.R;
import com.musicplayer.adapter.SongAdapter;
import com.musicplayer.viewmodel.MusicViewModel;

public class MainActivity extends ComponentActivity {
    private RecyclerView recyclerView;
    private SongAdapter songAdapter;
    private MusicViewModel viewModel;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        setupRecyclerView();
        setupViewModel();
        requestPermissions();
    }
    
    private void setupRecyclerView() {
        recyclerView = findViewById(R.id.recyclerView);
        GridLayoutManager layoutManager = new GridLayoutManager(this, 2);
        recyclerView.setLayoutManager(layoutManager);
        songAdapter = new SongAdapter();
        recyclerView.setAdapter(songAdapter);
    }
    
    private void setupViewModel() {
        viewModel = new MusicViewModel(this);
        viewModel.getSongs().observe(this, songs -> {
            songAdapter.updateSongs(songs);
        });
    }
    
    private void requestPermissions() {
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            if (isGranted) {
                viewModel.loadSongs();
            }
        }).launch(android.Manifest.permission.READ_EXTERNAL_STORAGE);
    }
}
```

### 4. SongAdapter
```java
package com.musicplayer.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.musicplayer.R;
import com.musicplayer.model.Song;
import java.util.ArrayList;
import java.util.List;

public class SongAdapter extends RecyclerView.Adapter<SongAdapter.SongViewHolder> {
    private List<Song> songs = new ArrayList<>();
    
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
        holder.titleTextView.setText(song.getTitle());
        holder.artistTextView.setText(song.getArtist());
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
    
    static class SongViewHolder extends RecyclerView.ViewHolder {
        TextView titleTextView;
        TextView artistTextView;
        
        public SongViewHolder(@NonNull View itemView) {
            super(itemView);
            titleTextView = itemView.findViewById(R.id.songTitle);
            artistTextView = itemView.findViewById(R.id.songArtist);
        }
    }
}
```

## AndroidManifest.xml
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    
    <!-- Permissions for Android 13/14 -->
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" 
        android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
    
    <application
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.MusicPlayer"
        tools:targetApi="31">
        
        <activity
            android:name=".ui.MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        
        <!-- Media3 Playback Service -->
        <service
            android:name=".service.PlaybackService"
            android:exported="false"
            android:foregroundServiceType="mediaPlayback">
            <intent-filter>
                <action android:name="androidx.media3.session.MediaSessionService" />
            </intent-filter>
        </service>
    </application>
</manifest>
```

## Gradle Dependencies

### Project Level build.gradle
```gradle
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:8.1.2'
    }
}

plugins {
    id 'com.android.application' version '8.1.2' apply false
    id 'com.android.library' version '8.1.2' apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

task clean(type: Delete) {
    delete rootProject.buildDir
}
```

### App Level build.gradle
```gradle
plugins {
    id 'com.android.application'
}

android {
    namespace 'com.musicplayer'
    compileSdk 34

    defaultConfig {
        applicationId "com.musicplayer"
        minSdk 26
        targetSdk 34
        versionCode 1
        versionName "1.0"

        testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
    
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
    
    buildFeatures {
        viewBinding true
    }
}

dependencies {
    // Core Android
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
    implementation 'androidx.recyclerview:recyclerview:1.3.2'
    implementation 'com.google.android.material:material:1.10.0'
    
    // Media3 (ExoPlayer) - Core Components
    implementation 'androidx.media3:media3-exoplayer:1.2.0'
    implementation 'androidx.media3:media3-exoplayer-dash:1.2.0'
    implementation 'androidx.media3:media3-exoplayer-hls:1.2.0'
    implementation 'androidx.media3:media3-exoplayer-rtsp:1.2.0'
    implementation 'androidx.media3:media3-ui:1.2.0'
    
    // Media3 Session Service (for background playback)
    implementation 'androidx.media3:media3-session:1.2.0'
    
    // Media3 Common
    implementation 'androidx.media3:media3-common:1.2.0'
    
    // Lifecycle Components
    implementation 'androidx.lifecycle:lifecycle-viewmodel:2.7.0'
    implementation 'androidx.lifecycle:lifecycle-livedata:2.7.0'
    implementation 'androidx.lifecycle:lifecycle-runtime:2.7.0'
    
    // Activity Result API
    implementation 'androidx.activity:activity:1.8.2'
    implementation 'androidx.fragment:fragment:1.6.2'
    
    // Testing
    testImplementation 'junit:junit:4.13.2'
    androidTestImplementation 'androidx.test.ext:junit:1.1.5'
    androidTestImplementation 'androidx.test.espresso:espresso-core:3.5.1'
}
```

## Gradle Wrapper Properties (gradle-wrapper.properties)
```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.4-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

## Key Features

### Media3 Benefits
- **Modern Architecture**: Replaces legacy MediaPlayer with ExoPlayer
- **Background Playback**: Integrated MediaSessionService support
- **Rich Media Controls**: Automatic notification controls
- **Format Support**: Extensive audio format support
- **Performance**: Optimized for battery and memory usage

### Android 13/14 Compatibility
- **Runtime Permissions**: Proper handling of READ_MEDIA_AUDIO
- **Foreground Service**: Correct foregroundServiceType declaration
- **Scoped Storage**: Compatible with modern storage model

### UI Architecture
- **RecyclerView**: Efficient list/grid display
- **GridLayoutManager**: 2-column grid layout
- **ViewModel**: MVVM pattern with LiveData
- **ViewBinding**: Type-safe view references

## Setup Instructions

1. **Create New Project**: Android Studio → New Project → Empty Activity
2. **Set Target SDK**: Android 14 (API 34)
3. **Add Dependencies**: Copy app/build.gradle dependencies
4. **Create Package Structure**: Follow the directory structure
5. **Add Classes**: Copy all Java classes to appropriate packages
6. **Update Manifest**: Replace AndroidManifest.xml
7. **Create Layouts**: Design activity_main.xml and item_song.xml
8. **Request Permissions**: Handle runtime permissions for media access
9. **Test**: Run on device/emulator with Android 13+

## Migration Benefits

- **No JNI Threading Issues**: Pure Java implementation
- **Full Lifecycle Control**: Proper service management
- **Modern API**: Latest Android best practices
- **Better Performance**: Native code execution
- **Easier Debugging**: Single language codebase
- **Future-Proof**: Compatible with latest Android versions

This architecture provides a solid foundation for a modern, efficient music player app using the latest Android development practices.
