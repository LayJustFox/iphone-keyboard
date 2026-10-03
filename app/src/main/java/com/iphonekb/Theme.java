package com.iphonekb;

/**
 * Colours for the classic iOS keyboard and for iOS 26 "Liquid Glass".
 * Glass keys are translucent with a bright top sheen, a light rim and a soft shadow.
 */
final class Theme {
    final boolean dark, glass;

    // Panel (keyboard background) – a vertical gradient for glass.
    final int bg, bgTop, bgBottom;
    // Letter keys / space: top, middle, bottom of the fill gradient.
    final int keyTop, keyMid, keyBottom;
    // Shift, delete, 123, emoji, return.
    final int specTop, specMid, specBottom;
    // Rim highlight (top → bottom) and shadow.
    final int rimTop, rimBottom, shadow;
    // Pressed overlay: specials light up, space dims.
    final int pressSpecial, pressSpace;
    // Bubble (letter preview) fill.
    final int bubbleTop, bubbleBottom;

    final int text, accent, accentText, shiftOnBg, shiftOnFg, separator, dim, highlight, glow;

    private Theme(boolean dark, boolean glass, int accent, int panelAlpha) {
        this.dark = dark;
        this.glass = glass;
        this.accent = accent;
        this.accentText = 0xFFFFFFFF;
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
            keyTop = 0xFCFFFFFF;
            keyMid = 0xEBFFFFFF;
            keyBottom = 0xDDFFFFFF;
            specTop = 0x9EFFFFFF;
            specMid = 0x70FFFFFF;
            specBottom = 0x5CF2F4F7;
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
    }

    static Theme create(boolean dark, boolean glass, int accent, int panelAlpha) {
        return new Theme(dark, glass, accent, Math.max(0, Math.min(255, panelAlpha)));
    }

    static Theme light() {
        return create(false, true, 0xFF007AFF, 255);
    }

    /** Solid colour behind the keys (navigation bar, panels). */
    int solidBg() {
        return glass ? (dark ? 0xFF222224 : 0xFFDEE1E6) : bg;
    }

    static Theme from(Prefs p, boolean systemDark, boolean translucent) {
        boolean dark = Prefs.THEME_DARK.equals(p.theme)
                || (Prefs.THEME_AUTO.equals(p.theme) && systemDark);
        boolean glass = Prefs.STYLE_GLASS.equals(p.style);
        int alpha = translucent ? Math.round(255 * p.glassOpacity / 100f) : 255;
        return create(dark, glass, p.accentColor(dark), alpha);
    }
}
