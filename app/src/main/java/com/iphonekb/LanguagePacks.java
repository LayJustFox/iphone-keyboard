package com.iphonekb;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;

import java.util.HashSet;
import java.util.Set;

/** Translator language packs on the phone: list, download, delete (Google ML Kit). */
final class LanguagePacks {

    interface Listener {
        void onDone(boolean ok);
    }

    interface ListListener {
        void onList(Set<String> downloaded);
    }

    /** Packs being downloaded right now (shared by the keyboard and the settings screen). */
    static final Set<String> DOWNLOADING = new HashSet<>();

    private LanguagePacks() {}

    static boolean supported(String code) {
        return TranslateLanguage.fromLanguageTag(code) != null;
    }

    static void list(final ListListener l) {
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel.class)
                .addOnSuccessListener(new OnSuccessListener<Set<TranslateRemoteModel>>() {
                    @Override
                    public void onSuccess(Set<TranslateRemoteModel> models) {
                        Set<String> out = new HashSet<>();
                        for (TranslateRemoteModel m : models) out.add(m.getLanguage());
                        l.onList(out);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(Exception e) {
                        l.onList(new HashSet<String>());
                    }
                });
    }

    static void download(final String code, boolean wifiOnly, final Listener l) {
        DownloadConditions.Builder b = new DownloadConditions.Builder();
        if (wifiOnly) b.requireWifi();
        DOWNLOADING.add(code);
        RemoteModelManager.getInstance()
                .download(new TranslateRemoteModel.Builder(code).build(), b.build())
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void v) {
                        DOWNLOADING.remove(code);
                        l.onDone(true);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(Exception e) {
                        DOWNLOADING.remove(code);
                        l.onDone(false);
                    }
                });
    }

    static void delete(String code, final Listener l) {
        RemoteModelManager.getInstance()
                .deleteDownloadedModel(new TranslateRemoteModel.Builder(code).build())
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void v) {
                        l.onDone(true);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(Exception e) {
                        l.onDone(false);
                    }
                });
    }
}
