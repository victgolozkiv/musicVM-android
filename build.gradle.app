// App Level build.gradle
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
