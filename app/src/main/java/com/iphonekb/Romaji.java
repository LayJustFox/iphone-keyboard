package com.iphonekb;

import java.util.HashMap;
import java.util.Locale;

/** Romaji → hiragana / katakana, following the usual IME conventions. */
final class Romaji {
    private static final HashMap<String, String> T = new HashMap<>();
    private static int maxLen = 1;

    private Romaji() {}

    private static void put(String keys, String values) {
        String[] k = keys.split(" ");
        String[] v = values.split(" ");
        if (k.length != v.length) throw new IllegalStateException("romaji table: " + keys);
        for (int i = 0; i < k.length; i++) {
            T.put(k[i], v[i]);
            maxLen = Math.max(maxLen, k[i].length());
        }
    }

    static {
        put("a i u e o", "あ い う え お");
        put("ka ki ku ke ko", "か き く け こ");
        put("ga gi gu ge go", "が ぎ ぐ げ ご");
        put("sa si shi su se so", "さ し し す せ そ");
        put("za zi ji zu ze zo", "ざ じ じ ず ぜ ぞ");
        put("ta ti chi tu tsu te to", "た ち ち つ つ て と");
        put("da di du de do", "だ ぢ づ で ど");
        put("na ni nu ne no", "な に ぬ ね の");
        put("ha hi hu fu he ho", "は ひ ふ ふ へ ほ");
        put("ba bi bu be bo", "ば び ぶ べ ぼ");
        put("pa pi pu pe po", "ぱ ぴ ぷ ぺ ぽ");
        put("ma mi mu me mo", "ま み む め も");
        put("ya yu yo", "や ゆ よ");
        put("ra ri ru re ro", "ら り る れ ろ");
        put("wa wi wu we wo", "わ うぃ う うぇ を");
        put("ca ci cu ce co", "か し く せ こ");
        put("qa qi qu qe qo", "くぁ くぃ く くぇ くぉ");
        put("la li lu le lo", "ぁ ぃ ぅ ぇ ぉ");
        put("xa xi xu xe xo", "ぁ ぃ ぅ ぇ ぉ");
        put("kya kyi kyu kye kyo", "きゃ きぃ きゅ きぇ きょ");
        put("gya gyi gyu gye gyo", "ぎゃ ぎぃ ぎゅ ぎぇ ぎょ");
        put("sha shu she sho sya syu sye syo", "しゃ しゅ しぇ しょ しゃ しゅ しぇ しょ");
        put("ja ju je jo jya jyu jye jyo zya zyu zye zyo",
                "じゃ じゅ じぇ じょ じゃ じゅ じぇ じょ じゃ じゅ じぇ じょ");
        put("cha chu che cho cya cyu cye cyo tya tyu tye tyo",
                "ちゃ ちゅ ちぇ ちょ ちゃ ちゅ ちぇ ちょ ちゃ ちゅ ちぇ ちょ");
        put("dya dyu dye dyo", "ぢゃ ぢゅ ぢぇ ぢょ");
        put("nya nyi nyu nye nyo", "にゃ にぃ にゅ にぇ にょ");
        put("hya hyi hyu hye hyo", "ひゃ ひぃ ひゅ ひぇ ひょ");
        put("bya byi byu bye byo", "びゃ びぃ びゅ びぇ びょ");
        put("pya pyi pyu pye pyo", "ぴゃ ぴぃ ぴゅ ぴぇ ぴょ");
        put("mya myi myu mye myo", "みゃ みぃ みゅ みぇ みょ");
        put("rya ryi ryu rye ryo", "りゃ りぃ りゅ りぇ りょ");
        put("lya lyu lyo xya xyu xyo", "ゃ ゅ ょ ゃ ゅ ょ");
        put("ltu xtu ltsu xtsu lwa xwa", "っ っ っ っ ゎ ゎ");
        put("fa fi fe fo fya fyu fyo", "ふぁ ふぃ ふぇ ふぉ ふゃ ふゅ ふょ");
        put("va vi vu ve vo", "ゔぁ ゔぃ ゔ ゔぇ ゔぉ");
        put("tsa tsi tse tso", "つぁ つぃ つぇ つぉ");
        put("thi thu dhi dhu twu dwu", "てぃ てゅ でぃ でゅ とぅ どぅ");
        put("ye wha whi whe who", "いぇ うぁ うぃ うぇ うぉ");
        put("kwa gwa", "くぁ ぐぁ");
        put("xka xke lka lke", "ゕ ゖ ゕ ゖ");
    }

    private static boolean vowel(char c) {
        return c == 'a' || c == 'i' || c == 'u' || c == 'e' || c == 'o';
    }

    private static boolean consonant(char c) {
        return c >= 'a' && c <= 'z' && !vowel(c) && c != 'n';
    }

    /**
     * @param fin true when the text is being confirmed: a trailing "n" becomes ん.
     *            While typing it stays "n" because the next letter may make it な, に…
     */
    static String toHiragana(CharSequence src, boolean fin) {
        String s = src.toString().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '-') {
                out.append('ー');
                i++;
                continue;
            }
            if (c == 'n') {
                if (i + 1 >= n) {
                    out.append(fin ? "ん" : "n");
                    i++;
                    continue;
                }
                char d = s.charAt(i + 1);
                if (d == '\'') {
                    out.append('ん');
                    i += 2;
                    continue;
                }
                if (d == 'n') {
                    // "konna" → こんな: the second n still starts the next syllable.
                    boolean nextStartsSyllable = i + 2 < n && (vowel(s.charAt(i + 2)) || s.charAt(i + 2) == 'y');
                    out.append('ん');
                    i += nextStartsSyllable ? 1 : 2;
                    continue;
                }
                if (!vowel(d) && d != 'y') {
                    out.append('ん');
                    i++;
                    continue;
                }
            }
            if (i + 1 < n && consonant(c) && (s.charAt(i + 1) == c || (c == 't' && s.charAt(i + 1) == 'c'))) {
                out.append('っ');
                i++;
                continue;
            }
            boolean matched = false;
            for (int len = Math.min(maxLen, n - i); len > 0; len--) {
                String v = T.get(s.substring(i, i + len));
                if (v != null) {
                    out.append(v);
                    i += len;
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    static String toKatakana(String hira) {
        StringBuilder sb = new StringBuilder(hira.length());
        for (int i = 0; i < hira.length(); i++) {
            char c = hira.charAt(i);
            if (c >= 0x3041 && c <= 0x3096) c = (char) (c + 0x60);
            sb.append(c);
        }
        return sb.toString();
    }
}
