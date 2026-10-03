package com.iphonekb;

import android.content.res.Configuration;
import android.content.res.Resources;

/**
 * Key sizes. Presets follow real iPhone keyboards (points ≈ dp):
 * SE / mini / regular: keys 42 high, 12 between rows; Plus / Pro Max: 45 and 11.
 * "Auto" picks by screen width, handles landscape and tablets, then the user's
 * sliders are applied and the result is kept within a share of the screen height.
 */
final class Metrics {
    float stripH, topPad, keyH, vGap, hGap, side, bottomPad, globeH, radius;
    float letterSize, labelSize, bubbleTextSize;

    static Metrics compute(Prefs p, Resources res, int rows) {
        Metrics m = new Metrics();
        Configuration cfg = res.getConfiguration();
        float d = res.getDisplayMetrics().density;
        boolean land = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE;
        int wDp = cfg.screenWidthDp > 0 ? cfg.screenWidthDp : 390;
        int hDp = cfg.screenHeightDp > 0 ? cfg.screenHeightDp : 800;
        boolean tablet = cfg.smallestScreenWidthDp >= 600;

        float keyH, vGap, hGap = 6, radius = 5, strip = 46, top = 10, globe = 40;
        switch (p.sizePreset) {
            case Prefs.SIZE_SE:
            case Prefs.SIZE_IPHONE:
                keyH = 42; vGap = 12;
                break;
            case Prefs.SIZE_MAX:
                keyH = 45; vGap = 11; radius = 5.5f;
                break;
            case Prefs.SIZE_COMPACT:
                keyH = 37; vGap = 9; hGap = 5; strip = 40; top = 7; globe = 34; radius = 4.5f;
                break;
            case Prefs.SIZE_LARGE:
                keyH = 50; vGap = 12; hGap = 7; radius = 6;
                break;
            default: // auto
                if (tablet) {
                    keyH = 52; vGap = 10; hGap = 9; radius = 7; strip = 50;
                } else if (wDp >= 414) {
                    keyH = 45; vGap = 11; radius = 5.5f;
                } else {
                    keyH = 42; vGap = 12;
                }
                break;
        }
        if (land) {
            keyH *= 0.79f;
            vGap *= 0.6f;
            strip = Math.min(strip, 38);
            top = 6;
            globe = Math.min(globe, 30);
        }

        keyH *= p.keyHeightPct / 100f;
        vGap *= p.vGapPct / 100f;
        hGap *= p.hGapPct / 100f;
        radius *= p.radiusPct / 100f;
        if (!p.globeRow) globe = 0;
        float side = p.sidePadDp;
        float bottom = p.bottomPadDp;

        if (p.limitHeight) {
            // Size optimisation: keep the keyboard from eating the screen.
            float maxH = hDp * (land ? 0.62f : 0.45f);
            float fixed = strip + top + bottom + globe;
            float keys = rows * keyH + (rows - 1) * vGap;
            if (fixed + keys > maxH) {
                float f = Math.max(0.6f, (maxH - fixed) / keys);
                keyH *= f;
                vGap *= f;
            }
        }

        m.stripH = strip * d;
        m.topPad = top * d;
        m.keyH = keyH * d;
        m.vGap = vGap * d;
        m.hGap = hGap * d;
        m.radius = radius * d;
        m.side = side * d;
        m.bottomPad = bottom * d;
        m.globeH = globe * d;
        float fs = p.fontPct / 100f;
        m.letterSize = keyH * 0.545f * fs * d;
        m.labelSize = Math.min(16.5f, keyH * 0.385f) * fs * d;
        m.bubbleTextSize = keyH * 0.8f * fs * d;
        return m;
    }

    float keysTop() {
        return stripH + topPad;
    }

    float totalHeight(int rows) {
        return keysTop() + rows * keyH + (rows - 1) * vGap + bottomPad + globeH;
    }
}
