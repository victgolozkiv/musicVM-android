package com.musicplayer.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LrcParser {

    public static class LrcLine implements Comparable<LrcLine> {
        public long timeMs;
        public String text;

        public LrcLine(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }

        @Override
        public int compareTo(LrcLine o) {
            return Long.compare(this.timeMs, o.timeMs);
        }
    }

    public static List<LrcLine> parseLrc(String lrcContent) {
        List<LrcLine> lines = new ArrayList<>();
        if (lrcContent == null || lrcContent.trim().isEmpty()) {
            return lines;
        }

        String[] rows = lrcContent.split("\n");
        // Regex para capturar [mm:ss.xx]
        Pattern pattern = Pattern.compile("\\[(\\d{2,}):(\\d{2})(?:\\.(\\d{2,3}))?](.*)");

        for (String row : rows) {
            Matcher matcher = pattern.matcher(row);
            while (matcher.find()) {
                String minStr = matcher.group(1);
                String secStr = matcher.group(2);
                String milStr = matcher.group(3);
                String text = matcher.group(4);

                if (minStr != null && secStr != null) {
                    long min = Long.parseLong(minStr);
                    long sec = Long.parseLong(secStr);
                    long mil = 0;
                    if (milStr != null) {
                        if (milStr.length() == 2) milStr += "0";
                        mil = Long.parseLong(milStr);
                    }

                    long timeMs = (min * 60 * 1000) + (sec * 1000) + mil;
                    if (text != null) {
                        lines.add(new LrcLine(timeMs, text.trim()));
                    }
                }
            }
        }
        
        Collections.sort(lines);
        return lines;
    }
}
