package com.musicplayer.utils

import java.util.*
import java.util.regex.Pattern

object LrcParser {

    data class LrcLine(
        val timeMs: Long,
        val text: String
    ) : Comparable<LrcLine> {
        override fun compareTo(other: LrcLine): Int {
            return timeMs.compareTo(other.timeMs)
        }
    }

    @JvmStatic
    fun parseLrc(lrcContent: String?): List<LrcLine> {
        val lines = mutableListOf<LrcLine>()
        if (lrcContent.isNullOrBlank()) {
            return lines
        }

        val rows = lrcContent.split("\\r?\\n".toRegex())
        // Regex para capturar [mm:ss.xx] o [mm:ss.xxx]
        val pattern = Pattern.compile("\\[(\\d{2,}):(\\d{2})(?:\\.(\\d+))?](.*)")

        for (row in rows) {
            val matcher = pattern.matcher(row)
            while (matcher.find()) {
                val minStr = matcher.group(1)
                val secStr = matcher.group(2)
                var milStr = matcher.group(3)
                val text = matcher.group(4)

                if (minStr != null && secStr != null) {
                    val min = minStr.toLong()
                    val sec = secStr.toLong()
                    var mil = 0L
                    if (milStr != null) {
                        milStr = when (milStr.length) {
                            1 -> milStr + "00"
                            2 -> milStr + "0"
                            else -> if (milStr.length > 3) milStr.substring(0, 3) else milStr
                        }
                        mil = milStr.toLong()
                    }

                    val timeMs = (min * 60 * 1000) + (sec * 1000) + mil
                    if (text != null) {
                        lines.add(LrcLine(timeMs, text.trim()))
                    }
                }
            }
        }
        
        lines.sort()
        return lines
    }
}
