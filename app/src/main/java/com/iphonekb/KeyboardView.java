package com.iphonekb;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * Draws the whole keyboard (suggestion bar / toolbar, keys, globe strip) and handles touches.
 *
 * Animations: every animated value eases toward a target once per display frame
 * (exponential smoothing on real frame time), so motion stays smooth at 60, 90 or 120 Hz
 * and never "jumps" when a new target arrives mid-animation.
 */
@SuppressLint("ViewConstructor")
final class KeyboardView extends View {

    interface Listener {
        void onFeedback(int keyType);
        void onText(String text);
        void onShift();
        void onDelete(boolean word);
        void onModeKey();
        void onMoreKey();
        void onSlideReturn();
        void onSpace();
        void onReturn();
        void onEmoji();
        void onGlobe();
        void onGlobeLong();
        void onCursor(int dx, int dy);
        void onSuggestion(int index);
        void onClipboard();
        void onSettings();
        void onQuickPaste();
    }

    static final int SHIFT_OFF = 0, SHIFT_ON = 1, SHIFT_LOCK = 2;

    private static final int NONE = 0, KEY = 1, STRIP = 2;
    private static final int TOOL_CLIP = 10, TOOL_SETTINGS = 11, TOOL_PASTE = 12;

    private final Listener listener;
    private final float density;
    private Prefs prefs;
    private Metrics m;
    private Theme theme = Theme.light();
    private Layout layout;
    private int measureRows = 4;
    private Locale locale = Locale.US;
    private int shiftState = SHIFT_OFF;
    private boolean lettersMode = true;

    private String spaceLabel = "space";
    private String returnLabel = "return";
    private CharSequence spaceShown = "", returnShown = "";
    private String flashLabel;
    private CharSequence flashShown = "";

    private final String[] suggestions = new String[3];
    private final CharSequence[] suggestionsShown = new CharSequence[3];
    private String quickClip;
    private CharSequence quickClipShown;

    private float keysTop, globeTop;
    // Navigation bar under the keyboard (the keyboard now draws behind it, like iOS).
    private int navInset;
    private float bottomArea;   // everything below the keys: globe strip and/or nav bar
    private float globeZone;    // part of bottomArea where the 🌐 sits
    private final Key globeKey = new Key(Key.GLOBE, "");

    // ---- paints & reusable objects (nothing is allocated while drawing)
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path clip = new Path();
    private final RectF tmp = new RectF();
    private final RectF bubble = new RectF();
    private final Matrix matrix = new Matrix();
    private LinearGradient keyShader, specShader, rimShader, bubbleShader, panelShader;
    private RadialGradient glowShader;

    // ---- animation state
    private static final class Anim {
        float v, target;

        Anim(float v) {
            this.v = v;
            this.target = v;
        }
    }

    private final Anim caseT = new Anim(0);      // 1 = capitals shown
    private final Anim labelsT = new Anim(1);    // 0 while the space bar is a trackpad
    private final Anim flashT = new Anim(0);     // language name on the space bar
    private final Anim bubbleT = new Anim(0);    // letter bubble
    private final Anim altsT = new Anim(0);      // long-press variants panel
    private final Anim layoutT = new Anim(1);    // labels fading in after 123 / ABC
    private final Anim accentT = new Anim(0);    // blue return key
    private final Anim shiftT = new Anim(0);     // white shift key
    private final Anim stripT = new Anim(0);     // 1 = suggestions, 0 = toolbar
    private long lastFrame;
    private boolean frameActive;

    // ---- touch state
    private int pointerId = -1;
    private int touchKind = NONE;
    private Key downKey;
    private Key bubbleKey;
    private float downX;
    private float lastX, lastY;
    private int pressedStrip = -1;
    private boolean modeSlide, movedOff, longFired;

    private boolean altsShown;
    private Key altsKey;
    private String[] alts;
    private final RectF altsRect = new RectF();
    private float altCellW, altPad;
    private int altSel;

    private boolean trackpad;
    private float accX, accY;

    private int deleteCount;
    private long deleteStart;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            onLongPress();
        }
    };

    private long bubbleShownAt;
    private final Runnable hideBubble = new Runnable() {
        @Override
        public void run() {
            bubbleT.target = 0;
            if (!animOn()) bubbleT.v = 0;
            kick();
        }
    };

    private final Runnable flashOut = new Runnable() {
        @Override
        public void run() {
            flashT.target = 0;
            kick();
        }
    };

    private final Runnable repeatDelete = new Runnable() {
        @Override
        public void run() {
            if (downKey == null || downKey.type != Key.DELETE) return;
            deleteCount++;
            long held = SystemClock.uptimeMillis() - deleteStart;
            // Like iOS: letters first, faster after a while, then whole words.
            boolean word = held > 2200;
            listener.onDelete(word);
            listener.onFeedback(Key.DELETE);
            long delay = word ? 220 : (deleteCount > 10 ? 45 : 95);
            handler.postDelayed(this, delay);
        }
    };

    KeyboardView(Context context, Listener listener, Prefs prefs) {
        super(context);
        this.listener = listener;
        this.prefs = prefs;
        this.density = context.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        rim.setStyle(Paint.Style.STROKE);
        text.setTextAlign(Paint.Align.CENTER);
        glowShader = new RadialGradient(0, 0, 1, 0x73FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        glowPaint.setShader(glowShader);
        computeMetrics();
        buildShaders();
    }

    private float dp(float v) {
        return v * density;
    }

    // ---------------------------------------------------------------- state from the service

    /** Re-reads every size / look setting (live preview in the settings uses this too). */
    void applyPrefs(Prefs p) {
        prefs = p;
        computeMetrics();
        requestLayout();
        layoutKeys();
        invalidate();
    }

    void setTheme(Theme t) {
        theme = t;
        buildShaders();
        invalidate();
    }

    void setLayout(Layout l, boolean letters) {
        boolean changed = layout != null && l != layout;
        layout = l;
        lettersMode = letters;
        for (Key[] row : l.rows) for (Key k : row) k.upper = k.label.toUpperCase(locale);
        layoutKeys();
        if (changed && animOn()) {
            layoutT.v = 0.25f;
            layoutT.target = 1;
        }
        caseT.target = upper() ? 1 : 0;
        caseT.v = caseT.target;
        kick();
    }

    void setLocale(Locale l) {
        locale = l;
        if (layout != null) for (Key[] row : layout.rows) for (Key k : row) k.upper = k.label.toUpperCase(l);
        invalidate();
    }

    void setShift(int s) {
        if (shiftState != s) {
            shiftState = s;
            caseT.target = upper() ? 1 : 0;
            shiftT.target = s != SHIFT_OFF ? 1 : 0;
            kick();
        }
    }

    void setSpaceLabel(String s) {
        if (!s.equals(spaceLabel)) {
            spaceLabel = s;
            refreshLabels();
            invalidate();
        }
    }

    void setReturn(String label, boolean accent) {
        returnLabel = label;
        accentT.target = accent ? 1 : 0;
        if (layout == null || !animOn()) accentT.v = accentT.target;
        refreshLabels();
        kick();
    }

    void setSuggestions(String[] s) {
        boolean any = false;
        for (int i = 0; i < 3; i++) {
            suggestions[i] = s != null && i < s.length ? s[i] : null;
            any |= suggestions[i] != null;
        }
        stripT.target = any ? 1 : 0;
        refreshStrip();
        kick();
    }

    /** Latest clipboard text offered for one-tap paste in the toolbar (null = none). */
    void setQuickClip(String s) {
        quickClip = s;
        refreshStrip();
        invalidate();
    }

    /** The language name lights up on the space bar, then fades back to "space". */
    void flashLanguage(String name) {
        flashLabel = name;
        refreshLabels();
        flashT.v = animOn() ? 0f : 1f;
        flashT.target = 1f;
        handler.removeCallbacks(flashOut);
        handler.postDelayed(flashOut, 950);
        kick();
    }

    void cancelTouch() {
        endTouch();
    }

    private boolean animOn() {
        return prefs.animSpeed != Prefs.ANIM_OFF;
    }

    private boolean upper() {
        return lettersMode && shiftState != SHIFT_OFF;
    }

    // ---------------------------------------------------------------- animation engine

    private void kick() {
        invalidate();
    }

    /** Eases every value toward its target. Returns true while anything is still moving. */
    private boolean step(float dt) {
        if (!animOn()) {
            snapAll();
            return false;
        }
        float base = prefs.animSpeed == Prefs.ANIM_FAST ? 0.55f : 1f;
        boolean moving = false;
        moving |= ease(caseT, dt, 55 * base);
        moving |= ease(labelsT, dt, 70 * base);
        moving |= ease(flashT, dt, (flashT.target > flashT.v ? 60 : 160) * base);
        moving |= ease(bubbleT, dt, 26 * base);
        moving |= ease(altsT, dt, 45 * base);
        moving |= ease(layoutT, dt, 40 * base);
        moving |= ease(accentT, dt, 70 * base);
        moving |= ease(shiftT, dt, 45 * base);
        moving |= ease(stripT, dt, 70 * base);
        if (layout != null) {
            for (Key[] row : layout.rows) {
                for (Key k : row) moving |= easeKey(k, dt, base);
            }
        }
        moving |= easeKey(globeKey, dt, base);
        return moving;
    }

    private static boolean ease(Anim a, float dt, float tau) {
        if (a.v == a.target) return false;
        float k = 1f - (float) Math.exp(-dt / tau);
        a.v += (a.target - a.v) * k;
        if (Math.abs(a.target - a.v) < 0.002f) {
            a.v = a.target;
            return false;
        }
        return true;
    }

    private static boolean easeKey(Key k, float dt, float base) {
        if (k.press == k.pressTarget) return false;
        // Presses light up almost instantly; releases fade out gently.
        float tau = (k.pressTarget > k.press ? 16 : 90) * base;
        float f = 1f - (float) Math.exp(-dt / tau);
        k.press += (k.pressTarget - k.press) * f;
        if (Math.abs(k.pressTarget - k.press) < 0.003f) {
            k.press = k.pressTarget;
            return false;
        }
        return true;
    }

    private void snapAll() {
        Anim[] all = {caseT, labelsT, flashT, bubbleT, altsT, layoutT, accentT, shiftT, stripT};
        for (Anim a : all) a.v = a.target;
        if (layout != null) for (Key[] row : layout.rows) for (Key k : row) k.press = k.pressTarget;
        globeKey.press = globeKey.pressTarget;
    }

    private void setPressed(Key k, boolean on, float x, float y) {
        if (k == null) return;
        k.pressTarget = on ? 1f : 0f;
        if (on) {
            k.touchX = x;
            k.touchY = y;
        }
    }

    // ---------------------------------------------------------------- metrics & layout

    private void computeMetrics() {
        measureRows = prefs.numberRow ? 5 : 4;
        m = Metrics.compute(prefs, getResources(), measureRows);
        keysTop = m.keysTop();
        float nav = navInset;
        boolean gesture = nav > 0 && nav <= dp(32);
        if (m.globeH > 0) {
            if (gesture) {
                // iPhone style: 🌐 sits beside the gesture bar instead of in its own extra strip.
                bottomArea = Math.max(m.globeH, nav + dp(30));
                globeZone = bottomArea - nav;
            } else {
                bottomArea = m.globeH + nav; // 3-button navigation: strip above the buttons
                globeZone = m.globeH;
            }
        } else {
            bottomArea = nav;
            globeZone = 0;
        }
    }

    /** Height of the system navigation bar drawn over the bottom of the keyboard. */
    void setNavInset(int px) {
        if (px != navInset) {
            navInset = px;
            computeMetrics();
            requestLayout();
            layoutKeys();
            invalidate();
        }
    }

    private float totalHeight() {
        return keysTop + measureRows * m.keyH + (measureRows - 1) * m.vGap + m.bottomPad + bottomArea;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        computeMetrics();
        int w = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(w, (int) Math.ceil(totalHeight()));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutKeys();
        buildShaders();
    }

    private void layoutKeys() {
        if (layout == null || getWidth() == 0 || m == null) return;
        float W = getWidth();
        int cols = layout.cols;
        float unit = (W - 2 * m.side - (cols - 1) * m.hGap) / cols;
        Key[][] rows = layout.rows;
        int n = rows.length;
        // A page with fewer rows than the tallest page gets taller keys, so the height never jumps.
        float area = measureRows * m.keyH + (measureRows - 1) * m.vGap;
        float kh = (area - (n - 1) * m.vGap) / n;
        globeTop = keysTop + area + m.bottomPad;
        float y = keysTop;
        for (int r = 0; r < n; r++) {
            Key[] row = rows[r];
            boolean last = r == n - 1;
            if (last) layoutBottom(row, y, W, kh);
            else layoutRow(row, y, W, unit, kh);

            float top = r == 0 ? m.stripH : y - m.vGap / 2;
            float bottom = last ? (globeZone > 0 ? globeTop : getHeight()) : y + kh + m.vGap / 2;
            for (int i = 0; i < row.length; i++) {
                Key k = row[i];
                float left = i == 0 ? 0 : (row[i - 1].rect.right + k.rect.left) / 2;
                float right = i == row.length - 1 ? W : (k.rect.right + row[i + 1].rect.left) / 2;
                k.hit.set(left, top, right, bottom);
            }
            y += kh + m.vGap;
        }
        float gw = Math.min(dp(64), W / 5);
        globeKey.rect.set(dp(8), globeTop, dp(8) + gw - dp(16), globeTop + globeZone);
        globeKey.hit.set(0, globeTop, gw, globeTop + globeZone);
        refreshLabels();
        refreshStrip();
    }

    private void layoutRow(Key[] row, float y, float W, float unit, float kh) {
        int n = row.length;
        float charsW = 0;
        int nc = 0;
        for (Key k : row) {
            if (k.type == Key.CHAR) {
                charsW += unit * k.width;
                nc++;
            }
        }
        if (nc > 1) charsW += (nc - 1) * m.hGap;
        float x = (W - charsW) / 2;
        for (Key k : row) {
            if (k.type != Key.CHAR) continue;
            k.rect.set(x, y, x + unit * k.width, y + kh);
            x += unit * k.width + m.hGap;
        }
        float avail = (W - 2 * m.side - charsW) / 2 - m.hGap;
        float sw = Math.max(unit * 0.8f, Math.min(unit * 1.32f, avail));
        if (row[0].type != Key.CHAR) row[0].rect.set(m.side, y, m.side + sw, y + kh);
        if (n > 1 && row[n - 1].type != Key.CHAR) row[n - 1].rect.set(W - m.side - sw, y, W - m.side, y + kh);
    }

    private void layoutBottom(Key[] row, float y, float W, float kh) {
        float avail = W - 2 * m.side - (row.length - 1) * m.hGap;
        float fixed = 0;
        float[] w = new float[row.length];
        boolean five = row.length >= 5;
        for (int i = 0; i < row.length; i++) {
            switch (row[i].type) {
                case Key.MODE:
                case Key.EMOJI:
                case Key.GLOBE:
                    w[i] = avail * (five ? 0.105f : 0.118f);
                    break;
                case Key.RETURN:
                    w[i] = avail * (five ? 0.22f : 0.25f);
                    break;
                default:
                    w[i] = -1;
            }
            if (w[i] > 0) fixed += w[i];
        }
        float x = m.side;
        for (int i = 0; i < row.length; i++) {
            float kw = w[i] > 0 ? w[i] : avail - fixed;
            row[i].rect.set(x, y, x + kw, y + kh);
            x += kw + m.hGap;
        }
    }

    private Key spaceKey() {
        if (layout == null) return null;
        for (Key k : layout.rows[layout.rows.length - 1]) if (k.type == Key.SPACE) return k;
        return null;
    }

    private Key returnKey() {
        if (layout == null) return null;
        for (Key k : layout.rows[layout.rows.length - 1]) if (k.type == Key.RETURN) return k;
        return null;
    }

    /** Pre-shortens labels so drawing never allocates. */
    private void refreshLabels() {
        if (m == null) return;
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(m.labelSize);
        Key s = spaceKey(), r = returnKey();
        float sw = s != null ? s.rect.width() - dp(8) : dp(120);
        float rw = r != null ? r.rect.width() - dp(6) : dp(80);
        spaceShown = TextUtils.ellipsize(spaceLabel, text, Math.max(sw, 1), TextUtils.TruncateAt.END);
        returnShown = TextUtils.ellipsize(returnLabel, text, Math.max(rw, 1), TextUtils.TruncateAt.END);
        flashShown = flashLabel == null ? "" :
                TextUtils.ellipsize(flashLabel, text, Math.max(sw, 1), TextUtils.TruncateAt.END);
    }

    private void refreshStrip() {
        if (getWidth() == 0 || m == null) return;
        float cw = getWidth() / 3f;
        text.setTextSize(dp(16.5f));
        for (int i = 0; i < 3; i++) {
            text.setTypeface(i == 1 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            suggestionsShown[i] = suggestions[i] == null ? null :
                    TextUtils.ellipsize(suggestions[i], text, cw - dp(14), TextUtils.TruncateAt.END);
        }
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(dp(14));
        if (quickClip != null) {
            String one = quickClip.replace('\n', ' ').trim();
            quickClipShown = TextUtils.ellipsize("📋 " + one, text, getWidth() - toolW() * 2 - dp(24),
                    TextUtils.TruncateAt.END);
        } else {
            quickClipShown = null;
        }
    }

    private float toolW() {
        return Math.min(m.stripH * 1.25f, dp(58));
    }

    private void buildShaders() {
        float h = 1f;
        keyShader = new LinearGradient(0, 0, 0, h,
                new int[]{theme.keyTop, theme.keyMid, theme.keyBottom}, new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP);
        specShader = new LinearGradient(0, 0, 0, h,
                new int[]{theme.specTop, theme.specMid, theme.specBottom}, new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP);
        rimShader = new LinearGradient(0, 0, 0, h, theme.rimTop, theme.rimBottom, Shader.TileMode.CLAMP);
        bubbleShader = new LinearGradient(0, 0, 0, h, theme.bubbleTop, theme.bubbleBottom, Shader.TileMode.CLAMP);
        panelShader = new LinearGradient(0, 0, 0, h, theme.bgTop, theme.bgBottom, Shader.TileMode.CLAMP);
        glowShader = new RadialGradient(0, 0, 1,
                (theme.dark ? 0x59 : 0x8C) << 24 | (theme.glow & 0xFFFFFF), theme.glow & 0xFFFFFF,
                Shader.TileMode.CLAMP);
        glowPaint.setShader(glowShader);
    }

    private Key findKey(float x, float y) {
        if (layout == null) return null;
        if (globeZone > 0 && y >= globeTop) return globeKey.hit.contains(x, y) ? globeKey : null;
        for (Key[] row : layout.rows) {
            for (Key k : row) {
                if (k.hit.contains(x, y)) return k;
            }
        }
        return null;
    }

    private int stripTarget(float x) {
        if (stripT.target > 0.5f) {
            int i = (int) (x / (getWidth() / 3f));
            if (i < 0 || i > 2 || suggestions[i] == null) return -1;
            return i;
        }
        float tw = toolW();
        if (x < tw) return TOOL_CLIP;
        if (x > getWidth() - tw) return TOOL_SETTINGS;
        if (quickClipShown != null) return TOOL_PASTE;
        return -1;
    }

    // ---------------------------------------------------------------- touch

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                begin(e.getPointerId(0), e.getX(), e.getY());
                return true;
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (trackpad || altsShown) return true;
                // Two-thumb typing: the first key is typed as soon as the next one is touched.
                finishPrevious();
                int i = e.getActionIndex();
                begin(e.getPointerId(i), e.getX(i), e.getY(i));
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                int i = e.findPointerIndex(pointerId);
                if (i >= 0) move(e.getX(i), e.getY(i));
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP: {
                int i = e.getActionIndex();
                if (e.getPointerId(i) == pointerId) release(e.getX(i), e.getY(i));
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                endTouch();
                return true;
            default:
                return true;
        }
    }

    private void finishPrevious() {
        if (touchKind == KEY && downKey != null && !longFired) {
            if (downKey.type == Key.CHAR) {
                commitChar(downKey);
                if (modeSlide && movedOff) listener.onSlideReturn();
            } else if (downKey.type == Key.SPACE) {
                listener.onSpace();
            }
        }
        endTouch();
    }

    private void begin(int id, float x, float y) {
        pointerId = id;
        lastX = x;
        lastY = y;
        downX = x;
        if (y < m.stripH) {
            touchKind = STRIP;
            pressedStrip = stripTarget(x);
            if (pressedStrip >= 0) listener.onFeedback(Key.CHAR);
            kick();
            return;
        }
        Key k = findKey(x, y);
        if (k == null) {
            touchKind = NONE;
            return;
        }
        touchKind = KEY;
        press(k, x, y);
    }

    private void press(Key k, float x, float y) {
        downKey = k;
        longFired = false;
        modeSlide = false;
        movedOff = false;
        listener.onFeedback(k.type);
        int lp = prefs.longPressMs;
        switch (k.type) {
            case Key.DELETE:
                deleteCount = 0;
                deleteStart = SystemClock.uptimeMillis();
                listener.onDelete(false);
                handler.postDelayed(repeatDelete, 450);
                break;
            case Key.SHIFT:
                listener.onShift();
                break;
            case Key.MODE:
                // The page switches on touch-down, so the finger can slide straight to a symbol.
                listener.onModeKey();
                modeSlide = true;
                downKey = findKey(x, y);
                k = downKey;
                break;
            case Key.MORE:
                listener.onMoreKey();
                downKey = findKey(x, y);
                k = downKey;
                break;
            case Key.CHAR:
                if (k.alts != null) handler.postDelayed(longPress, lp);
                showBubble(k);
                break;
            case Key.SPACE:
                if (prefs.trackpad) handler.postDelayed(longPress, lp + 40);
                break;
            case Key.GLOBE:
                handler.postDelayed(longPress, Math.max(lp, 450));
                break;
            default:
                break;
        }
        setPressed(k, true, x, y);
        kick();
    }

    private void showBubble(Key k) {
        if (!prefs.popups) return;
        // Like iOS: the big letter appears at once, at full size, and never animates in.
        handler.removeCallbacks(hideBubble);
        bubbleKey = k;
        bubbleT.v = 1;
        bubbleT.target = 1;
        bubbleShownAt = SystemClock.uptimeMillis();
    }

    /** Keeps the bubble readable for at least ~90 ms even on very quick taps. */
    private void releaseBubble() {
        handler.removeCallbacks(hideBubble);
        long wait = 90 - (SystemClock.uptimeMillis() - bubbleShownAt);
        if (wait <= 0) hideBubble.run();
        else handler.postDelayed(hideBubble, wait);
    }

    private void move(float x, float y) {
        float dx = x - lastX, dy = y - lastY;
        lastX = x;
        lastY = y;
        if (touchKind == STRIP) {
            if (pressedStrip >= 0 && (stripTarget(x) != pressedStrip || y > m.stripH + dp(24))) {
                pressedStrip = -1;
                kick();
            }
            return;
        }
        if (touchKind != KEY || downKey == null) return;

        if (trackpad) {
            accX += dx;
            accY += dy;
            float stepX = dp(9), stepY = dp(24);
            int sx = (int) (accX / stepX);
            int sy = (int) (accY / stepY);
            if (sx != 0 || sy != 0) {
                accX -= sx * stepX;
                accY -= sy * stepY;
                listener.onCursor(sx, sy);
            }
            return;
        }
        if (altsShown) {
            int s = altIndexAt(x);
            if (s != altSel) {
                altSel = s;
                listener.onFeedback(Key.CHAR);
                kick();
            }
            return;
        }
        if (downKey.type != Key.CHAR || prefs.liquidTouch) {
            downKey.touchX = x;
            downKey.touchY = y;
            if (downKey.type != Key.CHAR) invalidate();
        }
        if (downKey.type == Key.SPACE && prefs.trackpad && Math.abs(x - downX) > dp(18)) {
            // A swipe along the space bar also moves the cursor.
            handler.removeCallbacks(longPress);
            startTrackpad();
            return;
        }
        if (downKey.type == Key.CHAR || modeSlide) {
            Key k = findKey(x, y);
            if (k != null && k != downKey && (k.type == Key.CHAR || (modeSlide && k.type == Key.MODE))) {
                handler.removeCallbacks(longPress);
                setPressed(downKey, false, 0, 0);
                downKey = k;
                setPressed(k, true, x, y);
                if (k.type == Key.CHAR) {
                    movedOff = true;
                    showBubble(k);
                    if (k.alts != null) handler.postDelayed(longPress, prefs.longPressMs);
                } else {
                    bubbleT.target = 0;
                }
                kick();
            }
        }
    }

    private void release(float x, float y) {
        if (touchKind == STRIP) {
            int i = pressedStrip;
            pressedStrip = -1;
            touchKind = NONE;
            pointerId = -1;
            kick();
            if (i == TOOL_CLIP) listener.onClipboard();
            else if (i == TOOL_SETTINGS) listener.onSettings();
            else if (i == TOOL_PASTE) listener.onQuickPaste();
            else if (i >= 0) listener.onSuggestion(i);
            return;
        }
        int kind = touchKind;
        Key k = downKey;
        boolean inside = k != null && k.hit.contains(x, y);
        boolean wasTrackpad = trackpad, wasAlts = altsShown, wasLong = longFired;
        boolean slideBack = modeSlide && movedOff;
        int sel = altSel;
        String[] shownAlts = alts;
        endTouch();
        if (kind != KEY || k == null || wasTrackpad) return;

        if (wasAlts) {
            if (sel >= 0 && shownAlts != null && sel < shownAlts.length) {
                listener.onText(applyCase(shownAlts[sel]));
                if (slideBack) listener.onSlideReturn();
            }
            return;
        }
        switch (k.type) {
            case Key.CHAR:
                commitChar(k);
                if (slideBack) listener.onSlideReturn();
                break;
            case Key.SPACE:
                if (inside) listener.onSpace();
                break;
            case Key.RETURN:
                if (inside) listener.onReturn();
                break;
            case Key.EMOJI:
                if (inside) listener.onEmoji();
                break;
            case Key.GLOBE:
                if (inside && !wasLong) listener.onGlobe();
                break;
            default:
                break;
        }
    }

    private void endTouch() {
        handler.removeCallbacks(longPress);
        handler.removeCallbacks(repeatDelete);
        if (trackpad) {
            trackpad = false;
            labelsT.target = 1;
        }
        if (downKey != null) setPressed(downKey, false, 0, 0);
        releaseBubble();
        altsShown = false;
        altsT.target = 0;
        downKey = null;
        modeSlide = false;
        movedOff = false;
        touchKind = NONE;
        pointerId = -1;
        pressedStrip = -1;
        if (!animOn()) snapAll();
        kick();
    }

    private void onLongPress() {
        Key k = downKey;
        if (k == null || touchKind != KEY) return;
        switch (k.type) {
            case Key.CHAR:
                if (k.alts != null) showAlts(k);
                break;
            case Key.SPACE:
                startTrackpad();
                break;
            case Key.GLOBE:
                longFired = true;
                listener.onGlobeLong();
                break;
            default:
                break;
        }
    }

    private void startTrackpad() {
        if (trackpad || !prefs.trackpad) return;
        trackpad = true;
        longFired = true;
        accX = 0;
        accY = 0;
        listener.onFeedback(Key.CHAR);
        labelsT.target = 0;
        kick();
    }

    private void showAlts(Key k) {
        longFired = true;
        String[] a = k.alts;
        int n = a.length;
        altPad = dp(5);
        altCellW = Math.max(k.rect.width(), dp(28));
        float w = n * altCellW + 2 * altPad;
        float h = m.keyH * 1.12f;
        float W = getWidth();
        float left = k.rect.centerX() - altCellW / 2 - altPad;
        boolean reversed = false;
        if (left + w > W - m.side) {
            // No room on the right: grow to the left, first variant still above the key.
            left = k.rect.centerX() + altCellW / 2 + altPad - w;
            reversed = true;
        }
        left = Math.max(m.side, Math.min(left, W - m.side - w));
        float top = Math.max(dp(1), k.rect.top - dp(9) - h);
        altsRect.set(left, top, left + w, top + h);
        alts = new String[n];
        for (int i = 0; i < n; i++) alts[i] = reversed ? a[n - 1 - i] : a[i];
        altSel = altIndexAt(k.rect.centerX());
        altsKey = k;
        altsShown = true;
        altsT.v = animOn() ? Math.max(altsT.v, 0.15f) : 1f;
        altsT.target = 1;
        bubbleT.target = 0;
        bubbleT.v = 0;
        listener.onFeedback(Key.CHAR);
        kick();
    }

    private int altIndexAt(float x) {
        if (alts == null) return -1;
        int i = (int) Math.floor((x - altsRect.left - altPad) / altCellW);
        return Math.max(0, Math.min(alts.length - 1, i));
    }

    private void commitChar(Key k) {
        listener.onText(applyCase(k.output));
    }

    private String applyCase(String s) {
        return upper() ? s.toUpperCase(locale) : s;
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = frameActive ? Math.min(now - lastFrame, 48) : 16;
        lastFrame = now;
        boolean moving = step(dt);

        drawPanel(c);
        drawStrip(c);
        if (layout != null) {
            for (Key[] row : layout.rows) {
                for (Key k : row) drawKey(c, k);
            }
        }
        drawGlobeStrip(c);
        if (bubbleKey != null && bubbleT.v > 0.004f && labelsT.v > 0.5f) drawPreview(c, bubbleKey, bubbleT.v);
        if (altsKey != null && altsT.v > 0.004f) drawAlts(c, altsKey, altsT.v);

        frameActive = moving;
        if (moving) postInvalidateOnAnimation();
    }

    private static int alpha(int color, float a) {
        int al = Math.round(((color >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, a)));
        return (color & 0x00FFFFFF) | (al << 24);
    }

    private static int blend(int a, int b, float t) {
        if (t <= 0) return a;
        if (t >= 1) return b;
        int aa = a >>> 24, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = b >>> 24, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return (Math.round(aa + (ba - aa) * t) << 24)
                | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8)
                | Math.round(ab + (bb - ab) * t);
    }

    private void shaderAt(Shader s, float top, float h) {
        matrix.setScale(1, h);
        matrix.postTranslate(0, top);
        s.setLocalMatrix(matrix);
    }

    private void drawPanel(Canvas c) {
        if (!theme.glass) {
            c.drawColor(theme.bg);
            return;
        }
        shaderAt(panelShader, 0, getHeight());
        fill.setShader(panelShader);
        c.drawRect(0, 0, getWidth(), getHeight(), fill);
        fill.setShader(null);
        // Glass edge: a fine bright line along the top.
        fill.setColor(theme.dark ? 0x26FFFFFF : 0xB3FFFFFF);
        c.drawRect(0, 0, getWidth(), Math.max(1f, dp(0.6f)), fill);
    }

    private void drawStrip(Canvas c) {
        float sv = stripT.v;
        // Toolbar (clipboard / quick paste / settings) fades out as suggestions fade in.
        if (sv < 0.999f) drawToolbar(c, 1f - sv);
        if (sv <= 0.001f) return;

        float cw = getWidth() / 3f;
        text.setTextSize(dp(16.5f));
        for (int i = 0; i < 3; i++) {
            CharSequence s = suggestionsShown[i];
            if (s == null) continue;
            if (i == pressedStrip) {
                fill.setColor(theme.highlight);
                tmp.set(i * cw + dp(3), dp(5), (i + 1) * cw - dp(3), m.stripH - dp(5));
                c.drawRoundRect(tmp, dp(9), dp(9), fill);
            }
            text.setColor(alpha(theme.text, sv));
            text.setTypeface(i == 1 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            float base = m.stripH / 2 - (text.descent() + text.ascent()) / 2;
            c.drawText(s, 0, s.length(), i * cw + cw / 2, base, text);
        }
        text.setTypeface(Typeface.DEFAULT);
        stroke.setColor(alpha(theme.separator, sv));
        stroke.setStrokeWidth(Math.max(1f, dp(0.8f)));
        for (int i = 1; i < 3; i++) {
            c.drawLine(i * cw, m.stripH * 0.27f, i * cw, m.stripH * 0.73f, stroke);
        }
    }

    private void drawToolbar(Canvas c, float a) {
        float tw = toolW();
        float cy = m.stripH / 2;
        int col = alpha(theme.text, a * (theme.dark ? 0.8f : 0.6f));
        if (pressedStrip == TOOL_CLIP || pressedStrip == TOOL_SETTINGS) {
            fill.setColor(alpha(theme.highlight, a));
            float cx = pressedStrip == TOOL_CLIP ? tw / 2 : getWidth() - tw / 2;
            c.drawCircle(cx, cy, Math.min(m.stripH * 0.42f, dp(19)), fill);
        }
        drawClipboardIcon(c, tw / 2, cy, col);
        drawSlidersIcon(c, getWidth() - tw / 2, cy, col);
        if (quickClipShown != null) {
            text.setTypeface(Typeface.DEFAULT);
            text.setTextSize(dp(14));
            float w = Math.min(text.measureText(quickClipShown, 0, quickClipShown.length()) + dp(26),
                    getWidth() - 2 * tw - dp(12));
            float cx = getWidth() / 2f;
            tmp.set(cx - w / 2, cy - dp(15), cx + w / 2, cy + dp(15));
            fill.setColor(alpha(pressedStrip == TOOL_PASTE ? theme.highlight : (theme.dark ? 0x24FFFFFF : 0x99FFFFFF), a));
            c.drawRoundRect(tmp, dp(15), dp(15), fill);
            text.setColor(alpha(theme.text, a));
            float base = cy - (text.descent() + text.ascent()) / 2;
            c.drawText(quickClipShown, 0, quickClipShown.length(), cx, base, text);
        }
    }

    private void drawKey(Canvas c, Key k) {
        RectF r = k.rect;
        float p = k.press;
        boolean bubbleCovers = k == bubbleKey && bubbleT.v > 0.97f;
        if (bubbleCovers || (altsShown && k == altsKey && altsT.v > 0.6f)) return;

        boolean special = k.isSpecial();
        float accent = k.type == Key.RETURN ? accentT.v : 0f;
        float shiftOn = k.type == Key.SHIFT ? shiftT.v : 0f;

        // Shadow under the key (clipped so it never darkens translucent glass).
        drawShadow(c, r, m.radius);

        // Body
        if (theme.glass) {
            Shader s = special ? specShader : keyShader;
            shaderAt(s, r.top, r.height());
            fill.setShader(s);
            c.drawRoundRect(r, m.radius, m.radius, fill);
            fill.setShader(null);
        } else {
            fill.setColor(special ? theme.specTop : theme.keyTop);
            c.drawRoundRect(r, m.radius, m.radius, fill);
        }
        if (accent > 0.001f) {
            fill.setColor(alpha(theme.accent, accent));
            c.drawRoundRect(r, m.radius, m.radius, fill);
        }
        if (shiftOn > 0.001f) {
            fill.setColor(alpha(theme.shiftOnBg, shiftOn));
            c.drawRoundRect(r, m.radius, m.radius, fill);
        }
        // Pressed state: specials light up, space and letters dim slightly (iOS).
        if (p > 0.001f) {
            int overlay = special ? theme.pressSpecial : theme.pressSpace;
            if (k.type == Key.CHAR && !prefs.popups) overlay = theme.pressSpace;
            fill.setColor(alpha(overlay, p));
            c.drawRoundRect(r, m.radius, m.radius, fill);
            if (theme.glass && prefs.liquidTouch) drawGlow(c, k, p);
        }
        // Glass rim and sheen
        if (theme.glass) drawRim(c, r, m.radius, 1f);

        // Content
        float la = labelsT.v * (k.type == Key.SPACE || k.type == Key.RETURN ? 1f : layoutT.v);
        int fg = blend(theme.text, theme.accentText, accent);
        fg = blend(fg, theme.shiftOnFg, shiftOn);
        if (accent > 0 && p > 0) fg = blend(fg, theme.text, p * accent);
        int col = alpha(fg, la);
        float cx = r.centerX(), cy = r.centerY();
        switch (k.type) {
            case Key.CHAR:
                text.setTypeface(Typeface.DEFAULT);
                text.setTextSize(Math.min(m.letterSize, r.width() * 0.8f));
                if (lettersMode && caseT.v > 0.001f && caseT.v < 0.999f) {
                    text.setColor(alpha(fg, la * (1f - caseT.v)));
                    drawCentered(c, k.label, cx, cy - dp(1));
                    text.setColor(alpha(fg, la * caseT.v));
                    drawCentered(c, k.upper, cx, cy - dp(1));
                } else {
                    text.setColor(col);
                    drawCentered(c, lettersMode && caseT.v >= 0.999f ? k.upper : k.label, cx, cy - dp(1));
                }
                break;
            case Key.SHIFT:
                drawShift(c, cx, cy, col, shiftState);
                break;
            case Key.DELETE:
                drawDelete(c, cx, cy, col);
                break;
            case Key.EMOJI:
                drawSmiley(c, cx, cy, col);
                break;
            case Key.GLOBE:
                drawGlobeIcon(c, cx, cy, col, Math.min(dp(10.5f), r.height() * 0.26f));
                break;
            case Key.SPACE: {
                text.setTypeface(Typeface.DEFAULT);
                text.setTextSize(m.labelSize);
                float f = flashT.v;
                if (flashLabel != null && f > 0.001f) {
                    text.setColor(alpha(fg, la * f));
                    drawCentered(c, flashShown, cx, cy);
                }
                text.setColor(alpha(fg, la * (1f - f)));
                drawCentered(c, spaceShown, cx, cy);
                break;
            }
            case Key.RETURN:
                text.setTypeface(Typeface.DEFAULT);
                text.setTextSize(m.labelSize);
                text.setColor(col);
                drawCentered(c, returnShown, cx, cy);
                break;
            case Key.MODE:
            case Key.MORE:
                text.setTypeface(Typeface.DEFAULT);
                text.setTextSize(m.labelSize);
                text.setColor(col);
                drawCentered(c, k.label, cx, cy);
                break;
            default:
                break;
        }
    }

    private void drawShadow(Canvas c, RectF r, float rad) {
        int sh = theme.shadow;
        if (Build.VERSION.SDK_INT >= 26) {
            clip.reset();
            clip.addRoundRect(r, rad, rad, Path.Direction.CW);
            c.save();
            c.clipOutPath(clip);
        }
        fill.setColor(sh);
        tmp.set(r.left, r.top + dp(1), r.right, r.bottom + dp(1));
        c.drawRoundRect(tmp, rad, rad, fill);
        if (theme.glass) {
            fill.setColor(alpha(sh, 0.45f));
            tmp.set(r.left - dp(0.3f), r.top + dp(1.8f), r.right + dp(0.3f), r.bottom + dp(2.2f));
            c.drawRoundRect(tmp, rad + dp(1), rad + dp(1), fill);
        }
        if (Build.VERSION.SDK_INT >= 26) c.restore();
    }

    private void drawRim(Canvas c, RectF r, float rad, float a) {
        float sw = Math.max(1f, dp(0.75f));
        shaderAt(rimShader, r.top, r.height());
        rim.setShader(rimShader);
        rim.setStrokeWidth(sw);
        rim.setAlpha(Math.round(255 * a));
        tmp.set(r.left + sw / 2, r.top + sw / 2, r.right - sw / 2, r.bottom - sw / 2);
        c.drawRoundRect(tmp, rad, rad, rim);
        rim.setShader(null);
        rim.setAlpha(255);
    }

    /** "Liquid" light that follows the finger inside a pressed glass key. */
    private void drawGlow(Canvas c, Key k, float p) {
        RectF r = k.rect;
        float tx = Math.max(r.left, Math.min(r.right, k.touchX));
        float ty = Math.max(r.top, Math.min(r.bottom, k.touchY));
        float rad = Math.max(r.height() * 1.15f, dp(20));
        matrix.setScale(rad, rad);
        matrix.postTranslate(tx, ty);
        glowShader.setLocalMatrix(matrix);
        glowPaint.setAlpha(Math.round(255 * p));
        c.drawRoundRect(r, m.radius, m.radius, glowPaint);
    }

    private void drawCentered(Canvas c, CharSequence s, float cx, float cy) {
        float base = cy - (text.descent() + text.ascent()) / 2;
        c.drawText(s, 0, s.length(), cx, base, text);
    }

    /** Bubble shape that grows out of the key: rounded box on top, curved neck, the key itself below. */
    private void buildBubble(RectF b, RectF key) {
        float rb = Math.min(dp(11), b.height() / 3);
        float r = m.radius;
        float L = key.left, R = key.right, T = key.top, B = key.bottom;
        float bb = b.bottom;
        float mid = (bb + T) / 2;
        path.reset();
        path.moveTo(b.left, b.top + rb);
        path.quadTo(b.left, b.top, b.left + rb, b.top);
        path.lineTo(b.right - rb, b.top);
        path.quadTo(b.right, b.top, b.right, b.top + rb);
        path.lineTo(b.right, bb);
        path.cubicTo(b.right, mid, R, mid, R, T);
        path.lineTo(R, B - r);
        path.quadTo(R, B, R - r, B);
        path.lineTo(L + r, B);
        path.quadTo(L, B, L, B - r);
        path.lineTo(L, T);
        path.cubicTo(L, mid, b.left, mid, b.left, bb);
        path.close();
    }

    private void drawBubbleShape(Canvas c, float a) {
        // Soft shadow
        c.save();
        c.translate(0, dp(1));
        fill.setColor(alpha(theme.glass ? (theme.dark ? 0x99000000 : 0x40202A3A) : theme.shadow, a * (theme.dark ? 0.9f : 0.6f)));
        c.drawPath(path, fill);
        c.translate(0, dp(1.5f));
        fill.setColor(alpha(theme.dark ? 0x4D000000 : 0x1A202A3A, a));
        c.drawPath(path, fill);
        c.restore();
        // Body
        shaderAt(bubbleShader, bubble.top, bubbleKeyBottom() - bubble.top);
        fill.setShader(bubbleShader);
        fill.setAlpha(Math.round(255 * a));
        c.drawPath(path, fill);
        fill.setShader(null);
        fill.setAlpha(255);
        if (theme.glass) {
            rim.setShader(null);
            rim.setStrokeWidth(Math.max(1f, dp(0.75f)));
            rim.setColor(alpha(theme.dark ? 0x59FFFFFF : 0xFFFFFFFF, a));
            c.drawPath(path, rim);
        }
    }

    private float bubbleKeyBottom() {
        return bubbleKey != null ? bubbleKey.rect.bottom : bubble.bottom;
    }

    private void drawPreview(Canvas c, Key k, float t) {
        RectF kr = k.rect;
        float bw = kr.width() + dp(22);
        float bh = m.keyH * 1.12f;
        float neck = dp(9);
        float top = Math.max(dp(1), kr.top - neck - bh);
        float bottom = kr.top - neck;
        float left = kr.centerX() - bw / 2;
        left = Math.max(dp(1), Math.min(left, getWidth() - dp(1) - bw));
        bubble.set(left, top, left + bw, bottom);
        buildBubble(bubble, kr);

        c.save();
        drawBubbleShape(c, t);
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(Math.min(m.bubbleTextSize, bubble.height() * 0.8f));
        text.setColor(alpha(theme.text, t));
        drawCentered(c, upper() ? k.upper : k.label, bubble.centerX(), bubble.centerY() + dp(1));
        c.restore();
    }

    private void drawAlts(Canvas c, Key k, float t) {
        if (alts == null) return;
        bubble.set(altsRect);
        bubble.bottom = Math.min(altsRect.bottom, k.rect.top - dp(4));
        buildBubble(bubble, k.rect);
        float s = 0.75f + 0.25f * t;
        c.save();
        c.scale(s, s, k.rect.centerX(), k.rect.bottom);
        drawBubbleShape(c, t);
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(Math.min(m.letterSize * 1.05f, altCellW * 0.8f));
        for (int i = 0; i < alts.length; i++) {
            float l = altsRect.left + altPad + i * altCellW;
            float cy = bubble.centerY();
            if (i == altSel && altsShown) {
                fill.setColor(alpha(theme.accent, t));
                tmp.set(l + dp(1), bubble.top + dp(5), l + altCellW - dp(1), bubble.bottom - dp(5));
                c.drawRoundRect(tmp, dp(7), dp(7), fill);
                text.setColor(alpha(theme.accentText, t));
            } else {
                text.setColor(alpha(theme.text, t));
            }
            drawCentered(c, applyCase(alts[i]), l + altCellW / 2, cy);
        }
        c.restore();
    }

    private void drawGlobeStrip(Canvas c) {
        if (globeZone <= 0) return;
        float cx = globeKey.rect.centerX();
        float cy = globeTop + globeZone * 0.48f;
        float r = Math.min(dp(10.5f), Math.max(dp(8), globeZone * 0.3f));
        float p = globeKey.press;
        if (p > 0.001f) {
            fill.setColor(alpha(theme.highlight, p));
            c.drawCircle(cx, cy, r + dp(8), fill);
        }
        drawGlobeIcon(c, cx, cy, alpha(theme.text, theme.dark ? 0.85f : 0.62f), r);
    }

    private void drawGlobeIcon(Canvas c, float cx, float cy, int col, float r) {
        stroke.setColor(col);
        stroke.setStrokeWidth(dp(1.5f));
        c.drawCircle(cx, cy, r, stroke);
        tmp.set(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r);
        c.drawOval(tmp, stroke);
        c.drawLine(cx, cy - r, cx, cy + r, stroke);
        c.drawLine(cx - r, cy, cx + r, cy, stroke);
        float chord = r * 0.866f;
        c.drawLine(cx - chord, cy - r * 0.5f, cx + chord, cy - r * 0.5f, stroke);
        c.drawLine(cx - chord, cy + r * 0.5f, cx + chord, cy + r * 0.5f, stroke);
    }

    private void drawClipboardIcon(Canvas c, float cx, float cy, int col) {
        float w = dp(13), h = dp(17);
        stroke.setColor(col);
        stroke.setStrokeWidth(dp(1.6f));
        tmp.set(cx - w / 2, cy - h / 2 + dp(1.5f), cx + w / 2, cy + h / 2 + dp(1.5f));
        c.drawRoundRect(tmp, dp(2.5f), dp(2.5f), stroke);
        fill.setColor(col);
        tmp.set(cx - dp(3.5f), cy - h / 2 - dp(0.5f), cx + dp(3.5f), cy - h / 2 + dp(3f));
        c.drawRoundRect(tmp, dp(1.2f), dp(1.2f), fill);
        c.drawLine(cx - dp(3.2f), cy + dp(1.5f), cx + dp(3.2f), cy + dp(1.5f), stroke);
        c.drawLine(cx - dp(3.2f), cy + dp(5f), cx + dp(1.5f), cy + dp(5f), stroke);
    }

    private void drawSlidersIcon(Canvas c, float cx, float cy, int col) {
        stroke.setColor(col);
        stroke.setStrokeWidth(dp(1.6f));
        fill.setColor(col);
        float w = dp(8.5f);
        float[] ys = {cy - dp(5.5f), cy, cy + dp(5.5f)};
        float[] knobs = {cx + dp(3), cx - dp(3.5f), cx + dp(1)};
        for (int i = 0; i < 3; i++) {
            c.drawLine(cx - w, ys[i], cx + w, ys[i], stroke);
            c.drawCircle(knobs[i], ys[i], dp(2.6f), fill);
        }
    }

    private void drawShift(Canvas c, float cx, float cy, int color, int state) {
        float top = cy - dp(9.5f), mid = cy - dp(0.5f), bottom = cy + dp(5.5f);
        float head = dp(9.5f), stem = dp(4.3f);
        path.reset();
        path.moveTo(cx, top);
        path.lineTo(cx + head, mid);
        path.lineTo(cx + stem, mid);
        path.lineTo(cx + stem, bottom);
        path.lineTo(cx - stem, bottom);
        path.lineTo(cx - stem, mid);
        path.lineTo(cx - head, mid);
        path.close();
        if (state == SHIFT_OFF) {
            stroke.setColor(color);
            stroke.setStrokeWidth(dp(1.6f));
            c.drawPath(path, stroke);
        } else {
            fill.setColor(color);
            c.drawPath(path, fill);
            if (state == SHIFT_LOCK) {
                tmp.set(cx - stem, bottom + dp(2.5f), cx + stem, bottom + dp(4.5f));
                c.drawRect(tmp, fill);
            }
        }
    }

    private void drawDelete(Canvas c, float cx, float cy, int color) {
        float h = dp(8.5f), body = dp(15), tip = dp(7.5f), rr = dp(2.5f);
        float x0 = cx - (body + tip) / 2, x1 = x0 + tip, x2 = x1 + body;
        path.reset();
        path.moveTo(x0, cy);
        path.lineTo(x1, cy - h);
        path.lineTo(x2 - rr, cy - h);
        path.quadTo(x2, cy - h, x2, cy - h + rr);
        path.lineTo(x2, cy + h - rr);
        path.quadTo(x2, cy + h, x2 - rr, cy + h);
        path.lineTo(x1, cy + h);
        path.close();
        stroke.setColor(color);
        stroke.setStrokeWidth(dp(1.6f));
        c.drawPath(path, stroke);
        float xc = x1 + body / 2 - dp(0.5f), xs = dp(3.3f);
        c.drawLine(xc - xs, cy - xs, xc + xs, cy + xs, stroke);
        c.drawLine(xc - xs, cy + xs, xc + xs, cy - xs, stroke);
    }

    private void drawSmiley(Canvas c, float cx, float cy, int color) {
        float r = dp(10);
        stroke.setColor(color);
        stroke.setStrokeWidth(dp(1.6f));
        c.drawCircle(cx, cy, r, stroke);
        fill.setColor(color);
        c.drawCircle(cx - r * 0.36f, cy - r * 0.25f, dp(1.4f), fill);
        c.drawCircle(cx + r * 0.36f, cy - r * 0.25f, dp(1.4f), fill);
        tmp.set(cx - r * 0.52f, cy - r * 0.35f, cx + r * 0.52f, cy + r * 0.55f);
        c.drawArc(tmp, 20, 140, false, stroke);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacksAndMessages(null);
    }
}
