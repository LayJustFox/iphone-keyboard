package com.iphonekb;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.os.Build;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.io.File;
import java.util.List;
import java.util.Locale;

public final class KeyboardService extends InputMethodService
        implements KeyboardView.Listener, EmojiView.Listener, ClipboardView.Listener {

    private static final int HIRA = 0, KATA = 1, LATIN = 2;
    private static final long QUICK_PASTE_MS = 3 * 60 * 1000;

    private Prefs prefs;
    private WordStore words;
    private ClipStore clips;
    private int dictGeneration, clipGeneration;
    private KeyboardView kv;
    private EmojiView ev;
    private ClipboardView cv;          // full clipboard panel (covers the keys)
    private ClipboardView searchPane;  // results above the keys while searching
    private Theme theme;
    private Vibrator vibrator;
    private AudioManager audio;
    private ClipboardManager clipboardManager;
    private boolean translucent;

    private String lang = "ru";
    private int mode = Layouts.LETTERS;
    private int shift = KeyboardView.SHIFT_OFF;
    private boolean autoShifted;
    private long lastShiftTap;
    private boolean lastWasSpace;

    private boolean learn = true;
    private boolean showSuggestions = true;
    private int selStart = -1, selEnd = -1;

    /** Japanese: the romaji typed so far; shown as hiragana, katakana or latin. */
    private final StringBuilder romaji = new StringBuilder();
    private int jaMode = HIRA;

    private final String[] suggestionWords = new String[3];

    // clipboard search
    private boolean searching;
    private final StringBuilder searchQuery = new StringBuilder();
    private ClipStore.Clip quickPasteDone;

    private final ClipboardManager.OnPrimaryClipChangedListener clipListener =
            new ClipboardManager.OnPrimaryClipChangedListener() {
                @Override
                public void onPrimaryClipChanged() {
                    captureClipboard();
                }
            };

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        words = new WordStore(new File(getFilesDir(), "words.tsv"));
        clips = new ClipStore(new File(getFilesDir(), "clipboard.bin"));
        clips.setMax(prefs.clipMax);
        dictGeneration = prefs.dictGeneration;
        clipGeneration = prefs.clipGeneration;
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        clipboardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboardManager != null) clipboardManager.addPrimaryClipChangedListener(clipListener);
        lang = prefs.currentLang;
    }

    @Override
    public View onCreateInputView() {
        kv = new KeyboardView(this, this, prefs);
        ev = new EmojiView(this, this);
        cv = new ClipboardView(this, this, false);
        searchPane = new ClipboardView(this, this, true);
        cv.setStore(clips);
        searchPane.setStore(clips);

        FrameLayout stack = new FrameLayout(this);
        stack.addView(kv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        stack.addView(ev, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stack.addView(cv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ev.setVisibility(View.GONE);
        cv.setVisibility(View.GONE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int paneH = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 210,
                getResources().getDisplayMetrics()));
        root.addView(searchPane, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, paneH));
        root.addView(stack, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        searchPane.setVisibility(View.GONE);

        applyTheme();
        refreshLayout();
        return root;
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        prefs.reload();
        if (prefs.dictGeneration != dictGeneration) {
            dictGeneration = prefs.dictGeneration;
            words.clear();
        }
        if (prefs.clipGeneration != clipGeneration) {
            clipGeneration = prefs.clipGeneration;
            clips.clearUnpinned();
        }
        clips.setMax(prefs.clipMax);
        if (!prefs.hasLang(lang)) lang = prefs.currentLang;
        if (kv != null) kv.applyPrefs(prefs);
        applyTheme();

        int cls = info.inputType & InputType.TYPE_MASK_CLASS;
        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        boolean password = isPassword(cls, variation);
        boolean incognito = (info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0;
        boolean noSuggest = (info.inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0;

        learn = !password && !incognito;
        showSuggestions = prefs.suggestions && !password && !noSuggest
                && (cls == InputType.TYPE_CLASS_TEXT || cls == 0);

        boolean numeric = cls == InputType.TYPE_CLASS_NUMBER
                || cls == InputType.TYPE_CLASS_PHONE
                || cls == InputType.TYPE_CLASS_DATETIME;
        mode = numeric ? Layouts.SYM1 : Layouts.LETTERS;

        romaji.setLength(0);
        jaMode = HIRA;
        shift = KeyboardView.SHIFT_OFF;
        autoShifted = false;
        lastWasSpace = false;
        selStart = info.initialSelStart;
        selEnd = info.initialSelEnd;
        searching = false;
        searchQuery.setLength(0);

        if (ev != null) ev.hideNow();
        if (cv != null) cv.hideNow();
        if (searchPane != null) searchPane.setVisibility(View.GONE);
        if (cv != null) cv.setEnabledHistory(prefs.clipboard);
        if (kv != null) kv.cancelTouch();
        captureClipboard();
        refreshLayout();
        updateAutoShift();
        updateSuggestions();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        if (romaji.length() > 0) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.finishComposingText();
            romaji.setLength(0);
            jaMode = HIRA;
        }
        searching = false;
        if (kv != null) kv.cancelTouch();
        words.save();
    }

    @Override
    public void onDestroy() {
        if (clipboardManager != null) clipboardManager.removePrimaryClipChangedListener(clipListener);
        words.save();
        clips.flush();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (kv != null) kv.applyPrefs(prefs);
        applyTheme();
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
                                  int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
        boolean jumped = newSelStart != selStart || newSelEnd != selEnd;
        selStart = newSelStart;
        selEnd = newSelEnd;
        if (romaji.length() > 0 && (candidatesStart < 0 || newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            // The cursor left the Japanese composition (the user tapped elsewhere).
            romaji.setLength(0);
            jaMode = HIRA;
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.finishComposingText();
            updateKeyLabels();
        }
        if (jumped && newSelStart != oldSelStart + 1) lastWasSpace = false;
        updateAutoShift();
        updateSuggestions();
    }

    private static boolean isPassword(int cls, int variation) {
        if (cls == InputType.TYPE_CLASS_TEXT) {
            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
        }
        return cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }

    // ---------------------------------------------------------------- look

    private boolean systemDark() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private void applyTheme() {
        Dialog d = getWindow();
        Window w = d == null ? null : d.getWindow();

        // Real blur of the app behind the keyboard (Android 12+, if the phone supports it).
        translucent = false;
        if (w != null && Build.VERSION.SDK_INT >= 31) {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            boolean can = wm != null && wm.isCrossWindowBlurEnabled();
            boolean want = prefs.blur && can && Prefs.STYLE_GLASS.equals(prefs.style);
            if (want) {
                w.setFormat(PixelFormat.TRANSLUCENT);
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                w.setBackgroundBlurRadius(Math.round(TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, 28, getResources().getDisplayMetrics())));
                translucent = true;
            } else {
                w.setBackgroundBlurRadius(0);
            }
        }

        boolean sysDark = systemDark();
        theme = Theme.from(prefs, sysDark, translucent);
        if (kv != null) kv.setTheme(theme);
        if (ev != null) ev.setTheme(theme);
        if (cv != null) cv.setTheme(theme);
        if (searchPane != null) searchPane.setTheme(theme);

        if (w == null) return;
        w.setNavigationBarColor(translucent ? Color.TRANSPARENT : theme.solidBg());
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.setSystemBarsAppearance(theme.dark ? 0 : WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            View decor = w.getDecorView();
            int f = decor.getSystemUiVisibility();
            f = theme.dark ? (f & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) : (f | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            decor.setSystemUiVisibility(f);
        }
    }

    private Locale locale() {
        return Layouts.locale(lang);
    }

    private boolean isJa() {
        return "ja".equals(lang);
    }

    private void refreshLayout() {
        if (kv == null) return;
        kv.setLocale(locale());
        kv.setLayout(Layouts.get(lang, mode, prefs.numberRow, !prefs.globeRow), mode == Layouts.LETTERS);
        kv.setShift(shift);
        updateKeyLabels();
    }

    private void updateKeyLabels() {
        if (kv == null) return;
        if (searching) {
            kv.setSpaceLabel(Layouts.space(lang));
            kv.setReturn(Layouts.returnLabel(lang, Layouts.SEARCH), true);
            return;
        }
        if (romaji.length() > 0) {
            String next = jaMode == HIRA ? "カナ" : jaMode == KATA ? "ABC" : "かな";
            kv.setSpaceLabel(next);
            kv.setReturn(Layouts.returnLabel(lang, Layouts.CONFIRM), true);
            return;
        }
        kv.setSpaceLabel(Layouts.space(lang));
        EditorInfo ei = getCurrentInputEditorInfo();
        int kind = Layouts.RET;
        if (ei != null && (ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0) {
            switch (ei.imeOptions & EditorInfo.IME_MASK_ACTION) {
                case EditorInfo.IME_ACTION_SEARCH: kind = Layouts.SEARCH; break;
                case EditorInfo.IME_ACTION_SEND: kind = Layouts.SEND; break;
                case EditorInfo.IME_ACTION_GO: kind = Layouts.GO; break;
                case EditorInfo.IME_ACTION_DONE: kind = Layouts.DONE; break;
                case EditorInfo.IME_ACTION_NEXT: kind = Layouts.NEXT; break;
                default: break;
            }
        }
        boolean accent = kind == Layouts.SEARCH || kind == Layouts.SEND || kind == Layouts.GO || kind == Layouts.DONE;
        kv.setReturn(Layouts.returnLabel(lang, kind), accent);
    }

    // ---------------------------------------------------------------- feedback

    @Override
    public void onFeedback(int keyType) {
        int h = prefs.haptic;
        if (h > 0 && vibrator != null && vibrator.hasVibrator()) {
            try {
                long ms = h == 1 ? 8 : h == 2 ? 12 : 18;
                int amp = h == 1 ? 60 : h == 2 ? 130 : 230;
                vibrator.vibrate(VibrationEffect.createOneShot(ms,
                        vibrator.hasAmplitudeControl() ? amp : VibrationEffect.DEFAULT_AMPLITUDE));
            } catch (RuntimeException ignored) {
            }
        }
        if (prefs.sound && prefs.soundVolume > 0 && audio != null) {
            int fx;
            switch (keyType) {
                case Key.DELETE: fx = AudioManager.FX_KEYPRESS_DELETE; break;
                case Key.SPACE: fx = AudioManager.FX_KEYPRESS_SPACEBAR; break;
                case Key.RETURN: fx = AudioManager.FX_KEYPRESS_RETURN; break;
                default: fx = AudioManager.FX_KEYPRESS_STANDARD; break;
            }
            audio.playSoundEffect(fx, prefs.soundVolume / 100f);
        }
    }

    // ---------------------------------------------------------------- typing

    private boolean composesRomaji(String s) {
        if (!isJa() || s.length() != 1) return false;
        char c = s.charAt(0);
        if (mode == Layouts.LETTERS && ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '\'')) return true;
        return (c == '-' || c == 'ー') && (mode == Layouts.LETTERS || romaji.length() > 0);
    }

    @Override
    public void onText(String s) {
        if (searching) {
            searchQuery.append(s);
            searchPane.setQuery(searchQuery.toString());
            afterChar();
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (composesRomaji(s)) {
            romaji.append(s.equals("ー") ? "-" : s);
            updateComposing(ic);
            afterChar();
            return;
        }
        if (romaji.length() > 0) commitComposition(ic);
        if (!isWordChar(s)) learnCurrentWord(ic);
        ic.commitText(s, 1);
        afterChar();
    }

    private void afterChar() {
        lastWasSpace = false;
        if (shift == KeyboardView.SHIFT_ON) {
            shift = KeyboardView.SHIFT_OFF;
            autoShifted = false;
            if (kv != null) kv.setShift(shift);
        }
    }

    private static boolean isWordChar(String s) {
        if (s.isEmpty()) return false;
        int cp = s.codePointAt(0);
        return Character.isLetterOrDigit(cp) || cp == '\'' || cp == '’';
    }

    private String composingDisplay(boolean fin) {
        switch (jaMode) {
            case KATA: return Romaji.toKatakana(Romaji.toHiragana(romaji, fin));
            case LATIN: return romaji.toString();
            default: return Romaji.toHiragana(romaji, fin);
        }
    }

    private void updateComposing(InputConnection ic) {
        ic.setComposingText(composingDisplay(false), 1);
        updateKeyLabels();
        updateSuggestions();
    }

    private void commitComposition(InputConnection ic) {
        ic.commitText(composingDisplay(true), 1);
        romaji.setLength(0);
        jaMode = HIRA;
        updateKeyLabels();
    }

    @Override
    public void onSpace() {
        if (searching) {
            onText(" ");
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (romaji.length() > 0) {
            // Space switches the composition between hiragana, katakana and latin.
            jaMode = (jaMode + 1) % 3;
            updateComposing(ic);
            return;
        }
        if (lastWasSpace && prefs.doubleSpace && !isJa()) {
            CharSequence before = ic.getTextBeforeCursor(2, 0);
            if (before != null && before.length() == 2 && before.charAt(1) == ' '
                    && Character.isLetterOrDigit(before.charAt(0))) {
                ic.beginBatchEdit();
                ic.deleteSurroundingText(1, 0);
                ic.commitText(". ", 1);
                ic.endBatchEdit();
                lastWasSpace = false;
                return;
            }
        }
        learnCurrentWord(ic);
        ic.commitText(" ", 1);
        afterChar();
        lastWasSpace = true;
    }

    @Override
    public void onReturn() {
        if (searching) {
            onClipSearchDone();
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (romaji.length() > 0) {
            commitComposition(ic);
            updateSuggestions();
            return;
        }
        learnCurrentWord(ic);
        lastWasSpace = false;
        // Performs the field's action (search, send…) or types a new line.
        sendKeyChar('\n');
    }

    @Override
    public void onDelete(boolean word) {
        if (searching) {
            if (searchQuery.length() > 0) {
                searchQuery.setLength(searchQuery.length() - 1);
                searchPane.setQuery(searchQuery.toString());
            }
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        lastWasSpace = false;
        if (romaji.length() > 0) {
            romaji.setLength(romaji.length() - 1);
            if (romaji.length() == 0) {
                ic.commitText("", 1);
                jaMode = HIRA;
                updateKeyLabels();
                updateSuggestions();
            } else {
                updateComposing(ic);
            }
            return;
        }
        if (selStart >= 0 && selEnd > selStart) {
            ic.commitText("", 1);
            selEnd = selStart;
            return;
        }
        if (word) {
            int n = wordDeleteLength(ic.getTextBeforeCursor(64, 0));
            if (n > 0) {
                ic.deleteSurroundingText(n, 0);
                return;
            }
        }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
    }

    /** How many chars to remove to delete the previous word (plus the spaces after it). */
    static int wordDeleteLength(CharSequence b) {
        if (b == null) return 0;
        int len = b.length();
        int i = len;
        while (i > 0 && Character.isWhitespace(b.charAt(i - 1))) i--;
        if (i > 0 && !Character.isLetterOrDigit(b.charAt(i - 1))) {
            i--;
            if (i > 0 && Character.isLowSurrogate(b.charAt(i)) && Character.isHighSurrogate(b.charAt(i - 1))) i--;
        } else {
            while (i > 0 && Character.isLetterOrDigit(b.charAt(i - 1))) i--;
        }
        return len - i;
    }

    @Override
    public void onShift() {
        long now = SystemClock.uptimeMillis();
        if (shift == KeyboardView.SHIFT_LOCK) {
            shift = KeyboardView.SHIFT_OFF;
            lastShiftTap = 0;
        } else if (now - lastShiftTap < 330) {
            shift = KeyboardView.SHIFT_LOCK; // double tap = Caps Lock
            lastShiftTap = 0;
        } else {
            shift = shift == KeyboardView.SHIFT_OFF ? KeyboardView.SHIFT_ON : KeyboardView.SHIFT_OFF;
            lastShiftTap = now;
        }
        autoShifted = false;
        if (kv != null) kv.setShift(shift);
    }

    private void updateAutoShift() {
        if (kv == null || shift == KeyboardView.SHIFT_LOCK) return;
        boolean want = false;
        if (prefs.autoCap && mode == Layouts.LETTERS && !isJa() && romaji.length() == 0 && !searching) {
            InputConnection ic = getCurrentInputConnection();
            EditorInfo ei = getCurrentInputEditorInfo();
            if (ic != null && ei != null && ei.inputType != 0) {
                want = ic.getCursorCapsMode(ei.inputType) != 0;
            }
        }
        if (want && shift == KeyboardView.SHIFT_OFF) {
            shift = KeyboardView.SHIFT_ON;
            autoShifted = true;
        } else if (!want && autoShifted && shift == KeyboardView.SHIFT_ON) {
            shift = KeyboardView.SHIFT_OFF;
            autoShifted = false;
        }
        kv.setShift(shift);
    }

    @Override
    public void onModeKey() {
        mode = mode == Layouts.LETTERS ? Layouts.SYM1 : Layouts.LETTERS;
        refreshLayout();
        updateAutoShift();
    }

    @Override
    public void onMoreKey() {
        mode = mode == Layouts.SYM1 ? Layouts.SYM2 : Layouts.SYM1;
        refreshLayout();
    }

    @Override
    public void onSlideReturn() {
        mode = Layouts.LETTERS;
        refreshLayout();
        updateAutoShift();
    }

    @Override
    public void onCursor(int dx, int dy) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || searching) return;
        if (romaji.length() > 0) commitComposition(ic);
        int steps = Math.min(Math.abs(dx), 20);
        for (int i = 0; i < steps; i++) {
            sendDownUpKeyEvents(dx > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT);
        }
        steps = Math.min(Math.abs(dy), 10);
        for (int i = 0; i < steps; i++) {
            sendDownUpKeyEvents(dy > 0 ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP);
        }
    }

    // ---------------------------------------------------------------- language & panels

    @Override
    public void onGlobe() {
        String[] langs = prefs.langs;
        if (langs.length <= 1) {
            onGlobeLong();
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic != null && romaji.length() > 0) commitComposition(ic);
        int idx = 0;
        for (int i = 0; i < langs.length; i++) if (langs[i].equals(lang)) idx = i;
        lang = langs[(idx + 1) % langs.length];
        prefs.setCurrentLang(lang);
        mode = Layouts.LETTERS;
        if (shift != KeyboardView.SHIFT_LOCK) shift = KeyboardView.SHIFT_OFF;
        autoShifted = false;
        refreshLayout();
        updateAutoShift();
        updateSuggestions();
        if (kv != null) kv.flashLanguage(Layouts.name(lang));
    }

    @Override
    public void onGlobeLong() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showInputMethodPicker();
    }

    @Override
    public void onEmoji() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null && romaji.length() > 0) commitComposition(ic);
        if (ev == null) return;
        String[][] cats = EmojiData.categories();
        String[] titles = new String[cats.length + 1];
        String[] icons = new String[cats.length + 1];
        String[][] items = new String[cats.length + 1][];
        titles[0] = EmojiData.RECENT_TITLE;
        icons[0] = EmojiData.RECENT_ICON;
        List<String> recent = prefs.recentEmoji();
        items[0] = EmojiData.filter(recent);
        for (int i = 0; i < cats.length; i++) {
            titles[i + 1] = EmojiData.TITLES[i];
            icons[i + 1] = EmojiData.ICONS[i];
            items[i + 1] = cats[i];
        }
        ev.setAbcLabel(Layouts.abc(lang));
        ev.setData(titles, icons, items);
        ev.show();
    }

    @Override
    public void onEmojiPicked(String emoji) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.commitText(emoji, 1);
        prefs.recordEmoji(emoji);
        lastWasSpace = false;
    }

    @Override
    public void onEmojiDelete() {
        onDelete(false);
    }

    @Override
    public void onEmojiClose() {
        if (ev != null) ev.hide();
        updateAutoShift();
    }

    @Override
    public void onSettings() {
        Intent i = new Intent(this, SettingsActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
    }

    // ---------------------------------------------------------------- clipboard

    private void captureClipboard() {
        if (!prefs.clipboard || clipboardManager == null) return;
        ClipData cd;
        try {
            cd = clipboardManager.getPrimaryClip();
        } catch (RuntimeException e) {
            return; // not allowed right now (keyboard not in focus)
        }
        if (cd == null || cd.getItemCount() == 0) return;
        ClipDescription desc = cd.getDescription();
        if (desc != null) {
            PersistableBundle extras = desc.getExtras();
            // Apps mark passwords and codes as sensitive: never keep those.
            if (extras != null && extras.getBoolean("android.content.extra.IS_SENSITIVE", false)) return;
        }
        CharSequence t = cd.getItemAt(0).getText();
        if (t == null) return;
        if (clips.add(t.toString())) {
            quickPasteDone = null;
            updateQuickClip();
            if (cv != null) cv.invalidate();
        }
    }

    private void updateQuickClip() {
        if (kv == null) return;
        ClipStore.Clip c = clips.latest();
        boolean fresh = c != null && c != quickPasteDone && prefs.clipboard
                && System.currentTimeMillis() - c.time < QUICK_PASTE_MS;
        kv.setQuickClip(fresh ? c.text : null);
    }

    @Override
    public void onQuickPaste() {
        ClipStore.Clip c = clips.latest();
        if (c == null) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (romaji.length() > 0) commitComposition(ic);
        ic.commitText(c.text, 1);
        quickPasteDone = c;
        updateQuickClip();
    }

    @Override
    public void onClipboard() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null && romaji.length() > 0) commitComposition(ic);
        captureClipboard();
        if (cv == null) return;
        cv.setAbcLabel(Layouts.abc(lang));
        cv.setEnabledHistory(prefs.clipboard);
        cv.show();
    }

    @Override
    public void onClipPaste(ClipStore.Clip clip) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.commitText(clip.text, 1);
        lastWasSpace = false;
        if (searching) onClipSearchDone();
    }

    @Override
    public void onClipClose() {
        if (cv != null) cv.hide();
        updateAutoShift();
    }

    @Override
    public void onClipSearch() {
        searching = true;
        searchQuery.setLength(0);
        searchPane.setQuery("");
        searchPane.setVisibility(View.VISIBLE);
        cv.hideNow();
        mode = Layouts.LETTERS;
        refreshLayout();
        updateAutoShift();
        updateSuggestions();
    }

    @Override
    public void onClipSearchDone() {
        searching = false;
        searchPane.setVisibility(View.GONE);
        updateKeyLabels();
        updateSuggestions();
        updateAutoShift();
        cv.show();
    }

    // ---------------------------------------------------------------- suggestions

    /** Letters right before the cursor. */
    private String currentWord(InputConnection ic) {
        CharSequence b = ic.getTextBeforeCursor(48, 0);
        if (b == null) return "";
        int i = b.length();
        while (i > 0) {
            char c = b.charAt(i - 1);
            if (Character.isLetter(c) || ((c == '\'' || c == '’') && i < b.length())) i--;
            else break;
        }
        // No leading apostrophe
        while (i < b.length() && (b.charAt(i) == '\'' || b.charAt(i) == '’')) i++;
        return b.subSequence(i, b.length()).toString();
    }

    private void learnCurrentWord(InputConnection ic) {
        if (!learn) return;
        String w = currentWord(ic);
        if (w.length() >= 2 && w.length() <= 30) words.learn(w, locale());
    }

    private void updateSuggestions() {
        if (kv == null) return;
        String[] shown = new String[3];
        for (int i = 0; i < 3; i++) suggestionWords[i] = null;
        InputConnection ic = getCurrentInputConnection();
        if (searching) {
            kv.setSuggestions(shown);
            return;
        }
        if (romaji.length() > 0) {
            String h = Romaji.toHiragana(romaji, true);
            suggestionWords[0] = h;
            suggestionWords[1] = Romaji.toKatakana(h);
            suggestionWords[2] = romaji.toString();
        } else if (showSuggestions && ic != null && (selStart < 0 || selStart == selEnd)) {
            String p = currentWord(ic);
            if (!p.isEmpty()) {
                List<String> s = words.suggest(p, locale(), 2);
                if (!s.isEmpty()) {
                    suggestionWords[0] = p;
                    suggestionWords[1] = matchCase(s.get(0), p);
                    if (s.size() > 1) suggestionWords[2] = matchCase(s.get(1), p);
                }
            }
        }
        for (int i = 0; i < 3; i++) shown[i] = suggestionWords[i];
        if (romaji.length() == 0 && shown[0] != null) shown[0] = "«" + shown[0] + "»";
        kv.setSuggestions(shown);
        updateQuickClip();
    }

    private String matchCase(String w, String prefix) {
        Locale l = locale();
        if (prefix.length() > 1 && prefix.equals(prefix.toUpperCase(l))) return w.toUpperCase(l);
        if (Character.isUpperCase(prefix.charAt(0))) {
            return w.substring(0, 1).toUpperCase(l) + w.substring(1);
        }
        return w;
    }

    @Override
    public void onSuggestion(int index) {
        String w = suggestionWords[index];
        InputConnection ic = getCurrentInputConnection();
        if (w == null || ic == null) return;
        if (romaji.length() > 0) {
            ic.commitText(w, 1);
            romaji.setLength(0);
            jaMode = HIRA;
            updateKeyLabels();
            updateSuggestions();
            return;
        }
        String prefix = currentWord(ic);
        ic.beginBatchEdit();
        if (!prefix.isEmpty()) ic.deleteSurroundingText(prefix.length(), 0);
        ic.commitText(w + " ", 1);
        ic.endBatchEdit();
        if (learn) words.learn(w, locale());
        afterChar();
        lastWasSpace = true;
    }
}
