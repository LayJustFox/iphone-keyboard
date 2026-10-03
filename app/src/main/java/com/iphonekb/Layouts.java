package com.iphonekb;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Key layouts and per-language labels. */
final class Layouts {
    static final int LETTERS = 0, SYM1 = 1, SYM2 = 2;

    static final String[] ALL_LANGS = {"ru", "en", "tr", "ja"};

    // Return-key kinds
    static final int RET = 0, SEARCH = 1, SEND = 2, GO = 3, DONE = 4, NEXT = 5, CONFIRM = 6;

    private Layouts() {}

    // ---------------------------------------------------------------- languages

    static Locale locale(String lang) {
        switch (lang) {
            case "ru": return new Locale("ru", "RU");
            case "tr": return new Locale("tr", "TR");
            case "ja": return Locale.JAPAN;
            default: return Locale.US;
        }
    }

    /** Shown on the space bar for a moment after switching. */
    static String name(String lang) {
        switch (lang) {
            case "ru": return "Русский";
            case "tr": return "Türkçe";
            case "ja": return "日本語 ローマ字";
            default: return "English";
        }
    }

    /** Name in the settings screen. */
    static String settingsName(String lang) {
        switch (lang) {
            case "ru": return "🇷🇺  Русский (ЙЦУКЕН)";
            case "tr": return "🇹🇷  Türkçe (Q)";
            case "ja": return "🇯🇵  日本語 (ромадзи → кана)";
            default: return "🇺🇸  English (QWERTY)";
        }
    }

    static String space(String lang) {
        switch (lang) {
            case "ru": return "Пробел";
            case "tr": return "boşluk";
            case "ja": return "空白";
            default: return "space";
        }
    }

    static String abc(String lang) {
        return "ru".equals(lang) ? "АБВ" : "ABC";
    }

    static String returnLabel(String lang, int kind) {
        String[] l;
        switch (lang) {
            case "ru": l = new String[]{"Ввод", "Поиск", "Отпр.", "Перейти", "Готово", "Далее", "Ввод"}; break;
            case "tr": l = new String[]{"Enter", "Ara", "Gönder", "Git", "Bitti", "Sonraki", "Enter"}; break;
            case "ja": l = new String[]{"改行", "検索", "送信", "開く", "完了", "次へ", "確定"}; break;
            default: l = new String[]{"return", "search", "send", "go", "done", "next", "return"}; break;
        }
        return l[kind];
    }

    // ---------------------------------------------------------------- pages

    static Layout get(String lang, int mode) {
        if (mode == LETTERS) return letters(lang);
        return symbols(lang, mode == SYM2);
    }

    private static Layout letters(String lang) {
        String r1, r2, r3;
        int cols;
        Map<String, String> alts;
        switch (lang) {
            case "ru":
                r1 = "йцукенгшщзх"; r2 = "фывапролджэ"; r3 = "ячсмитьбю";
                cols = 11; alts = RU_ALTS; break;
            case "tr":
                r1 = "qwertyuıopğü"; r2 = "asdfghjklşi"; r3 = "zxcvbnmöç";
                cols = 12; alts = TR_ALTS; break;
            case "ja":
                r1 = "qwertyuiop"; r2 = "asdfghjkl"; r3 = "zxcvbnmー";
                cols = 10; alts = JA_ALTS; break;
            default:
                r1 = "qwertyuiop"; r2 = "asdfghjkl"; r3 = "zxcvbnm";
                cols = 10; alts = EN_ALTS; break;
        }
        Key[] mid = chars(r3, alts, 1f);
        Key[] row3 = new Key[mid.length + 2];
        row3[0] = new Key(Key.SHIFT, "");
        System.arraycopy(mid, 0, row3, 1, mid.length);
        row3[row3.length - 1] = new Key(Key.DELETE, "");
        return new Layout(cols, new Key[][]{
                chars(r1, alts, 1f),
                chars(r2, alts, 1f),
                row3,
                bottom("123"),
        });
    }

    private static Layout symbols(String lang, boolean second) {
        String cur;
        String others;
        switch (lang) {
            case "ru": cur = "₽"; others = "$€£"; break;
            case "tr": cur = "₺"; others = "$€£"; break;
            case "ja": cur = "¥"; others = "$€£"; break;
            default: cur = "$"; others = "€£¥"; break;
        }
        String r1 = second ? "[]{}#%^*+=" : "1234567890";
        String r2 = second ? "_\\|~<>" + others + "•" : "-/:;()" + cur + "&@\"";
        String r3 = "ja".equals(lang) ? "。、？！ー" : ".,?!'";

        Key[] mid = chars(r3, SYM_ALTS, 1.4f);
        Key[] row3 = new Key[mid.length + 2];
        row3[0] = new Key(Key.MORE, second ? "123" : "#+=");
        System.arraycopy(mid, 0, row3, 1, mid.length);
        row3[row3.length - 1] = new Key(Key.DELETE, "");
        return new Layout(10, new Key[][]{
                chars(r1, SYM_ALTS, 1f),
                chars(r2, SYM_ALTS, 1f),
                row3,
                bottom(abc(lang)),
        });
    }

    private static Key[] bottom(String modeLabel) {
        return new Key[]{
                new Key(Key.MODE, modeLabel),
                new Key(Key.EMOJI, ""),
                new Key(Key.SPACE, ""),
                new Key(Key.RETURN, ""),
        };
    }

    private static Key[] chars(String s, Map<String, String> alts, float width) {
        int n = s.codePointCount(0, s.length());
        Key[] keys = new Key[n];
        int i = 0;
        for (int off = 0; off < s.length(); ) {
            int cp = s.codePointAt(off);
            String label = new String(Character.toChars(cp));
            off += Character.charCount(cp);
            // In the romaji layout "ー" types "-", which becomes ー inside the composition.
            String output = label;
            String a = alts.get(label);
            keys[i++] = Key.ch(label, output, a == null ? null : a.split(" "), width);
        }
        return keys;
    }

    // ---------------------------------------------------------------- long-press variants

    private static final Map<String, String> EN_ALTS = new HashMap<>();
    private static final Map<String, String> RU_ALTS = new HashMap<>();
    private static final Map<String, String> TR_ALTS = new HashMap<>();
    private static final Map<String, String> JA_ALTS = new HashMap<>();
    private static final Map<String, String> SYM_ALTS = new HashMap<>();

    static {
        EN_ALTS.put("a", "à á â ä æ ã å ā");
        EN_ALTS.put("c", "ç ć č");
        EN_ALTS.put("e", "è é ê ë ē ė ę");
        EN_ALTS.put("i", "î ï í ī į ì");
        EN_ALTS.put("l", "ł");
        EN_ALTS.put("n", "ñ ń");
        EN_ALTS.put("o", "ô ö ò ó œ ø ō õ");
        EN_ALTS.put("s", "ß ś š");
        EN_ALTS.put("u", "û ü ù ú ū");
        EN_ALTS.put("y", "ÿ");
        EN_ALTS.put("z", "ž ź ż");

        RU_ALTS.put("е", "ё");
        RU_ALTS.put("ь", "ъ");

        TR_ALTS.putAll(EN_ALTS);
        TR_ALTS.put("c", "ć č");
        TR_ALTS.put("g", "ğ");
        TR_ALTS.put("s", "ş ß ś š");
        TR_ALTS.put("o", "ô ò ó œ ø ō õ");
        TR_ALTS.put("u", "û ù ú ū");
        TR_ALTS.put("i", "î ï í ī į ì");
        TR_ALTS.remove("y");

        SYM_ALTS.put("0", "°");
        SYM_ALTS.put("-", "– — •");
        SYM_ALTS.put("/", "\\");
        SYM_ALTS.put("$", "₽ € £ ¥ ₺ ₩ ¢");
        SYM_ALTS.put("₽", "$ € £ ¥ ₺");
        SYM_ALTS.put("₺", "$ € £ ¥ ₽");
        SYM_ALTS.put("¥", "$ € £ ₽ ₺");
        SYM_ALTS.put("&", "§");
        SYM_ALTS.put("\"", "« » „ “ ”");
        SYM_ALTS.put(".", "…");
        SYM_ALTS.put("?", "¿");
        SYM_ALTS.put("!", "¡");
        SYM_ALTS.put("'", "‘ ’ `");
        SYM_ALTS.put("%", "‰");
        SYM_ALTS.put("。", "…");
    }
}
