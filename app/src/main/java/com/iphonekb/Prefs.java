package com.iphonekb;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Every setting, shared by the settings screen, its live preview and the keyboard. */
final class Prefs {
    static final String THEME_AUTO = "auto", THEME_LIGHT = "light", THEME_DARK = "dark";
    static final String STYLE_GLASS = "glass", STYLE_CLASSIC = "classic";

    // Size presets
    static final String SIZE_AUTO = "auto", SIZE_SE = "se", SIZE_IPHONE = "iphone",
            SIZE_MAX = "max", SIZE_COMPACT = "compact", SIZE_LARGE = "large";

    // Animation speed
    static final int ANIM_OFF = 0, ANIM_FAST = 1, ANIM_SMOOTH = 2;

    /** Accent colours (iOS system colours): name, light, dark. */
    static final String[][] ACCENTS = {
            {"blue", "#007AFF", "#0A84FF"},
            {"indigo", "#5856D6", "#5E5CE6"},
            {"purple", "#AF52DE", "#BF5AF2"},
            {"pink", "#FF2D55", "#FF375F"},
            {"red", "#FF3B30", "#FF453A"},
            {"orange", "#FF9500", "#FF9F0A"},
            {"yellow", "#FFCC00", "#FFD60A"},
            {"green", "#34C759", "#30D158"},
            {"teal", "#30B0C7", "#40C8E0"},
            {"graphite", "#8E8E93", "#98989D"},
    };

    static final int[] CLIP_SIZES = {100, 500, 1000, 5000, 10000, 25000};

    private final SharedPreferences sp;

    // languages
    String[] langs;
    String currentLang;

    // look
    String theme, style, accent;
    int glassOpacity;       // 40..100 %
    boolean blur;           // real blur of the app behind (Android 12+)

    // size
    String sizePreset;
    int keyHeightPct, fontPct, hGapPct, vGapPct, radiusPct; // 50..150 %
    int sidePadDp, bottomPadDp;
    boolean globeRow;       // iPhone-style strip with 🌐 under the keys
    boolean numberRow;
    boolean limitHeight;    // never taller than ~45% of the screen

    // animation & feel
    int animSpeed;
    boolean popups, liquidTouch;
    boolean sound;
    int soundVolume;        // 0..100
    int haptic;             // 0 off, 1 light, 2 medium, 3 strong
    int longPressMs;

    // typing
    boolean autoCap, doubleSpace, suggestions, trackpad;
    int dictGeneration;

    // clipboard
    boolean clipboard;
    int clipMax;
    int clipGeneration;

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

        theme = sp.getString("theme", THEME_AUTO);
        style = sp.getString("style", STYLE_GLASS);
        accent = sp.getString("accent", "blue");
        glassOpacity = sp.getInt("glass_opacity", 78);
        blur = sp.getBoolean("blur", false);

        sizePreset = sp.getString("size_preset", SIZE_AUTO);
        keyHeightPct = sp.getInt("key_height", 100);
        fontPct = sp.getInt("font", 100);
        hGapPct = sp.getInt("hgap", 100);
        vGapPct = sp.getInt("vgap", 100);
        radiusPct = sp.getInt("radius", 100);
        sidePadDp = sp.getInt("side_pad", 3);
        bottomPadDp = sp.getInt("bottom_pad", 4);
        globeRow = sp.getBoolean("globe_row", true);
        numberRow = sp.getBoolean("number_row", false);
        limitHeight = sp.getBoolean("limit_height", true);

        animSpeed = sp.getInt("anim_speed", ANIM_SMOOTH);
        popups = sp.getBoolean("popups", true);
        liquidTouch = sp.getBoolean("liquid_touch", true);
        sound = sp.getBoolean("sound", true);
        soundVolume = sp.getInt("sound_volume", 60);
        haptic = sp.getInt("haptic", sp.getBoolean("vibrate", true) ? 1 : 0);
        longPressMs = sp.getInt("long_press", 380);

        autoCap = sp.getBoolean("autocap", true);
        doubleSpace = sp.getBoolean("double_space", true);
        suggestions = sp.getBoolean("suggestions", true);
        trackpad = sp.getBoolean("trackpad", true);
        dictGeneration = sp.getInt("dict_gen", 0);

        clipboard = sp.getBoolean("clipboard", true);
        clipMax = sp.getInt("clip_max", 5000);
        clipGeneration = sp.getInt("clip_gen", 0);
    }

    boolean hasLang(String l) {
        for (String x : langs) if (x.equals(l)) return true;
        return false;
    }

    int accentColor(boolean dark) {
        for (String[] a : ACCENTS) {
            if (a[0].equals(accent)) return android.graphics.Color.parseColor(dark ? a[2] : a[1]);
        }
        return dark ? 0xFF0A84FF : 0xFF007AFF;
    }

    // ---------------------------------------------------------------- writers

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

    void setInt(String key, int v) {
        sp.edit().putInt(key, v).apply();
        reload();
    }

    void setString(String key, String v) {
        sp.edit().putString(key, v).apply();
        reload();
    }

    void resetSizes() {
        sp.edit()
                .putString("size_preset", SIZE_AUTO)
                .putInt("key_height", 100).putInt("font", 100).putInt("hgap", 100)
                .putInt("vgap", 100).putInt("radius", 100)
                .putInt("side_pad", 3).putInt("bottom_pad", 4)
                .putBoolean("limit_height", true)
                .apply();
        reload();
    }

    /** Tells the keyboard to forget learned words next time it opens. */
    void bumpDictGeneration() {
        sp.edit().putInt("dict_gen", sp.getInt("dict_gen", 0) + 1).apply();
        reload();
    }

    /** Tells the keyboard to clear the clipboard history (pinned items stay). */
    void bumpClipGeneration() {
        sp.edit().putInt("clip_gen", sp.getInt("clip_gen", 0) + 1).apply();
        reload();
    }

    // ---------------------------------------------------------------- frequently used emoji

    private static final String[] DEFAULT_RECENT = {
            "😂", "❤️", "👍", "😭", "🙏", "😘", "🥰", "😍", "😊", "🎉", "🔥", "😁",
    };

    private static final Comparator<String[]> BY_COUNT = new Comparator<String[]>() {
        @Override
        public int compare(String[] a, String[] b) {
            return Integer.compare(parse(b[1]), parse(a[1]));
        }
    };

    private ArrayList<String[]> readRecent() {
        String raw = sp.getString("recent_emoji", "");
        ArrayList<String[]> rows = new ArrayList<>();
        if (raw.isEmpty()) return rows;
        for (String line : raw.split("\n")) {
            int t = line.lastIndexOf('\t');
            if (t > 0) rows.add(new String[]{line.substring(0, t), line.substring(t + 1)});
        }
        return rows;
    }

    List<String> recentEmoji() {
        ArrayList<String[]> rows = readRecent();
        Collections.sort(rows, BY_COUNT);
        ArrayList<String> out = new ArrayList<>();
        for (String[] r : rows) out.add(r[0]);
        for (String d : DEFAULT_RECENT) {
            if (out.size() >= 30) break;
            if (!out.contains(d)) out.add(d);
        }
        return out.size() > 30 ? out.subList(0, 30) : out;
    }

    void recordEmoji(String e) {
        ArrayList<String[]> rows = readRecent();
        boolean found = false;
        for (String[] r : rows) {
            if (r[0].equals(e)) {
                r[1] = String.valueOf(parse(r[1]) + 1);
                found = true;
            }
        }
        // New emoji go first so they survive the trim among equal counts (the sort is stable).
        if (!found) rows.add(0, new String[]{e, "1"});
        Collections.sort(rows, BY_COUNT);
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
