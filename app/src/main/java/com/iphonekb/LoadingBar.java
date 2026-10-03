package com.iphonekb;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/**
 * Thin iOS-style progress bar. ML Kit does not report how many bytes are done, so while a pack
 * downloads the bar shows honest, continuous activity (a gliding segment), then fills on finish.
 */
final class LoadingBar extends View {
    private final float d;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private int track = 0xFFE5E5EA, color = 0xFF007AFF;
    private boolean running;

    LoadingBar(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
    }

    void setColors(int track, int color) {
        this.track = track;
        this.color = color;
        invalidate();
    }

    void setRunning(boolean on) {
        running = on;
        setVisibility(on ? VISIBLE : GONE);
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Math.round(4 * d));
    }

    @Override
    protected void onDraw(Canvas c) {
        float W = getWidth(), H = getHeight(), rad = H / 2;
        p.setColor(track);
        r.set(0, 0, W, H);
        c.drawRoundRect(r, rad, rad, p);
        if (!running) return;
        float t = (SystemClock.uptimeMillis() % 1400) / 1400f;
        float e = t * t * (3 - 2 * t);
        float seg = W * 0.32f;
        float x = -seg + e * (W + seg);
        p.setColor(color);
        r.set(Math.max(0, x), 0, Math.min(W, x + seg), H);
        if (r.width() > 0) c.drawRoundRect(r, rad, rad, p);
        postInvalidateOnAnimation();
    }
}
