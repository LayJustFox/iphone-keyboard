package com.iphonekb;

/**
 * Colours for the classic iOS keyboard and for iOS 26 "Liquid Glass".
 * Glass keys are translucent with a bright top sheen, a light rim and a soft shadow.
 */
final class Theme {
    final boolean dark, glass;
    /** The window is see-through with a live blur behind it. */
    final boolean live;

    // Panel (keyboard background) – a vertical gradient for glass.
    final int bg, bgTop, bgBottom;
    // Letter keys / space: top, middle, bottom of the fill gradient.
    int keyTop, keyMid, keyBottom;
    // Shift, delete, 123, emoji, return.
    int specTop, specMid, specBottom;
    // Rim highlight (top → bottom) and shadow.
    final int rimTop, rimBottom, shadow;
    // Pressed overlay: specials light up, space dims.
    final int pressSpecial, pressSpace;
    // Bubble (letter preview) fill.
    final int bubbleTop, bubbleBottom;

    final int text, accent, accentText, shiftOnBg, shiftOnFg, separator, dim, highlight, glow;
    /** Accent used for text and thin marks: readable even when the accent is white. */
    final int accentInk;

    private Theme(boolean dark, boolean glass, int accent, int panelAlpha, boolean live, float keyAlpha) {
        this.dark = dark;
        this.glass = glass;
        this.live = live;
        this.accent = accent;
        this.accentText = isLight(accent) ? 0xFF000000 : 0xFFFFFFFF;
        this.accentInk = isLight(accent) && !dark ? 0xFF3A3A3C : accent;
        this.shiftOnBg = 0xFFFFFFFF;
        this.shiftOnFg = 0xFF000000;
        this.text = dark ? 0xFFFFFFFF : 0xFF000000;

        if (!glass) {
            if (!dark) {
                bg = bgTop = bgBottom = 0xFFD1D3D9;
                keyTop = keyMid = keyBottom = 0xFFFFFFFF;
                specTop = specMid = specBottom = 0xFFABB0BA;
                shadow = 0xFF898A8D;
                pressSpecial = 0xFFFFFFFF;
                pressSpace = 0xFFABB0BA;
                bubbleTop = bubbleBottom = 0xFFFFFFFF;
                separator = 0xFFB3B6BD;
                dim = 0xFF7C7F86;
                highlight = 0xFFBDC0C7;
            } else {
                bg = bgTop = bgBottom = 0xFF2A2A2C;
                keyTop = keyMid = keyBottom = 0xFF6A6A6C;
                specTop = specMid = specBottom = 0xFF454547;
                shadow = 0xFF101010;
                pressSpecial = 0xFF6A6A6C;
                pressSpace = 0xFF454547;
                bubbleTop = bubbleBottom = 0xFF6A6A6C;
                separator = 0xFF4C4C4E;
                dim = 0xFF98989D;
                highlight = 0xFF4A4A4C;
            }
            rimTop = rimBottom = 0;
            glow = 0;
        } else if (!dark) {
            int a = panelAlpha << 24;
            bg = 0xFFE3E6EB;
            bgTop = a | 0xEEF0F4;
            bgBottom = a | 0xD9DDE3;
            keyTop = live ? 0xE8FFFFFF : 0xFCFFFFFF;
            keyMid = live ? 0xCCFFFFFF : 0xEBFFFFFF;
            keyBottom = live ? 0xBDFFFFFF : 0xDDFFFFFF;
            specTop = live ? 0x80FFFFFF : 0x9EFFFFFF;
            specMid = live ? 0x52FFFFFF : 0x70FFFFFF;
            specBottom = live ? 0x40F2F4F7 : 0x5CF2F4F7;
            rimTop = 0xFFFFFFFF;
            rimBottom = 0x33FFFFFF;
            shadow = 0x2E1B2433;
            pressSpecial = 0xC8FFFFFF;
            pressSpace = 0x24203040;
            bubbleTop = 0xFFFFFFFF;
            bubbleBottom = 0xFFF4F6F9;
            separator = 0x33000000;
            dim = 0xFF6E727A;
            highlight = 0x29000000;
            glow = 0xFFFFFFFF;
        } else {
            int a = panelAlpha << 24;
            bg = 0xFF242426;
            bgTop = a | 0x2E2E31;
            bgBottom = a | 0x1B1B1D;
            keyTop = 0x5CFFFFFF;
            keyMid = 0x3DFFFFFF;
            keyBottom = 0x30FFFFFF;
            specTop = 0x33FFFFFF;
            specMid = 0x1FFFFFFF;
            specBottom = 0x17FFFFFF;
            rimTop = 0x8CFFFFFF;
            rimBottom = 0x12FFFFFF;
            shadow = 0x73000000;
            pressSpecial = 0x4DFFFFFF;
            pressSpace = 0x40000000;
            bubbleTop = 0xFF6E6E73;
            bubbleBottom = 0xFF59595E;
            separator = 0x40FFFFFF;
            dim = 0xFF9A9AA0;
            highlight = 0x29FFFFFF;
            glow = 0xFFFFFFFF;
        }
        if (keyAlpha < 1f) {
            keyTop = scaleAlpha(keyTop, keyAlpha);
            keyMid = scaleAlpha(keyMid, keyAlpha);
            keyBottom = scaleAlpha(keyBottom, keyAlpha);
            specTop = scaleAlpha(specTop, keyAlpha);
            specMid = scaleAlpha(specMid, keyAlpha);
            specBottom = scaleAlpha(specBottom, keyAlpha);
        }
    }

    /** True for very light colours (white accent): text on them must be dark. */
    static boolean isLight(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return (0.299 * r + 0.587 * g + 0.114 * b) > 200;
    }

    private static int scaleAlpha(int c, float f) {
        int a = Math.round(((c >>> 24) & 0xFF) * f);
        return (c & 0x00FFFFFF) | (a << 24);
    }

    static Theme create(boolean dark, boolean glass, int accent, int panelAlpha, boolean live, float keyAlpha) {
        return new Theme(dark, glass, accent, Math.max(0, Math.min(255, panelAlpha)), live, keyAlpha);
    }

    static Theme light() {
        return create(false, true, 0xFF007AFF, 255, false, 1f);
    }

    /** Panel colour for overlays (emoji, clipboard, translator): see-through on live glass. */
    int panelTop() {
        return glass ? bgTop : bg;
    }

    int panelBottom() {
        return glass ? bgBottom : bg;
    }

    /** Solid colour behind the keys (navigation bar, panels). */
    int solidBg() {
        return glass ? (dark ? 0xFF222224 : 0xFFDEE1E6) : bg;
    }

    static Theme from(Prefs p, boolean systemDark, boolean translucent) {
        boolean dark = Prefs.THEME_DARK.equals(p.theme)
                || (Prefs.THEME_AUTO.equals(p.theme) && systemDark);
        boolean glass = Prefs.STYLE_GLASS.equals(p.style);
        // Live glass: the tint gets denser as "transparency" goes down; never fully clear.
        int alpha = translucent ? Math.round(255 * (0.08f + 0.92f * p.glassOpacity / 100f)) : 255;
        float keyAlpha = 1f - Math.max(0, Math.min(80, p.keyTransparency)) / 100f;
        return create(dark, glass, p.accentColor(dark), alpha, translucent && glass, keyAlpha);
    }
}
