package com.iphonekb;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Translation through Yandex Cloud Translate (the official Yandex translation API).
 * Needs the user's own API key from Yandex Cloud. Text goes to Yandex only while the translator
 * is open and only what is typed into it; nothing else leaves the phone.
 */
final class TranslateEngine {

    interface Callback {
        /** @param req request number (stale results are ignored by the caller) */
        void onTranslated(int req, String detectedSource, String text);

        void onStatus(int req, String status, boolean busy);
    }

    static final String ENDPOINT = "https://translate.api.cloud.yandex.net/translate/v2/translate";

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
     * @param src    source code or "auto" (Yandex detects it)
     * @param key    Yandex Cloud API key (or IAM token starting with "t1.")
     * @param folder folder ID (needed for IAM tokens and user accounts; optional for API keys)
     */
    void translate(final int req, final String text, final String src, final String dst,
                   final String key, final String folder, final Callback cb) {
        if (text.trim().isEmpty()) {
            cb.onTranslated(req, null, "");
            return;
        }
        if (key == null || key.trim().isEmpty()) {
            cb.onStatus(req, "Добавьте ключ Yandex Cloud в настройках → Переводчик, или нажмите "
                    + "«Яндекс», чтобы открыть текст в Яндекс Переводчике", false);
            return;
        }
        cb.onStatus(req, "Перевожу…", true);
        net.execute(new Runnable() {
            @Override
            public void run() {
                final String[] out = request(text, src, dst, key.trim(), folder == null ? "" : folder.trim());
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (out[0] != null) cb.onTranslated(req, out[1], out[0]);
                        else cb.onStatus(req, out[2], false);
                    }
                });
            }
        });
    }

    /** @return {translation, detectedLanguage, error} */
    static String[] request(String text, String src, String dst, String key, String folder) {
        HttpURLConnection c = null;
        try {
            JSONObject body = new JSONObject();
            body.put("targetLanguageCode", dst);
            if (!"auto".equals(src)) body.put("sourceLanguageCode", src);
            if (!folder.isEmpty()) body.put("folderId", folder);
            JSONArray texts = new JSONArray();
            texts.put(text);
            body.put("texts", texts);

            c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Authorization", (key.startsWith("t1.") ? "Bearer " : "Api-Key ") + key);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            String resp = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code == 401 || code == 403) return new String[]{null, null, "Яндекс не принял ключ — проверьте его в настройках"};
            if (code >= 400) {
                String msg = "";
                try {
                    msg = new JSONObject(resp).optString("message", "");
                } catch (Exception ignored) {
                }
                return new String[]{null, null, "Ошибка Яндекса (" + code + ")" + (msg.isEmpty() ? "" : ": " + msg)};
            }
            JSONObject t = new JSONObject(resp).getJSONArray("translations").getJSONObject(0);
            return new String[]{t.optString("text", ""), t.optString("detectedLanguageCode", null), null};
        } catch (java.net.UnknownHostException e) {
            return new String[]{null, null, "Нет интернета"};
        } catch (java.net.SocketTimeoutException e) {
            return new String[]{null, null, "Яндекс не отвечает — попробуйте ещё раз"};
        } catch (Exception e) {
            return new String[]{null, null, "Не удалось перевести"};
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String read(InputStream in) throws java.io.IOException {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** Opens the text in the Yandex Translator app (or translate.yandex.ru if it isn't installed). */
    static android.content.Intent openInYandex(android.content.Context ctx, String text, String src, String dst) {
        android.content.Intent app = new android.content.Intent(android.content.Intent.ACTION_SEND);
        app.setType("text/plain");
        app.putExtra(android.content.Intent.EXTRA_TEXT, text);
        app.setPackage("ru.yandex.translate");
        app.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        if (app.resolveActivity(ctx.getPackageManager()) != null) return app;
        String url = "https://translate.yandex.ru/?source_lang=" + ("auto".equals(src) ? "" : src)
                + "&target_lang=" + dst + "&text=" + android.net.Uri.encode(text);
        android.content.Intent web = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url));
        web.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        return web;
    }

    void close() {
        // nothing to release
    }
}
