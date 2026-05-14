package com.musicplayer.repository

object SearchConfig {
    @JvmField
    val DEFAULT_SEEDS = arrayOf(
        "Santa Grifa", "Canserbero", "Peso Pluma",
        "Exitos Regional Mexicano", "Reggaeton 2024",
        "Trap Latino", "Lo-Fi Music", "Corridos Tumbados"
    )

    /**
     * Palabras clave forzadas para asegurar contexto musical sin restringir demasiado.
     */
    const val REQUIRED_MUSIC_KEYWORDS = " official audio music"

    /**
     * Exclusiones moderadas para evitar contenido no deseado sin romper la búsqueda.
     */
    const val EXCLUSION_FLAGS = " -vlog -blog -tutorial -news -noticias -podcast -interview -reaccion -review -critica -unboxing -humor -chismes -gaming -tutoriales -movie -scene -tiktok -shorts"
}
