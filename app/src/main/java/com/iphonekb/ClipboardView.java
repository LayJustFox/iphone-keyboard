package com.iphonekb;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import java.util.HashMap;
import java.util.List;

/**
 * Clipboard history panel: a scrolling grid of glass cards.
 * Tap = paste, hold = pin / delete. In search mode it sits above the keyboard
 * and filters as the user types.
 */
@SuppressLint("ViewConstructor")
final class ClipboardView extends View {

    interface Listener {
        void onClipPaste(ClipStore.Clip clip);
        void onClipClose();
        void onClipSearch();
        void onClipSearchDone();
        void onFeedback(int keyType);
    }

    private static final int H_NONE = -1, H_ABC = 1, H_SEARCH = 2, H_CLEAR = 3, H_DONE = 4;

    private final Listener listener;
    private final boolean searchMode;
    private final float density;
    private Theme theme = Theme.light();
    private ClipStore store;
    private boolean enabled = true;
    private String abcLabel = "ABC";
    private String query = "";

    private List<ClipStore.Clip> list;
    private int listVersion = -1;
    private String listQuery = "";

    private final HashMap<ClipStore.Clip, StaticLayout> layouts = new HashMap<>();
    private int layoutWidth;

    private float headerH, cardH, gap, pad;
    private int cols = 2;
    private float scroll, maxScroll;
    private final OverScroller scroller;
    private VelocityTracker vt;
    private final int slop;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();

    // touch
    private float downX, downY, lastY;
    private boolean dragging;
    private int pressHeader = H_NONE;
    private int pressIndex = -1;
    private int actionIndex = -1;       // card showing pin / delete
    private int actionPress = 0;        // 1 = pin half, 2 = delete half
    private long clearConfirmUntil;
    private boolean longFired;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (pressIndex >= 0 && !dragging) {
                actionIndex = pressIndex;
                pressIndex = -1;
                longFired = true;
                listener.onFeedback(Key.CHAR);
                invalidate();
            }
        }
    };
    private final Runnable clearTimeout = new Runnable() {
        @Override
        public void run() {
            invalidate();
        }
    };

    ClipboardView(Context context, Listener listener, boolean searchMode) {
        super(context);
        this.listener = listener;
        this.searchMode = searchMode;
        density = context.getResources().getDisplayMetrics().density;
        scroller = new OverScroller(context);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        tp.setTextSize(dp(14.5f));
        small.setTextSize(dp(11.5f));
        headerH = dp(46);
        cardH = dp(86);
        gap = dp(8);
        pad = dp(8);
    }

    private float dp(float v) {
        return v * density;
    }

    void setTheme(Theme t) {
        theme = t;
        layouts.clear();
        invalidate();
    }

    void setStore(ClipStore s) {
        store = s;
        listVersion = -1;
        invalidate();
    }

    void setEnabledHistory(boolean on) {
        enabled = on;
        invalidate();
    }

    private int bottomInset;

    /** Space taken by the navigation bar under this panel (content stays above it). */
    void setBottomInset(int px) {
        if (bottomInset != px) {
            bottomInset = px;
            requestLayout();
            invalidate();
        }
    }

    void setAbcLabel(String s) {
        abcLabel = s;
        invalidate();
    }

    void setQuery(String q) {
        query = q;
        scroll = 0;
        invalidate();
    }

    void show() {
        if (getVisibility() == VISIBLE && getAlpha() == 1f) return;
        animate().cancel();
        scroll = 0;
        actionIndex = -1;
        listVersion = -1;
        setVisibility(VISIBLE);
        setAlpha(0f);
        setTranslationY(dp(36));
        setScaleX(0.97f);
        setScaleY(0.97f);
        animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(320)
                .setInterpolator(EmojiView.EASE_OUT).start();
    }

    void hide() {
        if (getVisibility() != VISIBLE) return;
        animate().cancel();
        animate().alpha(0f).translationY(dp(36)).scaleX(0.97f).scaleY(0.97f).setDuration(200)
                .setInterpolator(EmojiView.EASE_IN)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        setVisibility(GONE);
                    }
                }).start();
    }

    void hideNow() {
        animate().cancel();
        setVisibility(GONE);
        setAlpha(1f);
        setTranslationY(0f);
        setScaleX(1f);
        setScaleY(1f);
        actionIndex = -1;
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    // ---------------------------------------------------------------- data & layout

    private List<ClipStore.Clip> items() {
        if (store == null) return java.util.Collections.emptyList();
        String q = searchMode ? query : "";
        if (list == null || listVersion != store.version() || !q.equals(listQuery)) {
            list = store.ordered(q);
            listVersion = store.version();
            listQuery = q;
            if (actionIndex >= list.size()) actionIndex = -1;
        }
        return list;
    }

    private float cardW() {
        return (getWidth() - 2 * pad - (cols - 1) * gap) / cols;
    }

    private void relayout() {
        cols = getWidth() > dp(600) ? 3 : 2;
        int n = items().size();
        int rows = (n + cols - 1) / cols;
        float content = rows * cardH + Math.max(0, rows - 1) * gap + 2 * pad;
        maxScroll = Math.max(0, content - (getHeight() - bottomInset - headerH));
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        int w = (int) (cardW() - 2 * dp(12));
        if (w != layoutWidth) {
            layoutWidth = w;
            layouts.clear();
        }
    }

    private StaticLayout layoutFor(ClipStore.Clip c) {
        StaticLayout l = layouts.get(c);
        if (l != null) return l;
        if (layouts.size() > 300) layouts.clear();
        String t = c.text.length() > 400 ? c.text.substring(0, 400) : c.text;
        tp.setColor(theme.text);
        l = StaticLayout.Builder.obtain(t, 0, t.length(), tp, Math.max(10, layoutWidth))
                .setMaxLines(3)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build();
        layouts.put(c, l);
        return l;
    }

    private float cardTop(int i) {
        return headerH + pad + (i / cols) * (cardH + gap) - scroll;
    }

    private float cardLeft(int i) {
        return pad + (i % cols) * (cardW() + gap);
    }

    private int cardAt(float x, float y) {
        if (y < headerH) return -1;
        List<ClipStore.Clip> l = items();
        float cw = cardW();
        int col = (int) ((x - pad) / (cw + gap));
        int row = (int) ((y - headerH - pad + scroll) / (cardH + gap));
        if (col < 0 || col >= cols || row < 0) return -1;
        int i = row * cols + col;
        if (i >= l.size()) return -1;
        float left = cardLeft(i), top = cardTop(i);
        if (x < left || x > left + cw || y < top || y > top + cardH) return -1;
        return i;
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas c) {
        relayout();
        drawBackground(c);
        List<ClipStore.Clip> l = items();
        int W = getWidth(), H = getHeight() - bottomInset;
        float cw = cardW();

        c.save();
        c.clipRect(0, headerH, W, H);
        if (l.isEmpty()) {
            small.setTextAlign(Paint.Align.CENTER);
            small.setColor(theme.dim);
            small.setTextSize(dp(14));
            String msg = !enabled ? "История буфера выключена в настройках"
                    : searchMode && !query.isEmpty() ? "Ничего не найдено"
                    : "Скопируйте текст — он появится здесь";
            c.drawText(msg, W / 2f, headerH + (H - headerH) / 2f, small);
            small.setTextSize(dp(11.5f));
            small.setTextAlign(Paint.Align.LEFT);
        }
        int first = Math.max(0, (int) ((scroll - pad) / (cardH + gap)) * cols);
        for (int i = first; i < l.size(); i++) {
            float top = cardTop(i);
            if (top > H) break;
            if (top + cardH < headerH) continue;
            drawCard(c, l.get(i), i, cardLeft(i), top, cw);
        }
        c.restore();
        drawHeader(c);
    }

    private void drawCard(Canvas c, ClipStore.Clip clip, int i, float left, float top, float w) {
        tmp.set(left, top, left + w, top + cardH);
        float r = dp(12);
        boolean pressed = i == pressIndex;
        int body = theme.dark ? 0x26FFFFFF : 0xF2FFFFFF;
        if (!theme.glass) body = theme.keyTop;
        fill.setColor(pressed ? blendHighlight(body) : body);
        c.drawRoundRect(tmp, r, r, fill);
        if (theme.glass) {
            stroke.setStrokeWidth(Math.max(1f, dp(0.75f)));
            stroke.setColor(theme.dark ? 0x40FFFFFF : 0xFFFFFFFF);
            c.drawRoundRect(tmp, r, r, stroke);
        }

        if (i == actionIndex) {
            drawActions(c, clip, left, top, w);
            return;
        }
        StaticLayout sl = layoutFor(clip);
        c.save();
        c.translate(left + dp(12), top + dp(10));
        sl.draw(c);
        c.restore();

        small.setColor(theme.dim);
        CharSequence when = DateUtils.getRelativeTimeSpanString(clip.time, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE);
        c.drawText(when, 0, when.length(), left + dp(12), top + cardH - dp(9), small);
        if (clip.pinned) {
            small.setTextAlign(Paint.Align.RIGHT);
            c.drawText("📌", left + w - dp(9), top + cardH - dp(9), small);
            small.setTextAlign(Paint.Align.LEFT);
        }
    }

    private int blendHighlight(int body) {
        return theme.dark ? 0x40FFFFFF : 0xFFE6E8EC;
    }

    private void drawActions(Canvas c, ClipStore.Clip clip, float left, float top, float w) {
        float half = w / 2;
        float r = dp(12);
        tmp.set(left, top, left + half, top + cardH);
        fill.setColor(actionPress == 1 ? theme.highlight : 0);
        if (actionPress == 1) c.drawRoundRect(tmp, r, r, fill);
        tmp.set(left + half, top, left + w, top + cardH);
        if (actionPress == 2) c.drawRoundRect(tmp, r, r, fill);

        tp.setTextAlign(Paint.Align.CENTER);
        tp.setColor(theme.text);
        float cy = top + cardH / 2 - (tp.descent() + tp.ascent()) / 2;
        c.drawText(clip.pinned ? "Открепить" : "📌 Закрепить", left + half / 2, cy, tp);
        tp.setColor(0xFFFF3B30);
        c.drawText("Удалить", left + half + half / 2, cy, tp);
        tp.setTextAlign(Paint.Align.LEFT);
        stroke.setColor(theme.separator);
        stroke.setStrokeWidth(Math.max(1f, dp(0.8f)));
        c.drawLine(left + half, top + dp(14), left + half, top + cardH - dp(14), stroke);
    }

    private LinearGradient bgShader;
    private int bgShaderH;
    private Theme bgShaderTheme;

    private void drawBackground(Canvas c) {
        if (bgShader == null || bgShaderH != getHeight() || bgShaderTheme != theme) {
            bgShaderH = getHeight();
            bgShaderTheme = theme;
            bgShader = new LinearGradient(0, 0, 0, Math.max(1, bgShaderH), theme.panelTop(), theme.panelBottom(),
                    Shader.TileMode.CLAMP);
        }
        // Gradients are drawn with the paint's alpha: start fully opaque every time.
        fill.setColor(0xFFFFFFFF);
        fill.setShader(bgShader);
        c.drawRect(0, 0, getWidth(), getHeight(), fill);
        fill.setShader(null);
    }

    private void drawHeader(Canvas c) {
        int W = getWidth();
        float cy = headerH / 2;
        tp.setTypeface(Typeface.DEFAULT);

        if (searchMode) {
            // Search field
            tmp.set(pad, dp(7), W - dp(96), headerH - dp(7));
            fill.setColor(theme.dark ? 0x26FFFFFF : 0xFFFFFFFF);
            c.drawRoundRect(tmp, dp(10), dp(10), fill);
            drawMagnifier(c, tmp.left + dp(16), cy, theme.dim);
            tp.setColor(query.isEmpty() ? theme.dim : theme.text);
            String shown = query.isEmpty() ? "Поиск в буфере" : query;
            CharSequence s = TextUtils.ellipsize(shown, tp, tmp.width() - dp(44), TextUtils.TruncateAt.START);
            float base = cy - (tp.descent() + tp.ascent()) / 2;
            c.drawText(s, 0, s.length(), tmp.left + dp(30), base, tp);
            // Caret after the typed text
            float x = tmp.left + dp(30) + (query.isEmpty() ? 0 : tp.measureText(s, 0, s.length())) + dp(1);
            fill.setColor(theme.accent);
            c.drawRect(x, cy - dp(9), x + dp(2), cy + dp(9), fill);
            headerButton(c, "Готово", W - dp(88), W - pad, cy, pressHeader == H_DONE, theme.accent);
            return;
        }

        headerButton(c, abcLabel, pad, pad + dp(56), cy, pressHeader == H_ABC, theme.text);

        tp.setColor(theme.text);
        tp.setTypeface(Typeface.DEFAULT_BOLD);
        String title = "Буфер обмена";
        float tx = pad + dp(68);
        float base = cy - (tp.descent() + tp.ascent()) / 2;
        c.drawText(title, tx, base, tp);
        float tw = tp.measureText(title);
        tp.setTypeface(Typeface.DEFAULT);
        small.setColor(theme.dim);
        if (store != null) c.drawText(String.valueOf(store.size()), tx + tw + dp(6), base, small);

        boolean confirm = SystemClock.uptimeMillis() < clearConfirmUntil;
        float clearW = confirm ? dp(116) : dp(86);
        headerButton(c, confirm ? "Удалить всё?" : "Очистить", W - pad - clearW, W - pad, cy,
                pressHeader == H_CLEAR, confirm ? 0xFFFF3B30 : theme.accent);
        float sx = W - pad - clearW - dp(26);
        if (pressHeader == H_SEARCH) {
            fill.setColor(theme.highlight);
            c.drawCircle(sx, cy, dp(17), fill);
        }
        drawMagnifier(c, sx, cy, theme.text);
    }

    private void headerButton(Canvas c, String label, float l, float r, float cy, boolean pressed, int color) {
        tmp.set(l, cy - dp(16), r, cy + dp(16));
        fill.setColor(pressed ? theme.highlight : (theme.dark ? 0x1FFFFFFF : 0x99FFFFFF));
        c.drawRoundRect(tmp, dp(16), dp(16), fill);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setColor(color);
        c.drawText(label, tmp.centerX(), cy - (tp.descent() + tp.ascent()) / 2, tp);
        tp.setTextAlign(Paint.Align.LEFT);
    }

    private void drawMagnifier(Canvas c, float cx, float cy, int color) {
        stroke.setColor(color);
        stroke.setStrokeWidth(dp(1.7f));
        float r = dp(6);
        c.drawCircle(cx - dp(1.5f), cy - dp(1.5f), r, stroke);
        c.drawLine(cx + dp(2.8f), cy + dp(2.8f), cx + dp(7), cy + dp(7), stroke);
    }

    private int headerAt(float x, float y) {
        if (y > headerH) return H_NONE;
        int W = getWidth();
        if (searchMode) return x > W - dp(96) ? H_DONE : H_NONE;
        if (x < pad + dp(60)) return H_ABC;
        boolean confirm = SystemClock.uptimeMillis() < clearConfirmUntil;
        float clearW = confirm ? dp(116) : dp(86);
        if (x > W - pad - clearW) return H_CLEAR;
        float sx = W - pad - clearW - dp(26);
        if (Math.abs(x - sx) < dp(22)) return H_SEARCH;
        return H_NONE;
    }

    // ---------------------------------------------------------------- touch

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scroll = Math.max(0, Math.min(maxScroll, scroller.getCurrY()));
            postInvalidateOnAnimation();
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scroller.forceFinished(true);
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                vt.addMovement(e);
                downX = x;
                downY = y;
                lastY = y;
                dragging = false;
                longFired = false;
                pressHeader = headerAt(x, y);
                pressIndex = -1;
                actionPress = 0;
                if (pressHeader == H_NONE) {
                    int i = cardAt(x, y);
                    if (actionIndex >= 0 && i == actionIndex) {
                        actionPress = x < cardLeft(i) + cardW() / 2 ? 1 : 2;
                    } else if (i >= 0) {
                        pressIndex = i;
                        handler.postDelayed(longPress, 420);
                    }
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (vt != null) vt.addMovement(e);
                if (!dragging && Math.abs(y - downY) > slop && pressHeader == H_NONE) {
                    dragging = true;
                    pressIndex = -1;
                    actionPress = 0;
                    handler.removeCallbacks(longPress);
                }
                if (dragging) {
                    scroll = Math.max(0, Math.min(maxScroll, scroll - (y - lastY)));
                    invalidate();
                }
                lastY = y;
                return true;
            case MotionEvent.ACTION_UP: {
                handler.removeCallbacks(longPress);
                if (dragging) {
                    if (vt != null) {
                        vt.computeCurrentVelocity(1000);
                        scroller.fling(0, (int) scroll, 0, (int) -vt.getYVelocity(), 0, 0, 0, (int) maxScroll);
                        postInvalidateOnAnimation();
                    }
                } else if (pressHeader != H_NONE) {
                    if (headerAt(x, y) == pressHeader) onHeader(pressHeader);
                } else if (actionPress != 0 && actionIndex >= 0) {
                    List<ClipStore.Clip> l = items();
                    if (actionIndex < l.size() && cardAt(x, y) == actionIndex) {
                        ClipStore.Clip clip = l.get(actionIndex);
                        if (actionPress == 1) store.setPinned(clip, !clip.pinned);
                        else store.remove(clip);
                        listener.onFeedback(Key.DELETE);
                    }
                    actionIndex = -1;
                } else if (!longFired && pressIndex >= 0) {
                    List<ClipStore.Clip> l = items();
                    if (actionIndex >= 0) {
                        actionIndex = -1;
                    } else if (pressIndex < l.size() && cardAt(x, y) == pressIndex) {
                        listener.onFeedback(Key.CHAR);
                        listener.onClipPaste(l.get(pressIndex));
                    }
                } else if (!longFired && actionIndex >= 0) {
                    actionIndex = -1;
                }
                pressHeader = H_NONE;
                pressIndex = -1;
                actionPress = 0;
                dragging = false;
                if (vt != null) {
                    vt.recycle();
                    vt = null;
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                pressHeader = H_NONE;
                pressIndex = -1;
                actionPress = 0;
                dragging = false;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void onHeader(int h) {
        listener.onFeedback(Key.MODE);
        switch (h) {
            case H_ABC:
                listener.onClipClose();
                break;
            case H_SEARCH:
                listener.onClipSearch();
                break;
            case H_DONE:
                listener.onClipSearchDone();
                break;
            case H_CLEAR:
                if (SystemClock.uptimeMillis() < clearConfirmUntil) {
                    clearConfirmUntil = 0;
                    if (store != null) store.clearUnpinned();
                    scroll = 0;
                } else {
                    clearConfirmUntil = SystemClock.uptimeMillis() + 3000;
                    handler.postDelayed(clearTimeout, 3010);
                }
                break;
            default:
                break;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacksAndMessages(null);
    }
}
