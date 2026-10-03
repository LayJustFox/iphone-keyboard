package com.iphonekb;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Words the user has typed, with how often. Kept in the app's private folder only
 * (backup is off in the manifest), never sent anywhere.
 */
final class WordStore {
    private static final int MAX = 6000, KEEP = 5000;

    private final File file;
    private final HashMap<String, Integer> counts = new HashMap<>();
    private boolean loaded, dirty;
    private int unsaved;

    WordStore(File file) {
        this.file = file;
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        if (!file.exists()) return;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int t = line.indexOf('\t');
                if (t <= 0) continue;
                try {
                    counts.put(line.substring(0, t), Integer.parseInt(line.substring(t + 1)));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    void learn(String word, Locale locale) {
        load();
        String w = word.toLowerCase(locale);
        Integer c = counts.get(w);
        counts.put(w, c == null ? 1 : c + 1);
        dirty = true;
        if (counts.size() > MAX) prune();
        if (++unsaved >= 25) save();
    }

    /** Most-typed words that start with the prefix (and are longer than it). */
    List<String> suggest(String prefix, Locale locale, int max) {
        load();
        String p = prefix.toLowerCase(locale);
        ArrayList<Map.Entry<String, Integer>> found = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            String k = e.getKey();
            if (k.length() > p.length() && k.startsWith(p)) found.add(e);
        }
        Collections.sort(found, BY_COUNT_DESC);
        ArrayList<String> out = new ArrayList<>();
        for (int i = 0; i < found.size() && out.size() < max; i++) out.add(found.get(i).getKey());
        return out;
    }

    void save() {
        if (!dirty) return;
        File tmp = new File(file.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
            }
            w.write(sb.toString());
        } catch (IOException e) {
            return;
        }
        if (tmp.renameTo(file)) {
            dirty = false;
            unsaved = 0;
        }
    }

    void clear() {
        counts.clear();
        loaded = true;
        dirty = false;
        unsaved = 0;
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private void prune() {
        ArrayList<Map.Entry<String, Integer>> all = new ArrayList<>(counts.entrySet());
        Collections.sort(all, BY_COUNT_DESC);
        HashMap<String, Integer> keep = new HashMap<>();
        for (int i = 0; i < KEEP && i < all.size(); i++) keep.put(all.get(i).getKey(), all.get(i).getValue());
        counts.clear();
        counts.putAll(keep);
    }

    private static final Comparator<Map.Entry<String, Integer>> BY_COUNT_DESC =
            new Comparator<Map.Entry<String, Integer>>() {
                @Override
                public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                    int c = Integer.compare(b.getValue(), a.getValue());
                    return c != 0 ? c : a.getKey().compareTo(b.getKey());
                }
            };
}
