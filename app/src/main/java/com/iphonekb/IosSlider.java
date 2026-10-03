package com.iphonekb;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** iOS slider: thin track, accent fill, white round knob with shadow. */
@SuppressLint("ViewConstructor")
final class IosSlider extends View {

    interface OnChange {
        void onChange(int value);
    }

    private final float d;
    private final boolean dark;
    private final int min, max;
    private int value;
    private int accent;
    private OnChange listener;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    IosSlider(Context c, boolean dark, int min, int max, int value, int accent) {
        super(c);
        this.dark = dark;
        this.min = min;
        this.max = max;
        this.value = Math.max(min, Math.min(max, value));
        this.accent = accent;
        d = c.getResources().getDisplayMetrics().density;
    }

    void setOnChange(OnChange l) {
        listener = l;
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Math.round(36 * d));
    }

    private float knobR() {
        return 13.5f * d;
    }

    private float frac() {
        return max == min ? 0 : (value - min) / (float) (max - min);
    }

    @Override
    protected void onDraw(Canvas c) {
        float kr = knobR();
        float left = kr, right = getWidth() - kr, cy = getHeight() / 2f;
        float x = left + frac() * (right - left);
        float th = 2f * d;
        p.setColor(dark ? 0xFF3A3A3C : 0xFFE5E5EA);
        r.set(left, cy - th, right, cy + th);
        c.drawRoundRect(r, th, th, p);
        p.setColor(accent);
        r.set(left, cy - th, x, cy + th);
        c.drawRoundRect(r, th, th, p);
        p.setColor(0x1F000000);
        c.drawCircle(x, cy + 1.5f * d, kr, p);
        p.setColor(0x0F000000);
        c.drawCircle(x, cy + 3f * d, kr, p);
        p.setColor(0xFFFFFFFF);
        c.drawCircle(x, cy, kr, p);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE: {
                float kr = knobR();
                float f = (e.getX() - kr) / Math.max(1f, getWidth() - 2 * kr);
                f = Math.max(0f, Math.min(1f, f));
                int v = min + Math.round(f * (max - min));
                if (v != value) {
                    value = v;
                    invalidate();
                    if (listener != null) listener.onChange(v);
                }
                return true;
            }
            default:
                return true;
        }
    }
}
