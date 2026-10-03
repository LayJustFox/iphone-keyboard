package com.iphonekb;

import android.app.Dialog;
import android.content.res.Configuration;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;

import java.io.File;
import java.util.List;
import java.util.Locale;

public final class KeyboardService extends InputMethodService
        implements KeyboardView.Listener, EmojiView.Listener {

    private static final int HIRA = 0, KATA = 1, LATIN = 2;

    private Prefs prefs;
    private WordStore words;
    private int dictGeneration;
    private KeyboardView kv;
    private EmojiView ev;
    private Theme theme;
    private Vibrator vibrator;
    private AudioManager audio;

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

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        words = new WordStore(new File(getFilesDir(), "words.tsv"));
        dictGeneration = prefs.dictGeneration;
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        lang = prefs.currentLang;
    }

    @Override
    public View onCreateInputView() {
        kv = new KeyboardView(this, this);
        ev = new EmojiView(this, this);
        FrameLayout root = new FrameLayout(this);
        root.addView(kv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(ev, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ev.setVisibility(View.GONE);
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
        if (!prefs.hasLang(lang)) lang = prefs.currentLang;
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

        if (ev != null) ev.hideNow();
        if (kv != null) kv.cancelTouch();
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
        if (kv != null) kv.cancelTouch();
        words.save();
    }

    @Override
    public void onDestroy() {
        words.save();
        super.onDestroy();
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

    private void applyTheme() {
        boolean dark;
        if (Prefs.THEME_DARK.equals(prefs.theme)) dark = true;
        else if (Prefs.THEME_LIGHT.equals(prefs.theme)) dark = false;
        else dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        theme = dark ? Theme.dark() : Theme.light();
        if (kv != null) kv.setTheme(theme);
        if (ev != null) ev.setTheme(theme);

        Dialog d = getWindow();
        Window w = d == null ? null : d.getWindow();
        if (w == null) return;
        w.setNavigationBarColor(theme.bg);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.setSystemBarsAppearance(dark ? 0 : WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            View decor = w.getDecorView();
            int f = decor.getSystemUiVisibility();
            f = dark ? (f & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) : (f | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
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
        kv.setLayout(Layouts.get(lang, mode), mode == Layouts.LETTERS);
        kv.setShift(shift);
        updateKeyLabels();
    }

    private void updateKeyLabels() {
        if (kv == null) return;
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
        if (prefs.vibrate && vibrator != null && vibrator.hasVibrator()) {
            try {
                if (vibrator.hasAmplitudeControl()) {
                    vibrator.vibrate(VibrationEffect.createOneShot(10, 70));
                } else {
                    vibrator.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE));
                }
            } catch (RuntimeException ignored) {
            }
        }
        if (prefs.sound && audio != null) {
            int fx;
            switch (keyType) {
                case Key.DELETE: fx = AudioManager.FX_KEYPRESS_DELETE; break;
                case Key.SPACE: fx = AudioManager.FX_KEYPRESS_SPACEBAR; break;
                case Key.RETURN: fx = AudioManager.FX_KEYPRESS_RETURN; break;
                default: fx = AudioManager.FX_KEYPRESS_STANDARD; break;
            }
            audio.playSoundEffect(fx, -1f);
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
        if (prefs.autoCap && mode == Layouts.LETTERS && !isJa() && romaji.length() == 0) {
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
        if (ic == null) return;
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
