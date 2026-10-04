package com.iphonekb;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

/**
 * Translator bar above the keys (opened by holding the space bar):
 *   [Русский ▾]  ⇄  [Английский ▾]                      ✕
 *   what you type…|
 *   ─────────────────────────────────────────────
 *   translation                              [Вставить]
 * Tapping a language opens a grid to pick another one.
 */
@SuppressLint("ViewConstructor")
final class TranslatorView extends View {

    interface Listener {
        void onTransClose();
        void onTransInsert();
        void onTransOpenYandex();
        void onTransSwap();
        void onTransLang(boolean source, String code);
        void onFeedback(int keyType);
    }

    private static final int T_NONE = 0, T_SRC = 1, T_SWAP = 2, T_DST = 3, T_CLOSE = 4, T_INSERT = 5,
            T_RESULT = 6, T_PICK = 7, T_YANDEX = 8;

    private final Listener listener;
    private final float d;
    private Theme theme = Theme.light();

    private String src = "auto", dst = "en", detected;
    private String input = "", result = "", status;
    private boolean busy;
    private boolean yandexMode;
    private boolean picking, pickingSource;
    private float pickScroll, pickMax;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint rp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private final RectF srcChip = new RectF(), dstChip = new RectF(), swapBtn = new RectF(),
            closeBtn = new RectF(), insertBtn = new RectF();
    private LinearGradient bg;
    private Theme bgTheme;
    private int bgH;
    private StaticLayout resultLayout;
    private String resultLayoutText;
    private int resultLayoutW;

    private int pressed = T_NONE;
    private int pressedPick = -1;
    private float downY, lastY;
    private boolean dragging;

    TranslatorView(Context c, Listener l) {
        super(c);
        listener = l;
        d = c.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
    }

    private float dp(float v) {
        return v * d;
    }

    void setTheme(Theme t) {
        theme = t;
        resultLayout = null;
        invalidate();
    }

    void setLanguages(String source, String target) {
        src = source;
        dst = target;
        detected = null;
        invalidate();
    }

    void setInput(String s) {
        input = s;
        invalidate();
    }

    void setResult(String r, String detectedSource) {
        result = r == null ? "" : r;
        status = null;
        busy = false;
        detected = detectedSource;
        invalidate();
    }

    void setStatus(String s, boolean busy) {
        status = s;
        this.busy = busy;
        invalidate();
    }

    void reset() {
        input = "";
        result = "";
        status = null;
        detected = null;
        picking = false;
        invalidate();
    }

    String result() {
        return result;
    }

    // ---------------------------------------------------------------- drawing

    private float headerH() {
        return dp(46);
    }

    private float inputBottom() {
        return headerH() + dp(42);
    }

    @Override
    protected void onDraw(Canvas c) {
        int W = getWidth(), H = getHeight();
        if (bg == null || bgTheme != theme || bgH != H) {
            bgTheme = theme;
            bgH = H;
            bg = new LinearGradient(0, 0, 0, Math.max(1, H), theme.panelTop(), theme.panelTop(), Shader.TileMode.CLAMP);
        }
        // Gradients are drawn with the paint's alpha: start fully opaque every time.
        fill.setColor(0xFFFFFFFF);
        fill.setShader(bg);
        c.drawRect(0, 0, W, H, fill);
        fill.setShader(null);
        fill.setColor(theme.dark ? 0x26FFFFFF : 0xB3FFFFFF);
        c.drawRect(0, 0, W, Math.max(1f, dp(0.6f)), fill);

        drawHeader(c, W);
        if (picking) drawPicker(c, W, H);
        else drawBody(c, W, H);
    }

    private void drawHeader(Canvas c, int W) {
        float cy = headerH() / 2;
        tp.setTypeface(Typeface.DEFAULT);
        tp.setTextSize(dp(15));
        float closeW = dp(44);
        float avail = W - closeW - dp(16);
        float chipW = (avail - dp(44)) / 2;
        srcChip.set(dp(8), cy - dp(16), dp(8) + chipW, cy + dp(16));
        swapBtn.set(srcChip.right, cy - dp(16), srcChip.right + dp(44), cy + dp(16));
        dstChip.set(swapBtn.right, cy - dp(16), swapBtn.right + chipW, cy + dp(16));
        closeBtn.set(W - closeW - dp(4), cy - dp(18), W - dp(4), cy + dp(18));

        String srcName = "auto".equals(src)
                ? (detected != null ? "Авто · " + TranslateEngine.name(detected) : "Автоопределение")
                : TranslateEngine.name(src);
        chip(c, srcChip, srcName, pressed == T_SRC || (picking && pickingSource));
        chip(c, dstChip, TranslateEngine.name(dst), pressed == T_DST || (picking && !pickingSource));

        // ⇄
        int ic = theme.dark ? 0xFFEBEBF5 : 0xFF3C3C43;
        if (pressed == T_SWAP) {
            fill.setColor(theme.highlight);
            c.drawCircle(swapBtn.centerX(), cy, dp(16), fill);
        }
        stroke.setColor(ic);
        stroke.setStrokeWidth(dp(1.6f));
        float sx = swapBtn.centerX();
        c.drawLine(sx - dp(7), cy - dp(3.5f), sx + dp(7), cy - dp(3.5f), stroke);
        c.drawLine(sx + dp(7), cy - dp(3.5f), sx + dp(3.5f), cy - dp(7), stroke);
        c.drawLine(sx - dp(7), cy + dp(3.5f), sx + dp(7), cy + dp(3.5f), stroke);
        c.drawLine(sx - dp(7), cy + dp(3.5f), sx - dp(3.5f), cy + dp(7), stroke);

        // ✕
        if (pressed == T_CLOSE) {
            fill.setColor(theme.highlight);
            c.drawCircle(closeBtn.centerX(), cy, dp(16), fill);
        }
        float x = closeBtn.centerX(), r = dp(5.5f);
        c.drawLine(x - r, cy - r, x + r, cy + r, stroke);
        c.drawLine(x - r, cy + r, x + r, cy - r, stroke);
    }

    private void chip(Canvas c, RectF r, String text, boolean active) {
        fill.setColor(active ? theme.highlight : (theme.dark ? 0x26FFFFFF : 0xB3FFFFFF));
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
        tp.setColor(theme.text);
        tp.setTextAlign(Paint.Align.CENTER);
        CharSequence s = TextUtils.ellipsize(text + " ▾", tp, r.width() - dp(16), TextUtils.TruncateAt.MIDDLE);
        c.drawText(s, 0, s.length(), r.centerX(), r.centerY() - (tp.descent() + tp.ascent()) / 2, tp);
        tp.setTextAlign(Paint.Align.LEFT);
    }

    private void drawBody(Canvas c, int W, int H) {
        float top = headerH();
        float cy = top + dp(21);
        tp.setTextSize(dp(17));
        tp.setTypeface(Typeface.DEFAULT);
        boolean empty = input.isEmpty();
        tp.setColor(empty ? theme.dim : theme.text);
        String shown = empty ? "Печатайте — перевод появится ниже" : input;
        CharSequence s = TextUtils.ellipsize(shown, tp, W - dp(40), TextUtils.TruncateAt.START);
        float base = cy - (tp.descent() + tp.ascent()) / 2;
        c.drawText(s, 0, s.length(), dp(16), base, tp);
        float caretX = dp(16) + (empty ? 0 : tp.measureText(s, 0, s.length())) + dp(1);
        fill.setColor(theme.accentInk);
        c.drawRect(caretX, cy - dp(10), caretX + dp(2), cy + dp(10), fill);

        fill.setColor(theme.separator);
        c.drawRect(dp(16), inputBottom(), W - dp(16), inputBottom() + Math.max(1f, dp(0.5f)), fill);

        // Result
        float rt = inputBottom() + dp(8);
        boolean hasResult = !result.isEmpty();
        yandexMode = !hasResult && !input.trim().isEmpty() && !busy;
        float insertW = hasResult ? dp(96) : (yandexMode ? dp(104) : 0);
        if (hasResult || yandexMode) {
            insertBtn.set(W - insertW - dp(8), rt + dp(4), W - dp(8), rt + dp(38));
            boolean down = pressed == T_INSERT || pressed == T_YANDEX;
            int bg = yandexMode ? 0xFFFC3F1D : theme.accent;       // Yandex red for "open in Yandex"
            fill.setColor(down ? blend(bg) : bg);
            c.drawRoundRect(insertBtn, insertBtn.height() / 2, insertBtn.height() / 2, fill);
            tp.setTextSize(dp(15));
            tp.setColor(yandexMode ? 0xFFFFFFFF : theme.accentText);
            tp.setTextAlign(Paint.Align.CENTER);
            c.drawText(yandexMode ? "Яндекс ↗" : "Вставить", insertBtn.centerX(),
                    insertBtn.centerY() - (tp.descent() + tp.ascent()) / 2, tp);
            tp.setTextAlign(Paint.Align.LEFT);
        } else {
            insertBtn.setEmpty();
        }
        String msg = status != null ? status : result;
        if (msg.isEmpty()) return;
        rp.setTypeface(status != null ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
        rp.setTextSize(dp(status != null ? 14 : 17));
        rp.setColor(status != null ? theme.dim : theme.text);
        int w = (int) (W - dp(32) - insertW);
        String key = (status != null ? "s:" : "r:") + msg + theme.text;
        if (resultLayout == null || !key.equals(resultLayoutText) || w != resultLayoutW) {
            resultLayoutText = key;
            resultLayoutW = w;
            resultLayout = StaticLayout.Builder.obtain(msg, 0, msg.length(), rp, Math.max(10, w))
                    .setMaxLines(Math.max(1, (int) ((H - rt - dp(6)) / (rp.getTextSize() * 1.25f))))
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .build();
        }
        if (pressed == T_RESULT) {
            fill.setColor(theme.highlight);
            tmp.set(dp(8), rt, W - insertW - dp(12), H - dp(4));
            c.drawRoundRect(tmp, dp(10), dp(10), fill);
        }
        c.save();
        c.translate(dp(16), rt + dp(6));
        resultLayout.draw(c);
        c.restore();
        if (status != null && busy) {
            // Waiting for Yandex: a gliding activity bar.
            float by = rt + dp(6) + resultLayout.getHeight() + dp(10);
            float bw = W - dp(32);
            float bh = dp(4);
            fill.setColor(theme.dark ? 0x33FFFFFF : 0x1F000000);
            tmp.set(dp(16), by, dp(16) + bw, by + bh);
            c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
            float t = (android.os.SystemClock.uptimeMillis() % 1400) / 1400f;
            float e = t * t * (3 - 2 * t);
            float seg = bw * 0.32f;
            float x = dp(16) - seg + e * (bw + seg);
            fill.setColor(theme.accentInk);
            tmp.set(Math.max(dp(16), x), by, Math.min(dp(16) + bw, x + seg), by + bh);
            if (tmp.width() > 0) c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
            postInvalidateOnAnimation();
        }
    }

    private int blend(int c) {
        return (c & 0x00FFFFFF) | 0xB3000000;
    }

    // ---- language picker grid

    private int pickCols() {
        return getWidth() > dp(600) ? 5 : 3;
    }

    private float pickRowH() {
        return dp(40);
    }

    private int pickCount() {
        return pickingSource ? TranslateEngine.LANGS.length : TranslateEngine.LANGS.length - 1;
    }

    private String[] pickItem(int i) {
        return TranslateEngine.LANGS[pickingSource ? i : i + 1];
    }

    private void drawPicker(Canvas c, int W, int H) {
        float top = headerH();
        int cols = pickCols();
        float cw = (W - dp(16)) / cols;
        int rows = (pickCount() + cols - 1) / cols;
        pickMax = Math.max(0, rows * pickRowH() + dp(8) - (H - top));
        pickScroll = Math.max(0, Math.min(pickScroll, pickMax));
        c.save();
        c.clipRect(0, top, W, H);
        tp.setTextSize(dp(15));
        tp.setTextAlign(Paint.Align.CENTER);
        String current = pickingSource ? src : dst;
        for (int i = 0; i < pickCount(); i++) {
            float x = dp(8) + (i % cols) * cw;
            float y = top + dp(4) + (i / cols) * pickRowH() - pickScroll;
            if (y > H || y + pickRowH() < top) continue;
            String[] it = pickItem(i);
            boolean sel = it[0].equals(current);
            tmp.set(x + dp(3), y + dp(3), x + cw - dp(3), y + pickRowH() - dp(3));
            fill.setColor(sel ? theme.accent : (i == pressedPick ? theme.highlight
                    : (theme.dark ? 0x1FFFFFFF : 0x99FFFFFF)));
            c.drawRoundRect(tmp, dp(10), dp(10), fill);
            tp.setColor(sel ? theme.accentText : theme.text);
            CharSequence s = TextUtils.ellipsize(it[1], tp, cw - dp(14), TextUtils.TruncateAt.END);
            c.drawText(s, 0, s.length(), tmp.centerX(), tmp.centerY() - (tp.descent() + tp.ascent()) / 2, tp);
        }
        tp.setTextAlign(Paint.Align.LEFT);
        c.restore();
    }

    private int pickAt(float x, float y) {
        if (y < headerH()) return -1;
        int cols = pickCols();
        float cw = (getWidth() - dp(16)) / cols;
        int col = (int) ((x - dp(8)) / cw);
        int row = (int) ((y - headerH() - dp(4) + pickScroll) / pickRowH());
        if (col < 0 || col >= cols || row < 0) return -1;
        int i = row * cols + col;
        return i < pickCount() ? i : -1;
    }

    // ---------------------------------------------------------------- touch

    private int targetAt(float x, float y) {
        if (srcChip.contains(x, y)) return T_SRC;
        if (dstChip.contains(x, y)) return T_DST;
        if (swapBtn.contains(x, y)) return T_SWAP;
        if (closeBtn.contains(x, y) || (y < headerH() && x > closeBtn.left - dp(6))) return T_CLOSE;
        if (picking) return y > headerH() ? T_PICK : T_NONE;
        if (!insertBtn.isEmpty() && insertBtn.contains(x, y)) return yandexMode ? T_YANDEX : T_INSERT;
        if (y > inputBottom() && !result.isEmpty()) return T_RESULT;
        return T_NONE;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = targetAt(x, y);
                pressedPick = pressed == T_PICK ? pickAt(x, y) : -1;
                downY = lastY = y;
                dragging = false;
                if (pressed != T_NONE) listener.onFeedback(Key.MODE);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pressed == T_PICK) {
                    if (!dragging && Math.abs(y - downY) > dp(8)) {
                        dragging = true;
                        pressedPick = -1;
                    }
                    if (dragging) {
                        pickScroll = Math.max(0, Math.min(pickMax, pickScroll - (y - lastY)));
                        invalidate();
                    }
                }
                lastY = y;
                return true;
            case MotionEvent.ACTION_UP: {
                int t = pressed;
                int pp = pressedPick;
                pressed = T_NONE;
                pressedPick = -1;
                invalidate();
                if (t == T_NONE || (t != T_PICK && targetAt(x, y) != t)) return true;
                switch (t) {
                    case T_SRC:
                        picking = !(picking && pickingSource);
                        pickingSource = true;
                        pickScroll = 0;
                        break;
                    case T_DST:
                        picking = !(picking && !pickingSource);
                        pickingSource = false;
                        pickScroll = 0;
                        break;
                    case T_SWAP:
                        picking = false;
                        listener.onTransSwap();
                        break;
                    case T_CLOSE:
                        if (picking) picking = false;
                        else listener.onTransClose();
                        break;
                    case T_INSERT:
                    case T_RESULT:
                        listener.onTransInsert();
                        break;
                    case T_YANDEX:
                        listener.onTransOpenYandex();
                        break;
                    case T_PICK:
                        if (!dragging && pp >= 0 && pp == pickAt(x, y)) {
                            picking = false;
                            listener.onTransLang(pickingSource, pickItem(pp)[0]);
                        }
                        break;
                    default:
                        break;
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                pressed = T_NONE;
                pressedPick = -1;
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
