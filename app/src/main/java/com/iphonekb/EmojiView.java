package com.iphonekb;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.PathInterpolator;
import android.widget.OverScroller;

/**
 * iOS-style emoji panel: one long horizontal strip of 5-row columns, section titles on top,
 * category tabs at the bottom with ABC on the left and delete on the right.
 */
@SuppressLint("ViewConstructor")
final class EmojiView extends View {

    interface Listener {
        void onEmojiPicked(String emoji);
        void onEmojiDelete();
        void onEmojiClose();
        void onFeedback(int keyType);
    }

    private static final int ROWS = 5;
    private static final int BAR_NONE = -100, BAR_ABC = -2, BAR_DELETE = -3;

    private final Listener listener;
    private final float density;
    private Theme theme = Theme.light();
    private String abcLabel = "ABC";

    private String[] titles = new String[0];
    private String[] icons = new String[0];
    private String[][] items = new String[0][];
    private float[] secX = new float[0];
    private float contentW, pos, maxPos;

    private float titleH, barH, cellW, cellH, gridTop, padX, sectionGap, sideZone;

    private final OverScroller scroller;
    private VelocityTracker vt;
    private final int slop;

    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint ep = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private final Path path = new Path();

    private float downX, lastX;
    private boolean dragging;
    private int pressSec = -1, pressIdx = -1, pressBar = BAR_NONE;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable repeatDelete = new Runnable() {
        @Override
        public void run() {
            if (pressBar != BAR_DELETE) return;
            listener.onEmojiDelete();
            handler.postDelayed(this, 80);
        }
    };

    EmojiView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        density = context.getResources().getDisplayMetrics().density;
        scroller = new OverScroller(context);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        tp.setTextAlign(Paint.Align.LEFT);
        ep.setTextAlign(Paint.Align.CENTER);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
    }

    private float dp(float v) {
        return v * density;
    }

    private LinearGradient panelShader;
    private int shaderH;

    /** iOS-like ease-out curves for opening and closing. */
    static final PathInterpolator EASE_OUT = new PathInterpolator(0.32f, 0.72f, 0f, 1f);
    static final PathInterpolator EASE_IN = new PathInterpolator(0.4f, 0f, 0.8f, 0.6f);

    void setTheme(Theme t) {
        theme = t;
        panelShader = null;
        invalidate();
    }

    private void drawBackground(Canvas c) {
        if (!theme.glass) {
            c.drawColor(theme.bg);
            return;
        }
        if (panelShader == null || shaderH != getHeight()) {
            shaderH = getHeight();
            panelShader = new LinearGradient(0, 0, 0, Math.max(1, shaderH),
                    theme.panelTop(), theme.panelBottom(), Shader.TileMode.CLAMP);
        }
        // Gradients are drawn with the paint's alpha: start fully opaque every time.
        fill.setColor(0xFFFFFFFF);
        fill.setShader(panelShader);
        c.drawRect(0, 0, getWidth(), getHeight(), fill);
        fill.setShader(null);
        fill.setColor(theme.dark ? 0x26FFFFFF : 0xB3FFFFFF);
        c.drawRect(0, 0, getWidth(), Math.max(1f, dp(0.6f)), fill);
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

    void setData(String[] titles, String[] icons, String[][] items) {
        this.titles = titles;
        this.icons = icons;
        this.items = items;
        relayout();
        invalidate();
    }

    void show() {
        if (getVisibility() == VISIBLE && getAlpha() == 1f) return;
        animate().cancel();
        setVisibility(VISIBLE);
        setAlpha(0f);
        // Settles in like an iOS sheet: from slightly larger and lower, fading in.
        setPivotX(getWidth() / 2f);
        setPivotY(getHeight());
        setTranslationY(dp(18));
        setScaleX(1.06f);
        setScaleY(1.06f);
        animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(460)
                .setInterpolator(EmojiView.EASE_OUT).start();
    }

    void hide() {
        if (getVisibility() != VISIBLE) return;
        animate().cancel();
        animate().alpha(0f).translationY(dp(14)).scaleX(1.04f).scaleY(1.04f).setDuration(300)
                .setInterpolator(EmojiView.EASE_OUT)
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
        handler.removeCallbacks(repeatDelete);
        pressBar = BAR_NONE;
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        relayout();
    }

    private void relayout() {
        int W = getWidth(), H = getHeight() - bottomInset;
        if (W == 0 || H <= 0) return;
        titleH = dp(30);
        barH = Math.min(dp(44), H * 0.17f);
        gridTop = titleH;
        cellH = (H - titleH - barH) / ROWS;
        cellW = Math.min(cellH * 1.12f, W / 7.6f);
        padX = dp(10);
        sectionGap = dp(14);
        sideZone = Math.min(dp(58), W / 7f);
        secX = new float[items.length];
        float x = padX;
        for (int s = 0; s < items.length; s++) {
            secX[s] = x;
            int cols = (items[s].length + ROWS - 1) / ROWS;
            x += Math.max(1, cols) * cellW + sectionGap;
        }
        contentW = x - sectionGap + padX;
        maxPos = Math.max(0, contentW - W);
        pos = Math.max(0, Math.min(pos, maxPos));
    }

    private int currentSection() {
        int cur = 0;
        for (int s = 0; s < secX.length; s++) {
            if (secX[s] - pos <= getWidth() * 0.35f) cur = s;
        }
        if (pos >= maxPos - 1 && secX.length > 0) {
            // At the very end the last (short) section may never reach the left side.
            cur = Math.max(cur, secX.length - 1);
        }
        return cur;
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas c) {
        drawBackground(c);
        int W = getWidth();
        if (secX.length != items.length) relayout();

        // Section titles that stick to the left edge until pushed off by the next one.
        tp.setTypeface(Typeface.DEFAULT_BOLD);
        tp.setTextSize(dp(12));
        tp.setColor(theme.dim);
        for (int s = 0; s < items.length; s++) {
            float tx = secX[s] - pos;
            float tw = tp.measureText(titles[s]);
            if (tx < padX) tx = padX;
            if (s + 1 < items.length) {
                float next = secX[s + 1] - pos;
                if (tx + tw + dp(16) > next) tx = next - tw - dp(16);
            }
            if (tx + tw < 0 || tx > W) continue;
            c.drawText(titles[s], tx, titleH * 0.68f, tp);
        }

        // Emoji grid
        ep.setTextSize(Math.min(dp(32), cellH * 0.66f));
        float base = -(ep.descent() + ep.ascent()) / 2;
        for (int s = 0; s < items.length; s++) {
            String[] list = items[s];
            for (int i = 0; i < list.length; i++) {
                float x = secX[s] + (i / ROWS) * cellW - pos;
                if (x + cellW < 0) continue;
                if (x > W) break;
                float y = gridTop + (i % ROWS) * cellH;
                if (s == pressSec && i == pressIdx) {
                    fill.setColor(theme.highlight);
                    tmp.set(x + dp(2), y + dp(2), x + cellW - dp(2), y + cellH - dp(2));
                    c.drawRoundRect(tmp, dp(8), dp(8), fill);
                }
                c.drawText(list[i], x + cellW / 2, y + cellH / 2 + base, ep);
            }
        }

        drawBar(c);
    }

    private void drawBar(Canvas c) {
        int W = getWidth(), H = getHeight() - bottomInset;
        float top = H - barH;
        float cy = top + barH / 2;

        // ABC
        tp.setTypeface(Typeface.DEFAULT);
        tp.setTextSize(dp(15));
        tp.setColor(theme.text);
        tp.setTextAlign(Paint.Align.CENTER);
        if (pressBar == BAR_ABC) {
            fill.setColor(theme.highlight);
            tmp.set(dp(4), top + dp(5), sideZone - dp(4), H - dp(5));
            c.drawRoundRect(tmp, dp(7), dp(7), fill);
        }
        c.drawText(abcLabel, sideZone / 2, cy - (tp.descent() + tp.ascent()) / 2, tp);
        tp.setTextAlign(Paint.Align.LEFT);

        // Category tabs
        int n = icons.length;
        if (n > 0) {
            float tabW = (W - 2 * sideZone) / n;
            int cur = currentSection();
            ep.setTextSize(Math.min(dp(19), tabW * 0.62f));
            float base = -(ep.descent() + ep.ascent()) / 2;
            for (int i = 0; i < n; i++) {
                float cx = sideZone + tabW * i + tabW / 2;
                if (i == cur) {
                    fill.setColor(theme.highlight);
                    c.drawCircle(cx, cy, Math.min(tabW / 2 - dp(1), dp(15)), fill);
                }
                ep.setAlpha(i == cur ? 255 : 120);
                c.drawText(icons[i], cx, cy + base, ep);
            }
            ep.setAlpha(255);
        }

        // Delete
        float dcx = W - sideZone / 2;
        if (pressBar == BAR_DELETE) {
            fill.setColor(theme.highlight);
            tmp.set(W - sideZone + dp(4), top + dp(5), W - dp(4), H - dp(5));
            c.drawRoundRect(tmp, dp(7), dp(7), fill);
        }
        drawDelete(c, dcx, cy, theme.text);
    }

    private void drawDelete(Canvas c, float cx, float cy, int color) {
        float h = dp(8), body = dp(14), tip = dp(7), rr = dp(2.5f);
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
        float xc = x1 + body / 2 - dp(0.5f), xs = dp(3.1f);
        c.drawLine(xc - xs, cy - xs, xc + xs, cy + xs, stroke);
        c.drawLine(xc - xs, cy + xs, xc + xs, cy - xs, stroke);
    }

    // ---------------------------------------------------------------- touch

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            pos = Math.max(0, Math.min(maxPos, scroller.getCurrX()));
            postInvalidateOnAnimation();
        }
    }

    private boolean hitGrid(float x, float y) {
        pressSec = -1;
        pressIdx = -1;
        if (y < gridTop || y >= gridTop + ROWS * cellH) return false;
        float cx = x + pos;
        for (int s = 0; s < items.length; s++) {
            float local = cx - secX[s];
            int cols = (items[s].length + ROWS - 1) / ROWS;
            if (local < 0 || local >= cols * cellW) continue;
            int idx = (int) (local / cellW) * ROWS + (int) ((y - gridTop) / cellH);
            if (idx < items[s].length) {
                pressSec = s;
                pressIdx = idx;
                return true;
            }
        }
        return false;
    }

    private int hitBar(float x, float y) {
        int W = getWidth();
        if (y < getHeight() - bottomInset - barH || y > getHeight() - bottomInset) return BAR_NONE;
        if (x < sideZone) return BAR_ABC;
        if (x > W - sideZone) return BAR_DELETE;
        if (icons.length == 0) return BAR_NONE;
        float tabW = (W - 2 * sideZone) / icons.length;
        return Math.max(0, Math.min(icons.length - 1, (int) ((x - sideZone) / tabW)));
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                scroller.forceFinished(true);
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                vt.addMovement(e);
                downX = x;
                lastX = x;
                dragging = false;
                pressBar = hitBar(x, y);
                if (pressBar == BAR_NONE) {
                    hitGrid(x, y);
                } else {
                    pressSec = -1;
                    pressIdx = -1;
                    listener.onFeedback(pressBar == BAR_DELETE ? Key.DELETE : Key.MODE);
                    if (pressBar == BAR_DELETE) {
                        listener.onEmojiDelete();
                        handler.postDelayed(repeatDelete, 420);
                    }
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (vt != null) vt.addMovement(e);
                if (pressBar != BAR_NONE) return true;
                if (!dragging && Math.abs(x - downX) > slop) {
                    dragging = true;
                    pressSec = -1;
                    pressIdx = -1;
                }
                if (dragging) {
                    pos = Math.max(0, Math.min(maxPos, pos - (x - lastX)));
                    invalidate();
                }
                lastX = x;
                return true;
            }
            case MotionEvent.ACTION_UP: {
                handler.removeCallbacks(repeatDelete);
                if (dragging) {
                    if (vt != null) {
                        vt.addMovement(e);
                        vt.computeCurrentVelocity(1000);
                        scroller.fling((int) pos, 0, (int) -vt.getXVelocity(), 0, 0, (int) maxPos, 0, 0);
                        postInvalidateOnAnimation();
                    }
                } else if (pressBar == BAR_ABC) {
                    if (hitBar(x, y) == BAR_ABC) listener.onEmojiClose();
                } else if (pressBar >= 0) {
                    int tab = hitBar(x, y);
                    if (tab >= 0 && tab < secX.length) {
                        float target = Math.max(0, Math.min(maxPos, secX[tab] - padX));
                        scroller.forceFinished(true);
                        scroller.startScroll((int) pos, 0, (int) (target - pos), 0, 450);
                        postInvalidateOnAnimation();
                    }
                } else if (pressSec >= 0 && pressIdx >= 0) {
                    String em = items[pressSec][pressIdx];
                    listener.onFeedback(Key.CHAR);
                    listener.onEmojiPicked(em);
                }
                pressBar = BAR_NONE;
                pressSec = -1;
                pressIdx = -1;
                dragging = false;
                if (vt != null) {
                    vt.recycle();
                    vt = null;
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                handler.removeCallbacks(repeatDelete);
                pressBar = BAR_NONE;
                pressSec = -1;
                pressIdx = -1;
                dragging = false;
                if (vt != null) {
                    vt.recycle();
                    vt = null;
                }
                invalidate();
                return true;
            }
            default:
                return true;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacksAndMessages(null);
    }
}
