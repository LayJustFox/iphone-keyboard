package com.iphonekb;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** iOS toggle: 51×31 pill, green when on, white knob with a soft shadow, animated. */
@SuppressLint("ViewConstructor")
final class IosSwitch extends View {

    interface OnChange {
        void onChange(boolean on);
    }

    private final float d;
    private final boolean dark;
    private boolean checked;
    private float pos;
    private ValueAnimator anim;
    private OnChange listener;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    IosSwitch(Context c, boolean dark) {
        super(c);
        this.dark = dark;
        d = c.getResources().getDisplayMetrics().density;
    }

    void setChecked(boolean on) {
        checked = on;
        pos = on ? 1f : 0f;
        invalidate();
    }

    void setOnChange(OnChange l) {
        listener = l;
    }

    void toggle() {
        checked = !checked;
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(pos, checked ? 1f : 0f);
        anim.setDuration(300);
        anim.setInterpolator(EmojiView.EASE_OUT);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                pos = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        if (listener != null) listener.onChange(checked);
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(Math.round(51 * d), Math.round(31 * d));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        int off = dark ? 0xFF39393D : 0xFFE9E9EA;
        int on = dark ? 0xFF30D158 : 0xFF34C759;
        p.setColor(blend(off, on, pos));
        r.set(0, 0, w, h);
        c.drawRoundRect(r, h / 2, h / 2, p);
        float kr = h / 2 - 2 * d;
        float cx = 2 * d + kr + pos * (w - 4 * d - 2 * kr);
        p.setColor(0x26000000);
        c.drawCircle(cx, h / 2 + 1.5f * d, kr, p);
        p.setColor(0xFFFFFFFF);
        c.drawCircle(cx, h / 2, kr, p);
    }

    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP) toggle();
        return true;
    }
}
