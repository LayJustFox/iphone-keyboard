package com.iphonekb;

import android.os.Handler;
import android.os.Looper;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

/**
 * On-device translation (Google ML Kit). Text never leaves the phone: the internet is used only
 * once per language to download its model (~30 MB). Results come back on the main thread.
 */
final class TranslateEngine {

    interface Callback {
        /** @param req request number (stale results are ignored by the caller) */
        void onTranslated(int req, String detectedSource, String text);

        void onStatus(int req, String status);
    }

    /** Languages offered in the translator: code, name. "auto" = detect. */
    static final String[][] LANGS = {
            {"auto", "Автоопределение"},
            {"ru", "Русский"}, {"en", "Английский"}, {"tr", "Турецкий"}, {"ja", "Японский"},
            {"de", "Немецкий"}, {"fr", "Французский"}, {"es", "Испанский"}, {"it", "Итальянский"},
            {"pt", "Португальский"}, {"uk", "Украинский"}, {"be", "Белорусский"}, {"pl", "Польский"},
            {"cs", "Чешский"}, {"nl", "Нидерландский"}, {"sv", "Шведский"}, {"fi", "Финский"},
            {"el", "Греческий"}, {"ka", "Грузинский"}, {"he", "Иврит"}, {"ar", "Арабский"},
            {"fa", "Персидский"}, {"hi", "Хинди"}, {"zh", "Китайский"}, {"ko", "Корейский"},
            {"th", "Тайский"}, {"vi", "Вьетнамский"}, {"id", "Индонезийский"},
    };

    static String name(String code) {
        for (String[] l : LANGS) if (l[0].equals(code)) return l[1];
        return code;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Translator translator;
    private String curSrc, curDst;
    private LanguageIdentifier identifier;

    /**
     * @param src      source code or "auto"
     * @param fallback source to use when detection fails (the keyboard language)
     */
    void translate(final int req, final String text, String src, final String dst, final String fallback,
                   final boolean wifiOnly, final Callback cb) {
        if (text.trim().isEmpty()) {
            cb.onTranslated(req, src, "");
            return;
        }
        if (!"auto".equals(src)) {
            run(req, text, src, dst, wifiOnly, cb);
            return;
        }
        if (identifier == null) identifier = LanguageIdentification.getClient();
        identifier.identifyLanguage(text)
                .addOnSuccessListener(new OnSuccessListener<String>() {
                    @Override
                    public void onSuccess(String code) {
                        String s = code == null || "und".equals(code) || TranslateLanguage.fromLanguageTag(code) == null
                                ? fallback : code;
                        if (s.equals(dst)) s = fallback.equals(dst) ? ("en".equals(dst) ? "ru" : "en") : fallback;
                        run(req, text, s, dst, wifiOnly, cb);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(Exception e) {
                        run(req, text, fallback, dst, wifiOnly, cb);
                    }
                });
    }

    private void run(final int req, final String text, final String src, String dst, final boolean wifiOnly,
                     final Callback cb) {
        if (src.equals(dst)) {
            cb.onTranslated(req, src, text);
            return;
        }
        String s = TranslateLanguage.fromLanguageTag(src), d = TranslateLanguage.fromLanguageTag(dst);
        if (s == null || d == null) {
            cb.onStatus(req, "Этот язык не поддерживается");
            return;
        }
        if (translator == null || !s.equals(curSrc) || !d.equals(curDst)) {
            if (translator != null) translator.close();
            translator = Translation.getClient(new TranslatorOptions.Builder()
                    .setSourceLanguage(s).setTargetLanguage(d).build());
            curSrc = s;
            curDst = d;
        }
        final Translator t = translator;
        final boolean[] done = {false};
        // If the model is not on the phone yet, say so while it downloads.
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!done[0]) cb.onStatus(req, "Загрузка языков для перевода… (один раз, ~30 МБ)");
            }
        }, 400);
        DownloadConditions.Builder cond = new DownloadConditions.Builder();
        if (wifiOnly) cond.requireWifi();
        t.downloadModelIfNeeded(cond.build())
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void v) {
                        t.translate(text)
                                .addOnSuccessListener(new OnSuccessListener<String>() {
                                    @Override
                                    public void onSuccess(String out) {
                                        done[0] = true;
                                        cb.onTranslated(req, src, out);
                                    }
                                })
                                .addOnFailureListener(new OnFailureListener() {
                                    @Override
                                    public void onFailure(Exception e) {
                                        done[0] = true;
                                        cb.onStatus(req, "Не удалось перевести");
                                    }
                                });
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(Exception e) {
                        done[0] = true;
                        cb.onStatus(req, wifiOnly
                                ? "Подключитесь к Wi-Fi, чтобы один раз скачать язык"
                                : "Нужен интернет, чтобы один раз скачать язык");
                    }
                });
    }

    void close() {
        if (translator != null) translator.close();
        translator = null;
        curSrc = curDst = null;
        if (identifier != null) identifier.close();
        identifier = null;
    }
}
