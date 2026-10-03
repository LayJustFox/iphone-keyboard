package com.iphonekb;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.Locale;

/**
 * Draws the whole keyboard (suggestion bar, keys, globe strip) and handles touches.
 * Everything is drawn on one canvas so the letter bubble can rise over the suggestion bar.
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
    }

    static final int SHIFT_OFF = 0, SHIFT_ON = 1, SHIFT_LOCK = 2;

    private static final int NONE = 0, KEY = 1, STRIP = 2;

    private final Listener listener;
    private final float density;
    private Theme theme = Theme.light();
    private Layout layout;
    private Locale locale = Locale.US;
    private int shiftState = SHIFT_OFF;
    private boolean lettersMode = true;

    private String spaceLabel = "space";
    private String returnLabel = "return";
    private boolean returnAccent;
    private String flashLabel;
    private float flashAlpha;
    private ValueAnimator flashAnim;

    private final String[] suggestions = new String[3];

    // metrics (px)
    private float stripH, topPad, keyH, vGap, hGap, side, bottomPad, globeH, radius;
    private float keysTop, globeTop;

    private final Key globeKey = new Key(Key.GLOBE, "");

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF tmp = new RectF();
    private final RectF bubble = new RectF();

    // touch state
    private int pointerId = -1;
    private int touchKind = NONE;
    private Key downKey;
    private float downX;
    private float lastX, lastY;
    private int pressedSuggestion = -1;
    private boolean modeSlide, movedOff, longFired;

    private boolean altsShown;
    private String[] alts;
    private final RectF altsRect = new RectF();
    private float altCellW, altPad;
    private int altSel;

    private boolean trackpad;
    private float accX, accY;
    private float labelAlpha = 1f;
    private ValueAnimator labelAnim;

    private int deleteCount;
    private long deleteStart;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            onLongPress();
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

    KeyboardView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        this.density = context.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        computeMetrics();
    }

    private float dp(float v) {
        return v * density;
    }

    // ---------------------------------------------------------------- state from the service

    void setTheme(Theme t) {
        theme = t;
        invalidate();
    }

    void setLayout(Layout l, boolean letters) {
        layout = l;
        lettersMode = letters;
        layoutKeys();
        invalidate();
    }

    void setLocale(Locale l) {
        locale = l;
        invalidate();
    }

    void setShift(int s) {
        if (shiftState != s) {
            shiftState = s;
            invalidate();
        }
    }

    void setSpaceLabel(String s) {
        if (!s.equals(spaceLabel)) {
            spaceLabel = s;
            invalidate();
        }
    }

    void setReturn(String label, boolean accent) {
        returnLabel = label;
        returnAccent = accent;
        invalidate();
    }

    void setSuggestions(String[] s) {
        for (int i = 0; i < 3; i++) suggestions[i] = s != null && i < s.length ? s[i] : null;
        invalidate();
    }

    /** The language name lights up on the space bar, then fades back to "space". */
    void flashLanguage(String name) {
        flashLabel = name;
        flashAlpha = 1f;
        if (flashAnim != null) flashAnim.cancel();
        flashAnim = ValueAnimator.ofFloat(1f, 0f);
        flashAnim.setStartDelay(900);
        flashAnim.setDuration(450);
        flashAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                flashAlpha = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        flashAnim.start();
        invalidate();
    }

    void cancelTouch() {
        endTouch();
    }

    // ---------------------------------------------------------------- metrics & layout

    private void computeMetrics() {
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (land) {
            stripH = dp(38); topPad = dp(6); keyH = dp(34); vGap = dp(7); bottomPad = dp(3); globeH = dp(30);
        } else {
            stripH = dp(46); topPad = dp(10); keyH = dp(42); vGap = dp(12); bottomPad = dp(4); globeH = dp(40);
        }
        hGap = dp(6);
        side = dp(3);
        radius = dp(5);
        keysTop = stripH + topPad;
    }

    private float totalHeight() {
        return keysTop + 4 * keyH + 3 * vGap + bottomPad + globeH;
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
    }

    private void layoutKeys() {
        if (layout == null || getWidth() == 0) return;
        float W = getWidth();
        int cols = layout.cols;
        float unit = (W - 2 * side - (cols - 1) * hGap) / cols;
        float y = keysTop;
        Key[][] rows = layout.rows;
        globeTop = keysTop + rows.length * keyH + (rows.length - 1) * vGap + bottomPad;
        for (int r = 0; r < rows.length; r++) {
            Key[] row = rows[r];
            boolean last = r == rows.length - 1;
            if (last) layoutBottom(row, y, W);
            else layoutRow(row, y, W, unit);

            float top = r == 0 ? stripH : y - vGap / 2;
            float bottom = last ? globeTop : y + keyH + vGap / 2;
            for (int i = 0; i < row.length; i++) {
                Key k = row[i];
                float left = i == 0 ? 0 : (row[i - 1].rect.right + k.rect.left) / 2;
                float right = i == row.length - 1 ? W : (k.rect.right + row[i + 1].rect.left) / 2;
                k.hit.set(left, top, right, bottom);
            }
            y += keyH + vGap;
        }
        float gw = Math.min(dp(64), W / 5);
        globeKey.rect.set(dp(8), globeTop, dp(8) + gw - dp(16), globeTop + globeH);
        globeKey.hit.set(0, globeTop, gw, getHeight());
    }

    private void layoutRow(Key[] row, float y, float W, float unit) {
        int n = row.length;
        float charsW = 0;
        int nc = 0;
        for (Key k : row) {
            if (k.type == Key.CHAR) {
                charsW += unit * k.width;
                nc++;
            }
        }
        if (nc > 1) charsW += (nc - 1) * hGap;
        float x = (W - charsW) / 2;
        for (Key k : row) {
            if (k.type != Key.CHAR) continue;
            k.rect.set(x, y, x + unit * k.width, y + keyH);
            x += unit * k.width + hGap;
        }
        float avail = (W - 2 * side - charsW) / 2 - hGap;
        float sw = Math.max(unit * 0.8f, Math.min(unit * 1.32f, avail));
        if (row[0].type != Key.CHAR) row[0].rect.set(side, y, side + sw, y + keyH);
        if (n > 1 && row[n - 1].type != Key.CHAR) row[n - 1].rect.set(W - side - sw, y, W - side, y + keyH);
    }

    private void layoutBottom(Key[] row, float y, float W) {
        float avail = W - 2 * side - (row.length - 1) * hGap;
        float fixed = 0;
        float[] w = new float[row.length];
        for (int i = 0; i < row.length; i++) {
            switch (row[i].type) {
                case Key.MODE:
                case Key.EMOJI:
                    w[i] = avail * 0.118f;
                    break;
                case Key.RETURN:
                    w[i] = avail * 0.25f;
                    break;
                default:
                    w[i] = -1;
            }
            if (w[i] > 0) fixed += w[i];
        }
        float x = side;
        for (int i = 0; i < row.length; i++) {
            float kw = w[i] > 0 ? w[i] : avail - fixed;
            row[i].rect.set(x, y, x + kw, y + keyH);
            x += kw + hGap;
        }
    }

    private Key findKey(float x, float y) {
        if (layout == null) return null;
        if (y >= globeTop) return globeKey.hit.contains(x, y) ? globeKey : null;
        for (Key[] row : layout.rows) {
            for (Key k : row) {
                if (k.hit.contains(x, y)) return k;
            }
        }
        return null;
    }

    private int suggestionIndex(float x) {
        int i = (int) (x / (getWidth() / 3f));
        if (i < 0 || i > 2 || suggestions[i] == null) return -1;
        return i;
    }

    // ---------------------------------------------------------------- touch

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
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
        if (y < stripH) {
            touchKind = STRIP;
            pressedSuggestion = suggestionIndex(x);
            invalidate();
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
                break;
            case Key.MORE:
                listener.onMoreKey();
                downKey = findKey(x, y);
                break;
            case Key.CHAR:
                if (k.alts != null) handler.postDelayed(longPress, 380);
                break;
            case Key.SPACE:
                handler.postDelayed(longPress, 420);
                break;
            case Key.GLOBE:
                handler.postDelayed(longPress, 500);
                break;
            default:
                break;
        }
        invalidate();
    }

    private void move(float x, float y) {
        float dx = x - lastX, dy = y - lastY;
        lastX = x;
        lastY = y;
        if (touchKind == STRIP) {
            if (pressedSuggestion >= 0 && (suggestionIndex(x) != pressedSuggestion || y > stripH + dp(24))) {
                pressedSuggestion = -1;
                invalidate();
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
                invalidate();
            }
            return;
        }
        if (downKey.type == Key.SPACE && Math.abs(x - downX) > dp(18)) {
            // A swipe along the space bar also moves the cursor.
            handler.removeCallbacks(longPress);
            startTrackpad();
            return;
        }
        if (downKey.type == Key.CHAR || modeSlide) {
            Key k = findKey(x, y);
            if (k != null && k != downKey && (k.type == Key.CHAR || (modeSlide && k.type == Key.MODE))) {
                handler.removeCallbacks(longPress);
                downKey = k;
                if (k.type == Key.CHAR) {
                    movedOff = true;
                    if (k.alts != null) handler.postDelayed(longPress, 380);
                }
                invalidate();
            }
        }
    }

    private void release(float x, float y) {
        if (touchKind == STRIP) {
            int i = pressedSuggestion;
            pressedSuggestion = -1;
            touchKind = NONE;
            pointerId = -1;
            invalidate();
            if (i >= 0) listener.onSuggestion(i);
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
            animateLabels(1f);
        }
        altsShown = false;
        downKey = null;
        modeSlide = false;
        movedOff = false;
        touchKind = NONE;
        pointerId = -1;
        pressedSuggestion = -1;
        invalidate();
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
        if (trackpad) return;
        trackpad = true;
        longFired = true;
        accX = 0;
        accY = 0;
        listener.onFeedback(Key.CHAR);
        animateLabels(0f);
    }

    private void animateLabels(float target) {
        if (labelAnim != null) labelAnim.cancel();
        labelAnim = ValueAnimator.ofFloat(labelAlpha, target);
        labelAnim.setDuration(200);
        labelAnim.setInterpolator(new DecelerateInterpolator());
        labelAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                labelAlpha = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        labelAnim.start();
    }

    private void showAlts(Key k) {
        longFired = true;
        String[] a = k.alts;
        int n = a.length;
        altPad = dp(5);
        altCellW = Math.max(k.rect.width(), dp(28));
        float w = n * altCellW + 2 * altPad;
        float h = keyH * 1.12f;
        float W = getWidth();
        float left = k.rect.centerX() - altCellW / 2 - altPad;
        boolean reversed = false;
        if (left + w > W - side) {
            // No room on the right: grow to the left, first variant still above the key.
            left = k.rect.centerX() + altCellW / 2 + altPad - w;
            reversed = true;
        }
        left = Math.max(side, Math.min(left, W - side - w));
        float top = Math.max(dp(1), k.rect.top - dp(9) - h);
        altsRect.set(left, top, left + w, top + h);
        alts = new String[n];
        for (int i = 0; i < n; i++) alts[i] = reversed ? a[n - 1 - i] : a[i];
        altSel = altIndexAt(k.rect.centerX());
        altsShown = true;
        listener.onFeedback(Key.CHAR);
        invalidate();
    }

    private int altIndexAt(float x) {
        if (alts == null) return -1;
        int i = (int) Math.floor((x - altsRect.left - altPad) / altCellW);
        return Math.max(0, Math.min(alts.length - 1, i));
    }

    private void commitChar(Key k) {
        listener.onText(applyCase(k.output));
    }

    private boolean upper() {
        return lettersMode && shiftState != SHIFT_OFF;
    }

    private String applyCase(String s) {
        return upper() ? s.toUpperCase(locale) : s;
    }

    private String labelOf(Key k) {
        return upper() ? k.label.toUpperCase(locale) : k.label;
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(theme.bg);
        drawStrip(c);
        if (layout != null) {
            for (Key[] row : layout.rows) {
                for (Key k : row) drawKey(c, k);
            }
        }
        drawGlobe(c);
        if (touchKind == KEY && downKey != null && !trackpad) {
            if (altsShown) drawAlts(c, downKey);
            else if (downKey.type == Key.CHAR) drawPreview(c, downKey);
        }
    }

    private static int alpha(int color, float a) {
        int al = Math.round(((color >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, a)));
        return (color & 0x00FFFFFF) | (al << 24);
    }

    private void drawStrip(Canvas c) {
        boolean any = suggestions[0] != null || suggestions[1] != null || suggestions[2] != null;
        if (!any) return;
        float cw = getWidth() / 3f;
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(dp(16.5f));
        for (int i = 0; i < 3; i++) {
            String s = suggestions[i];
            if (s == null) continue;
            if (i == pressedSuggestion) {
                fill.setColor(theme.highlight);
                tmp.set(i * cw + dp(3), dp(5), (i + 1) * cw - dp(3), stripH - dp(5));
                c.drawRoundRect(tmp, dp(7), dp(7), fill);
            }
            text.setColor(theme.text);
            text.setTypeface(i == 1 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            CharSequence shown = TextUtils.ellipsize(s, text, cw - dp(14), TextUtils.TruncateAt.END);
            float base = stripH / 2 - (text.descent() + text.ascent()) / 2;
            c.drawText(shown, 0, shown.length(), i * cw + cw / 2, base, text);
        }
        stroke.setColor(theme.separator);
        stroke.setStrokeWidth(Math.max(1f, dp(0.8f)));
        for (int i = 1; i < 3; i++) {
            c.drawLine(i * cw, stripH * 0.27f, i * cw, stripH * 0.73f, stroke);
        }
    }

    private void drawKey(Canvas c, Key k) {
        boolean pressed = k == downKey && touchKind == KEY && !trackpad;
        if (pressed && k.type == Key.CHAR) return; // covered by the bubble or the variants panel

        int bg;
        int fg = theme.text;
        switch (k.type) {
            case Key.CHAR:
                bg = theme.key;
                break;
            case Key.SPACE:
                bg = pressed ? theme.special : theme.key;
                break;
            case Key.RETURN:
                if (returnAccent) {
                    bg = pressed ? theme.key : theme.accent;
                    fg = pressed ? theme.text : theme.accentText;
                } else {
                    bg = pressed ? theme.key : theme.special;
                }
                break;
            case Key.SHIFT:
                if (shiftState != SHIFT_OFF) {
                    bg = theme.shiftOnBg;
                    fg = theme.shiftOnFg;
                } else {
                    bg = pressed ? theme.key : theme.special;
                }
                break;
            default:
                bg = pressed ? theme.key : theme.special;
                break;
        }
        if (trackpad || labelAlpha < 1f) {
            // Trackpad: keys turn into blank tiles.
            if (k.type == Key.RETURN && returnAccent) {
                bg = blend(bg, theme.special, 1f - labelAlpha);
            }
        }

        RectF r = k.rect;
        fill.setColor(theme.shadow);
        tmp.set(r.left, r.top + dp(1), r.right, r.bottom + dp(1));
        c.drawRoundRect(tmp, radius, radius, fill);
        fill.setColor(bg);
        c.drawRoundRect(r, radius, radius, fill);

        int col = alpha(fg, labelAlpha);
        float cx = r.centerX(), cy = r.centerY();
        switch (k.type) {
            case Key.CHAR: {
                text.setTypeface(Typeface.DEFAULT);
                text.setTextSize(Math.min(dp(23), r.width() * 0.78f));
                text.setColor(col);
                drawCentered(c, labelOf(k), cx, cy - dp(1));
                break;
            }
            case Key.SHIFT:
                drawShift(c, cx, cy, col, shiftState);
                break;
            case Key.DELETE:
                drawDelete(c, cx, cy, col, pressed);
                break;
            case Key.EMOJI:
                drawSmiley(c, cx, cy, col);
                break;
            case Key.SPACE: {
                text.setTypeface(Typeface.DEFAULT);
                text.setTextSize(dp(16));
                if (flashLabel != null && flashAlpha > 0f) {
                    text.setColor(alpha(col, flashAlpha));
                    drawCentered(c, flashLabel, cx, cy);
                    text.setColor(alpha(col, 1f - flashAlpha));
                } else {
                    text.setColor(col);
                }
                drawCentered(c, fit(spaceLabel, r.width()), cx, cy);
                break;
            }
            case Key.RETURN:
            case Key.MODE:
            case Key.MORE: {
                String s = k.type == Key.RETURN ? returnLabel : k.label;
                text.setTypeface(Typeface.DEFAULT);
                text.setTextSize(dp(16));
                text.setColor(col);
                drawCentered(c, fit(s, r.width()), cx, cy);
                break;
            }
            default:
                break;
        }
    }

    private CharSequence fit(String s, float w) {
        return TextUtils.ellipsize(s, text, w - dp(6), TextUtils.TruncateAt.END);
    }

    private void drawCentered(Canvas c, CharSequence s, float cx, float cy) {
        float base = cy - (text.descent() + text.ascent()) / 2;
        c.drawText(s, 0, s.length(), cx, base, text);
    }

    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000
                | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8)
                | Math.round(ab + (bb - ab) * t);
    }

    /** Bubble shape that grows out of the key: rounded box on top, curved neck, the key itself below. */
    private void buildBubble(RectF b, RectF key) {
        float rb = dp(9);
        float r = radius;
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

    private void drawBubbleShape(Canvas c) {
        c.save();
        c.translate(0, dp(1));
        fill.setColor(alpha(theme.shadow, theme.dark ? 0.9f : 0.55f));
        c.drawPath(path, fill);
        c.restore();
        fill.setColor(theme.key);
        c.drawPath(path, fill);
    }

    private void drawPreview(Canvas c, Key k) {
        RectF kr = k.rect;
        float bw = kr.width() + dp(22);
        float bh = keyH * 1.12f;
        float neck = dp(9);
        float top = Math.max(dp(1), kr.top - neck - bh);
        float bottom = kr.top - neck;
        float left = kr.centerX() - bw / 2;
        left = Math.max(dp(1), Math.min(left, getWidth() - dp(1) - bw));
        bubble.set(left, top, left + bw, bottom);
        buildBubble(bubble, kr);
        drawBubbleShape(c);
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(Math.min(dp(34), bubble.height() * 0.8f));
        text.setColor(theme.text);
        drawCentered(c, labelOf(k), bubble.centerX(), bubble.centerY() + dp(1));
    }

    private void drawAlts(Canvas c, Key k) {
        bubble.set(altsRect);
        bubble.bottom = Math.min(altsRect.bottom, k.rect.top - dp(4));
        buildBubble(bubble, k.rect);
        drawBubbleShape(c);
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(Math.min(dp(24), altCellW * 0.8f));
        for (int i = 0; i < alts.length; i++) {
            float l = altsRect.left + altPad + i * altCellW;
            float cy = bubble.centerY();
            if (i == altSel) {
                fill.setColor(theme.accent);
                tmp.set(l + dp(1), bubble.top + dp(5), l + altCellW - dp(1), bubble.bottom - dp(5));
                c.drawRoundRect(tmp, dp(6), dp(6), fill);
                text.setColor(theme.accentText);
            } else {
                text.setColor(theme.text);
            }
            drawCentered(c, applyCase(alts[i]), l + altCellW / 2, cy);
        }
    }

    private void drawGlobe(Canvas c) {
        if (globeH <= 0) return;
        boolean pressed = downKey == globeKey && touchKind == KEY;
        float cx = globeKey.rect.centerX();
        float cy = globeTop + globeH * 0.45f;
        float r = dp(10.5f);
        if (pressed) {
            fill.setColor(theme.highlight);
            c.drawCircle(cx, cy, r + dp(8), fill);
        }
        int col = alpha(theme.text, theme.dark ? 0.85f : 0.62f);
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

    private void drawDelete(Canvas c, float cx, float cy, int color, boolean pressed) {
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
        float xc = x1 + body / 2 - dp(0.5f), xs = dp(3.3f);
        if (pressed) {
            fill.setColor(color);
            c.drawPath(path, fill);
            stroke.setColor(theme.key); // the cross is cut out of the filled icon
        } else {
            stroke.setColor(color);
            stroke.setStrokeWidth(dp(1.6f));
            c.drawPath(path, stroke);
        }
        stroke.setStrokeWidth(dp(1.6f));
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
        if (flashAnim != null) flashAnim.cancel();
        if (labelAnim != null) labelAnim.cancel();
    }
}
