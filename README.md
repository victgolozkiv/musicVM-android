# 🎵 Native Android Music Player (Premium Media3)

![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Java](https://img.shields.io/badge/Java-ED8B00?style=for-the-badge&logo=java&logoColor=white)
![Media3](https://img.shields.io/badge/Media3-ExoPlayer-blue?style=for-the-badge)

A fully native, professional-grade Android Music Player architected on top of Google's **Media3 (ExoPlayer)** framework. Engineered to deliver an extreme-low latency, pristine, and premium music streaming experience.

---

## ❤️ Apoya el Desarrollo

Si este reproductor te gusta o te ha sido útil, ¡considera invitarme un café! Todo el apoyo ayuda a mantener las APIs actualizadas y el desarrollo fluyendo.

[![Donate PayPal](https://img.shields.io/badge/Donar_vía-PayPal-00457C?style=for-the-badge&logo=paypal&logoColor=white)](https://www.paypal.com/paypalme/VictorRicardo162/1)

---

## ✨ Características Premium (v3.0)

*   **🎙️ Letras Sincronizadas (Karaoke Engine):** Integración con la API de `LRCLib` con parseo en tiempo real. La interfaz muestra la letra de la canción al exacto milisegundo de ejecución (a 4 fotogramas por segundo).
*   **☁️ Smart Offline Auto-Cache:** Ahorra datos celulares. Toda canción reproducida se fragmenta y auto-guarda dinámicamente en disco (hasta 500MB). Además, la vista de descargas te separa de manera inteligente tus `MP3s Locales` del `Caché Remoto`, permitiéndote reproducir en entornos sin red.
*   **📻 Radio Inteligente Infinita ("Para ti"):** Scroll sin fin gracias al rediseño algorítmico del `MusicRepository`, cargando casi cien temas cruzados afines a tus gustos.
*   **🎨 Animaciones Fluídas Premium:** Elementos compartidos (`SharedElementTransitions`). Clickea la canción en el modo mini, y el cover del disco *volará* suavemente hacia la pantalla principal y volverá con naturalidad.
*   **🎛️ Motor AudioFX Nativo:** Efectos directos unificados al canal digital de audio de `ExoPlayer`, permitiéndote potenciar los graves a nivel Kernel.
*   **🖼️ Portadas 1080p Inmersivas (Lock screen):** Extracción inteligente de miniaturas `maxresdefault.jpg` optimizadas para desatar todo el potencial estético del desenfoque gaussiano de la pantalla de bloqueo de **Android 14**.

## 🛠️ Arquitectura Técnica

Este proyecto descarta componentes lentos y despliega tecnologías punta de forma limpia:
- Extracción de streams vía web scrape directa asíncrona (`NewPipeExtractor`), operada desde el `MusicRepository`.
- **Media3 Session** protegiendo los subprocesos de la muerte del sistema operativo.
- Almacenamiento local mediante bases de datos JSON SharedPreferences persistentes y DAO Room.
- Listas virtualizadas (`RecyclerView` + `Glide/Palette` + UI Gradient).

## 🚀 Instalación y Compilación

Este proyecto usa la última versión de dependencias Gradle y Java puro sin dependencias hostiles JNI.
```bash
# Simplemente clona el repo y compila:
./gradlew assembleDebug

# El APK aparecerá en app/build/outputs/apk/debug/app-debug.apk
```
