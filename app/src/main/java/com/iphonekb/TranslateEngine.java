package com.iphonekb;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Translation through MyMemory (mymemory.translated.net): free, no key or account.
 * 5,000 characters a day per phone, 50,000 if an e-mail is given in settings.
 * Only the text typed into the translator is sent, and only while it is open.
 */
final class TranslateEngine {

    interface Callback {
        /** @param req request number (stale results are ignored by the caller) */
        void onTranslated(int req, String detectedSource, String text);

        void onStatus(int req, String status, boolean busy);
    }

    static final String ENDPOINT = "https://api.mymemory.translated.net/get";
    /** MyMemory accepts at most 500 bytes per request; longer text is sent in pieces. */
    private static final int MAX_BYTES = 450;

    /** Languages offered in the translator: code, name. "auto" = detect. */
    static final String[][] LANGS = {
            {"auto", "Автоопределение"},
            {"ru", "Русский"}, {"en", "Английский"}, {"tr", "Турецкий"}, {"ja", "Японский"},
            {"de", "Немецкий"}, {"fr", "Французский"}, {"es", "Испанский"}, {"it", "Итальянский"},
            {"pt", "Португальский"}, {"uk", "Украинский"}, {"be", "Белорусский"}, {"kk", "Казахский"},
            {"uz", "Узбекский"}, {"az", "Азербайджанский"}, {"hy", "Армянский"}, {"ka", "Грузинский"},
            {"pl", "Польский"}, {"cs", "Чешский"}, {"nl", "Нидерландский"}, {"sv", "Шведский"},
            {"fi", "Финский"}, {"el", "Греческий"}, {"he", "Иврит"}, {"ar", "Арабский"},
            {"fa", "Персидский"}, {"hi", "Хинди"}, {"zh", "Китайский"}, {"ko", "Корейский"},
            {"th", "Тайский"}, {"vi", "Вьетнамский"}, {"id", "Индонезийский"},
    };

    static String name(String code) {
        for (String[] l : LANGS) if (l[0].equals(code)) return l[1];
        return code;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "translate");
            t.setDaemon(true);
            return t;
        }
    });

    /**
     * @param src      source code or "auto"
     * @param fallback language to assume when the text gives no hint (the keyboard language)
     * @param email    optional e-mail for the higher daily limit
     */
    void translate(final int req, final String text, final String src, final String dst,
                   final String fallback, final String email, final Callback cb) {
        if (text.trim().isEmpty()) {
            cb.onTranslated(req, null, "");
            return;
        }
        cb.onStatus(req, "Перевожу…", true);
        net.execute(new Runnable() {
            @Override
            public void run() {
                String from = "auto".equals(src) ? detect(text, fallback) : src;
                String to = dst;
                if (from.equals(to)) to = "en".equals(to) ? "ru" : "en";
                final String detected = "auto".equals(src) ? from : null;
                final String[] out = request(text, from, to, email);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (out[0] != null) cb.onTranslated(req, detected, out[0]);
                        else cb.onStatus(req, out[1], false);
                    }
                });
            }
        });
    }

    /** Guesses the language from the letters used (good enough for a keyboard). */
    static String detect(String t, String fallback) {
        int cyr = 0, lat = 0, kana = 0, han = 0, hangul = 0, arab = 0, hebr = 0, greek = 0, geo = 0, arm = 0, thai = 0;
        boolean ukr = false, bel = false, turk = false, deu = false, esp = false, fra = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '¿' || c == '¡') esp = true;
            Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
            if (b == Character.UnicodeBlock.CYRILLIC) {
                cyr++;
                if ("іїєґІЇЄҐ".indexOf(c) >= 0) ukr = true;
                if ("ўЎ".indexOf(c) >= 0) bel = true;
            } else if (b == Character.UnicodeBlock.HIRAGANA || b == Character.UnicodeBlock.KATAKANA) kana++;
            else if (b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) han++;
            else if (b == Character.UnicodeBlock.HANGUL_SYLLABLES) hangul++;
            else if (b == Character.UnicodeBlock.ARABIC) arab++;
            else if (b == Character.UnicodeBlock.HEBREW) hebr++;
            else if (b == Character.UnicodeBlock.GREEK) greek++;
            else if (b == Character.UnicodeBlock.GEORGIAN) geo++;
            else if (b == Character.UnicodeBlock.ARMENIAN) arm++;
            else if (b == Character.UnicodeBlock.THAI) thai++;
            else if (Character.isLetter(c)) {
                lat++;
                if ("ğĞşŞıİ".indexOf(c) >= 0) turk = true;
                if ("äöüßÄÖÜ".indexOf(c) >= 0) deu = true;
                if (c == 'ñ' || c == 'Ñ') esp = true;
                if ("çéèêëàâîïôûœ".indexOf(Character.toLowerCase(c)) >= 0) fra = true;
            }
        }
        if (kana > 0) return "ja";
        if (hangul > 0) return "ko";
        if (han > 0) return "zh";
        if (cyr > 0 && cyr >= lat) return ukr ? "uk" : bel ? "be" : ("uk".equals(fallback) || "be".equals(fallback) ? fallback : "ru");
        if (arab > 0) return "fa".equals(fallback) ? "fa" : "ar";
        if (hebr > 0) return "he";
        if (greek > 0) return "el";
        if (geo > 0) return "ka";
        if (arm > 0) return "hy";
        if (thai > 0) return "th";
        if (lat > 0) {
            if (turk) return "tr";
            if (deu) return "de";
            if (esp) return "es";
            // Latin letters only: the keyboard language if it is a Latin one, else English.
            if (fallback != null && !"ru".equals(fallback) && !"ja".equals(fallback) && !"uk".equals(fallback)) {
                return fra && !"fr".equals(fallback) ? "fr" : fallback;
            }
            return fra ? "fr" : "en";
        }
        return fallback == null ? "en" : fallback;
    }

    private static String code(String c) {
        return "zh".equals(c) ? "zh-CN" : c;
    }

    /** @return {translation, error} */
    static String[] request(String text, String from, String to, String email) {
        StringBuilder all = new StringBuilder();
        for (String part : split(text)) {
            if (part.trim().isEmpty()) {
                all.append(part);
                continue;
            }
            String[] r = requestOne(part, from, to, email);
            if (r[0] == null) return r;
            if (all.length() > 0 && !Character.isWhitespace(all.charAt(all.length() - 1))) all.append(' ');
            all.append(r[0]);
        }
        return new String[]{all.toString(), null};
    }

    /** Splits text into pieces under the size limit, preferring sentence and word breaks. */
    static List<String> split(String text) {
        ArrayList<String> out = new ArrayList<>();
        String rest = text;
        while (rest.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            int cut = 0, bytes = 0, lastSentence = -1, lastSpace = -1;
            while (cut < rest.length()) {
                int len = String.valueOf(rest.charAt(cut)).getBytes(StandardCharsets.UTF_8).length;
                if (bytes + len > MAX_BYTES) break;
                bytes += len;
                char c = rest.charAt(cut);
                if (c == '.' || c == '!' || c == '?' || c == '\n') lastSentence = cut + 1;
                if (c == ' ') lastSpace = cut + 1;
                cut++;
            }
            int at = lastSentence > 0 ? lastSentence : (lastSpace > 0 ? lastSpace : cut);
            out.add(rest.substring(0, at).trim());
            rest = rest.substring(at);
        }
        if (!rest.trim().isEmpty() || out.isEmpty()) out.add(rest.trim());
        return out;
    }

    private static String[] requestOne(String text, String from, String to, String email) {
        HttpURLConnection c = null;
        try {
            String url = ENDPOINT + "?q=" + URLEncoder.encode(text, "UTF-8")
                    + "&langpair=" + URLEncoder.encode(code(from) + "|" + code(to), "UTF-8")
                    + "&mt=1"
                    + (email == null || email.trim().isEmpty() ? "" : "&de=" + URLEncoder.encode(email.trim(), "UTF-8"));
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            int status = c.getResponseCode();
            String resp = read(status >= 400 ? c.getErrorStream() : c.getInputStream());
            JSONObject o = new JSONObject(resp);
            boolean quota = o.optBoolean("quotaFinished", false);
            String translated = o.optJSONObject("responseData") == null ? ""
                    : o.optJSONObject("responseData").optString("translatedText", "");
            int code = o.optInt("responseStatus", status);
            if (quota || translated.startsWith("MYMEMORY WARNING")) {
                return new String[]{null, "Дневной лимит бесплатного перевода исчерпан. Укажите e-mail в "
                        + "настройках → Переводчик, чтобы лимит стал в 10 раз больше"};
            }
            if (code != 200 || translated.isEmpty()) {
                String d = o.optString("responseDetails", "");
                return new String[]{null, "Не удалось перевести" + (d.isEmpty() ? "" : ": " + d)};
            }
            return new String[]{unescape(translated), null};
        } catch (java.net.UnknownHostException e) {
            return new String[]{null, "Нет интернета"};
        } catch (java.net.SocketTimeoutException e) {
            return new String[]{null, "Переводчик не отвечает — попробуйте ещё раз"};
        } catch (Exception e) {
            return new String[]{null, "Не удалось перевести"};
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static String unescape(String s) {
        return s.replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&#34;", "\"").replace("&amp;", "&");
    }

    private static String read(InputStream in) throws java.io.IOException {
        if (in == null) return "{}";
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    void close() {
        // nothing to release
    }
}
