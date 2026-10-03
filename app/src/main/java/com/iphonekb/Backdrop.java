package com.iphonekb;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;

/**
 * Moving, colourful "app content" behind the keyboard preview in settings, blurred live with the
 * chosen strength (Android 12+). Shows how the real glass looks over a changing screen.
 */
final class Backdrop extends View {
    private final float d;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private boolean dark;
    private LinearGradient bg;
    private int bgH;

    Backdrop(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
    }

    void setDark(boolean dark) {
        this.dark = dark;
        bg = null;
        invalidate();
    }

    /** @param radiusDp blur strength; 0 = sharp */
    void setBlur(int radiusDp) {
        if (Build.VERSION.SDK_INT < 31) return;
        float px = radiusDp * d;
        setRenderEffect(px > 0.5f ? RenderEffect.createBlurEffect(px, px, Shader.TileMode.CLAMP) : null);
    }

    @Override
    protected void onDraw(Canvas c) {
        int W = getWidth(), H = getHeight();
        if (bg == null || bgH != H) {
            bgH = H;
            bg = new LinearGradient(0, 0, W, H,
                    dark ? new int[]{0xFF1B2440, 0xFF3A1F4A, 0xFF10303A} : new int[]{0xFFDCEBFF, 0xFFFBE3F0, 0xFFE0F7EC},
                    null, Shader.TileMode.CLAMP);
        }
        p.setShader(bg);
        c.drawRect(0, 0, W, H, p);
        p.setShader(null);

        float t = (SystemClock.uptimeMillis() % 12000) / 12000f * (float) (2 * Math.PI);
        int[] cols = {0xFF0A84FF, 0xFFFF375F, 0xFFFFD60A, 0xFF30D158, 0xFFBF5AF2};
        for (int i = 0; i < cols.length; i++) {
            float ph = t + i * 1.3f;
            float cx = W * (0.15f + 0.7f * (0.5f + 0.5f * (float) Math.sin(ph * (1 + i * 0.15f))));
            float cy = H * (0.2f + 0.6f * (0.5f + 0.5f * (float) Math.cos(ph * 0.8f + i)));
            p.setColor((cols[i] & 0x00FFFFFF) | (dark ? 0xB0000000 : 0xC8000000));
            c.drawCircle(cx, cy, Math.min(W, H) * (0.12f + 0.04f * i), p);
        }
        // A few "message bubbles" sliding past, like a chat under the keyboard.
        float off = (SystemClock.uptimeMillis() % 6000) / 6000f * H;
        for (int i = 0; i < 6; i++) {
            float y = ((i * H / 3f) + off) % (H * 1.6f) - H * 0.3f;
            boolean right = i % 2 == 0;
            float w = W * (0.35f + 0.1f * (i % 3));
            r.set(right ? W - w - 16 * d : 16 * d, y, right ? W - 16 * d : 16 * d + w, y + 34 * d);
            p.setColor(right ? 0xFF0A84FF : (dark ? 0xFF3A3A3C : 0xFFFFFFFF));
            c.drawRoundRect(r, 17 * d, 17 * d, p);
        }
        if (getVisibility() == VISIBLE) postInvalidateOnAnimation();
    }
}
