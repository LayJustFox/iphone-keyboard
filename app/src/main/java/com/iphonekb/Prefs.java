package com.iphonekb;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Settings shared by the settings screen and the keyboard. */
final class Prefs {
    static final String THEME_AUTO = "auto", THEME_LIGHT = "light", THEME_DARK = "dark";

    private final SharedPreferences sp;

    String[] langs;
    String currentLang;
    boolean sound, vibrate, autoCap, doubleSpace, suggestions;
    String theme;
    int dictGeneration;

    Prefs(Context c) {
        sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
        reload();
    }

    void reload() {
        String raw = sp.getString("langs", "ru,en");
        ArrayList<String> list = new ArrayList<>();
        for (String l : Layouts.ALL_LANGS) {
            if (("," + raw + ",").contains("," + l + ",")) list.add(l);
        }
        if (list.isEmpty()) list.add("ru");
        langs = list.toArray(new String[0]);
        currentLang = sp.getString("current", langs[0]);
        if (!list.contains(currentLang)) currentLang = langs[0];
        sound = sp.getBoolean("sound", true);
        vibrate = sp.getBoolean("vibrate", true);
        autoCap = sp.getBoolean("autocap", true);
        doubleSpace = sp.getBoolean("double_space", true);
        suggestions = sp.getBoolean("suggestions", true);
        theme = sp.getString("theme", THEME_AUTO);
        dictGeneration = sp.getInt("dict_gen", 0);
    }

    boolean hasLang(String l) {
        for (String x : langs) if (x.equals(l)) return true;
        return false;
    }

    void setLangs(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String x : l) {
            if (sb.length() > 0) sb.append(',');
            sb.append(x);
        }
        sp.edit().putString("langs", sb.toString()).apply();
        reload();
    }

    void setCurrentLang(String l) {
        currentLang = l;
        sp.edit().putString("current", l).apply();
    }

    void setBool(String key, boolean v) {
        sp.edit().putBoolean(key, v).apply();
        reload();
    }

    void setTheme(String t) {
        sp.edit().putString("theme", t).apply();
        reload();
    }

    /** Tells the keyboard to forget learned words next time it opens. */
    void bumpDictGeneration() {
        sp.edit().putInt("dict_gen", sp.getInt("dict_gen", 0) + 1).apply();
        reload();
    }

    // ---------------------------------------------------------------- frequently used emoji

    private static final String[] DEFAULT_RECENT = {
            "😂", "❤️", "👍", "😭", "🙏", "😘", "🥰", "😍", "😊", "🎉", "🔥", "😁",
    };

    List<String> recentEmoji() {
        String raw = sp.getString("recent_emoji", "");
        final ArrayList<String[]> rows = new ArrayList<>();
        if (!raw.isEmpty()) {
            for (String line : raw.split("\n")) {
                int t = line.lastIndexOf('\t');
                if (t > 0) rows.add(new String[]{line.substring(0, t), line.substring(t + 1)});
            }
        }
        Collections.sort(rows, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return Integer.compare(parse(b[1]), parse(a[1]));
            }
        });
        ArrayList<String> out = new ArrayList<>();
        for (String[] r : rows) out.add(r[0]);
        for (String d : DEFAULT_RECENT) {
            if (out.size() >= 30) break;
            if (!out.contains(d)) out.add(d);
        }
        return out.size() > 30 ? out.subList(0, 30) : out;
    }

    void recordEmoji(String e) {
        String raw = sp.getString("recent_emoji", "");
        ArrayList<String[]> rows = new ArrayList<>();
        boolean found = false;
        if (!raw.isEmpty()) {
            for (String line : raw.split("\n")) {
                int t = line.lastIndexOf('\t');
                if (t <= 0) continue;
                String[] r = {line.substring(0, t), line.substring(t + 1)};
                if (r[0].equals(e)) {
                    r[1] = String.valueOf(parse(r[1]) + 1);
                    found = true;
                }
                rows.add(r);
            }
        }
        // New emoji go first so they survive the trim among equal counts (the sort is stable).
        if (!found) rows.add(0, new String[]{e, "1"});
        Collections.sort(rows, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return Integer.compare(parse(b[1]), parse(a[1]));
            }
        });
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size() && i < 40; i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(rows.get(i)[0]).append('\t').append(rows.get(i)[1]);
        }
        sp.edit().putString("recent_emoji", sb.toString()).apply();
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
