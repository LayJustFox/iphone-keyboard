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
    private static final int MAX = 15000, KEEP = 12000;
    /** Separates the two words of a remembered word pair (for next-word prediction). */
    private static final char PAIR = '\u0001';

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

    /** Remembers that {@code word} followed {@code prev} (next-word prediction). */
    void learnPair(String prev, String word, Locale locale) {
        if (prev == null || prev.isEmpty() || word.isEmpty()) return;
        load();
        String k = prev.toLowerCase(locale) + PAIR + word.toLowerCase(locale);
        Integer c = counts.get(k);
        counts.put(k, c == null ? 1 : c + 1);
        dirty = true;
        if (counts.size() > MAX) prune();
    }

    /** Words the user most often typed after {@code prev}. */
    List<String> next(String prev, Locale locale, int max) {
        load();
        String p = prev.toLowerCase(locale) + PAIR;
        ArrayList<Map.Entry<String, Integer>> found = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getKey().startsWith(p)) found.add(e);
        }
        Collections.sort(found, BY_COUNT_DESC);
        ArrayList<String> out = new ArrayList<>();
        for (int i = 0; i < found.size() && out.size() < max; i++) {
            out.add(found.get(i).getKey().substring(p.length()));
        }
        return out;
    }

    /** How often the user typed this word (0 = never). */
    int count(String lower) {
        load();
        Integer c = counts.get(lower);
        return c == null ? 0 : c;
    }

    /** The user's own words, typed at least twice or kept on purpose, never get "corrected". */
    boolean isKnown(String lower) {
        return count(lower) >= 2;
    }

    /** The user insisted on this spelling (undid a correction or added it): remember it. */
    void keep(String word, Locale locale) {
        load();
        String w = word.toLowerCase(locale);
        Integer c = counts.get(w);
        counts.put(w, Math.max(3, (c == null ? 0 : c) + 2));
        dirty = true;
        save();
    }

    void remove(String word) {
        load();
        if (counts.remove(word) != null) {
            dirty = true;
            save();
        }
    }

    /** Map of word → count used to favour the user's words in corrections. */
    Map<String, Integer> boostMap() {
        load();
        return counts;
    }

    /** The user's words, most typed first (pairs excluded). */
    List<String> words(int max) {
        load();
        ArrayList<Map.Entry<String, Integer>> all = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getKey().indexOf(PAIR) < 0) all.add(e);
        }
        Collections.sort(all, BY_COUNT_DESC);
        ArrayList<String> out = new ArrayList<>();
        for (int i = 0; i < all.size() && out.size() < max; i++) out.add(all.get(i).getKey());
        return out;
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
            if (k.length() > p.length() && k.startsWith(p) && k.indexOf(PAIR) < 0) found.add(e);
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
