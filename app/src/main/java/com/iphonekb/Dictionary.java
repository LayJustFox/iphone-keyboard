package com.iphonekb;

import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Built-in word list for one language (most frequent words, from OpenSubtitles 2018 via
 * hermitdave/FrequencyWords, CC BY-SA 4.0). Used for completions and autocorrect.
 * Loaded once in the background; everything stays on the phone.
 */
final class Dictionary {

    private static final HashMap<String, Dictionary> CACHE = new HashMap<>();
    /** Minimum weighted frequency for an automatic correction (keeps rare words out). */
    static double MIN_SCORE = 1500;
    /** Words this frequent are real words, not typos. */
    static final int COMMON = 1500;

    private final String lang;
    private final HashMap<String, Integer> freq = new HashMap<>();
    private String[] sorted = new String[0];
    private volatile boolean loaded;
    private final String alphabet;
    private final HashMap<Character, String> neighbours = new HashMap<>();

    private Dictionary(String lang) {
        this.lang = lang;
        switch (lang) {
            case "ru": alphabet = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"; break;
            case "tr": alphabet = "abcçdefgğhıijklmnoöprsştuüvyz"; break;
            default: alphabet = "abcdefghijklmnopqrstuvwxyz'"; break;
        }
        buildNeighbours();
    }

    /** Returns the dictionary for a language (loading starts in the background on first use). */
    static synchronized Dictionary get(AssetManager assets, String lang) {
        Dictionary d = CACHE.get(lang);
        if (d == null) {
            d = new Dictionary(lang);
            CACHE.put(lang, d);
            d.loadAsync(assets);
        }
        return d;
    }

    /** For tests: a dictionary from given words and their counts. */
    static Dictionary of(String lang, String[] words, int[] counts) {
        Dictionary d = new Dictionary(lang);
        for (int i = 0; i < words.length; i++) d.freq.put(words[i], counts[i]);
        d.finish();
        return d;
    }

    /** "ещё" and "еще" are the same word (the word lists mostly use е). */
    private String norm(String w) {
        return "ru".equals(lang) ? w.replace('ё', 'е') : w;
    }

    boolean isLoaded() {
        return loaded;
    }

    private void loadAsync(final AssetManager assets) {
        if (assets == null) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try (InputStream in = assets.open("dict_" + lang + ".txt");
                     BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        int tab = line.indexOf('\t');
                        if (tab <= 0) continue;
                        try {
                            freq.put(line.substring(0, tab), Integer.parseInt(line.substring(tab + 1)));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                } catch (Exception ignored) {
                    // No list for this language (e.g. Japanese): suggestions use learned words only.
                }
                finish();
            }
        }, "dict-" + lang);
        t.setDaemon(true);
        t.start();
    }

    private void finish() {
        String[] s = freq.keySet().toArray(new String[0]);
        Arrays.sort(s);
        sorted = s;
        loaded = true;
    }

    boolean contains(String lower) {
        return loaded && (freq.containsKey(lower) || freq.containsKey(norm(lower)));
    }

    int frequency(String lower) {
        if (!loaded) return 0;
        Integer f = freq.get(lower);
        if (f == null && "ru".equals(lang)) f = freq.get(norm(lower));
        return f == null ? 0 : f;
    }

    /** Most frequent words starting with the prefix (longer than it). */
    List<String> complete(String lowerPrefix, int max) {
        ArrayList<String> out = new ArrayList<>();
        if (!loaded || lowerPrefix.isEmpty()) return out;
        String[] s = sorted;
        int i = Arrays.binarySearch(s, lowerPrefix);
        if (i < 0) i = -i - 1;
        // Keep the best few by frequency among (at most) the first 4000 matches.
        String[] best = new String[max];
        int[] bestF = new int[max];
        for (int scanned = 0; i < s.length && scanned < 4000; i++, scanned++) {
            String w = s[i];
            if (!w.startsWith(lowerPrefix)) break;
            if (w.length() == lowerPrefix.length()) continue;
            int f = freq.get(w);
            for (int k = 0; k < max; k++) {
                if (best[k] == null || f > bestF[k]) {
                    for (int m = max - 1; m > k; m--) {
                        best[m] = best[m - 1];
                        bestF[m] = bestF[m - 1];
                    }
                    best[k] = w;
                    bestF[k] = f;
                    break;
                }
            }
        }
        for (String w : best) if (w != null) out.add(w);
        return out;
    }

    // ---------------------------------------------------------------- autocorrect

    /**
     * Best correction for a word that is not in the dictionary, or null.
     * Looks at one edit away (a wrong, missing, extra or swapped letter); a slip onto a
     * neighbouring key counts most, like on iPhone.
     *
     * @param userBoost frequency of each word in the user's own typing (counts as extra weight)
     */
    String correct(String lowerWord, Map<String, Integer> userBoost) {
        if (!loaded || lowerWord.length() < 2 || lowerWord.length() > 24) return null;
        lowerWord = norm(lowerWord);
        // A word in the list can still be a common misspelling ("helo", "здраствуйте"):
        // correct it only when a far more frequent word is one slip away.
        int own = frequency(lowerWord);
        String best = null;
        double bestScore = 0;
        HashSet<String> seen = new HashSet<>();
        int n = lowerWord.length();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= n; i++) {
            // deletion (extra letter typed)
            if (i < n) {
                String c = lowerWord.substring(0, i) + lowerWord.substring(i + 1);
                if (c.length() < 4) c = null; // "вася" must not become "вас"
                // A doubled letter ("helllo") is the most common slip.
                boolean doubled = (i > 0 && lowerWord.charAt(i - 1) == lowerWord.charAt(i))
                        || (i + 1 < n && lowerWord.charAt(i + 1) == lowerWord.charAt(i));
                double sc = c == null ? 0 : score(c, doubled ? 0.9 : 0.35, userBoost, seen);
                if (sc > bestScore) { bestScore = sc; best = c; }
            }
            // transposition
            if (i < n - 1) {
                sb.setLength(0);
                sb.append(lowerWord, 0, i).append(lowerWord.charAt(i + 1)).append(lowerWord.charAt(i))
                        .append(lowerWord, i + 2, n);
                String c = sb.toString();
                double sc = score(c, 0.8, userBoost, seen);
                if (sc > bestScore) { bestScore = sc; best = c; }
            }
            for (int a = 0; a < alphabet.length(); a++) {
                char ch = alphabet.charAt(a);
                // replacement (wrong key)
                if (i < n && ch != lowerWord.charAt(i)) {
                    String c = lowerWord.substring(0, i) + ch + lowerWord.substring(i + 1);
                    String nb = neighbours.get(lowerWord.charAt(i));
                    boolean near = nb != null && nb.indexOf(ch) >= 0;
                    double sc = score(c, near ? 0.5 : 0.02, userBoost, seen);
                    if (sc > bestScore) { bestScore = sc; best = c; }
                }
                // insertion (missed letter; a missed double letter is very common)
                String c = lowerWord.substring(0, i) + ch + lowerWord.substring(i);
                boolean dbl = (i > 0 && lowerWord.charAt(i - 1) == ch) || (i < n && lowerWord.charAt(i) == ch);
                double sc = score(c, dbl ? 0.9 : 0.35, userBoost, seen);
                if (sc > bestScore) { bestScore = sc; best = c; }
            }
        }
        // Only confident, reasonably common corrections.
        if (best == null) return null;
        // A real, common word is never "corrected" into another word — only a missing
        // apostrophe is added ("dont" → "don't").
        boolean apostrophe = best.length() == lowerWord.length() + 1 && best.indexOf('\'') >= 0
                && best.replace("'", "").equals(lowerWord);
        if (own >= COMMON && !apostrophe) return null;
        double need = own > 0 ? Math.max(MIN_SCORE, own * 25.0) : MIN_SCORE;
        return bestScore >= need ? best : null;
    }

    private double score(String cand, double weight, Map<String, Integer> userBoost, HashSet<String> seen) {
        if (!seen.add(cand)) return 0;
        Integer f = freq.get(cand);
        Integer u = userBoost == null ? null : userBoost.get(cand);
        if (f == null && (u == null || u < 2)) return 0;
        double base = (f == null ? 0 : f) + (u == null ? 0 : u * 5000.0);
        return base * weight;
    }

    private void buildNeighbours() {
        String[] rows;
        switch (lang) {
            case "ru": rows = new String[]{"йцукенгшщзх", "фывапролджэ", "ячсмитьбю"}; break;
            case "tr": rows = new String[]{"qwertyuıopğü", "asdfghjklşi", "zxcvbnmöç"}; break;
            default: rows = new String[]{"qwertyuiop", "asdfghjkl", "zxcvbnm"}; break;
        }
        for (int r = 0; r < rows.length; r++) {
            for (int i = 0; i < rows[r].length(); i++) {
                StringBuilder nb = new StringBuilder();
                addAt(nb, rows, r, i - 1);
                addAt(nb, rows, r, i + 1);
                addAt(nb, rows, r - 1, i);
                addAt(nb, rows, r - 1, i + 1);
                addAt(nb, rows, r + 1, i - 1);
                addAt(nb, rows, r + 1, i);
                neighbours.put(rows[r].charAt(i), nb.toString());
            }
        }
        if ("ru".equals(lang)) {
            neighbours.put('е', neighbours.get('е') + "ё");
            neighbours.put('ё', "е");
            neighbours.put('ь', neighbours.get('ь') + "ъ");
            neighbours.put('ъ', "ь");
        }
    }

    private static void addAt(StringBuilder nb, String[] rows, int r, int i) {
        if (r < 0 || r >= rows.length || i < 0 || i >= rows[r].length()) return;
        nb.append(rows[r].charAt(i));
    }

    static String lower(String w, String lang) {
        return w.toLowerCase(Layouts.locale(lang));
    }

    static Locale localeOf(String lang) {
        return Layouts.locale(lang);
    }
}
