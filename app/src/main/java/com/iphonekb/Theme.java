package com.iphonekb;

/** iOS keyboard colours. */
final class Theme {
    final boolean dark;
    final int bg;          // keyboard background
    final int key;         // letter keys, space
    final int special;     // shift, delete, 123, emoji, return
    final int text;
    final int shadow;      // 1px line under every key
    final int accent;      // blue return key, selected long-press variant
    final int accentText;
    final int shiftOnBg;
    final int shiftOnFg;
    final int separator;   // suggestion bar dividers
    final int dim;         // secondary text (emoji section titles)
    final int highlight;   // pressed suggestion / selected emoji tab

    private Theme(boolean dark, int bg, int key, int special, int text, int shadow,
                  int separator, int dim, int highlight) {
        this.dark = dark;
        this.bg = bg;
        this.key = key;
        this.special = special;
        this.text = text;
        this.shadow = shadow;
        this.accent = dark ? 0xFF0A84FF : 0xFF007AFF;
        this.accentText = 0xFFFFFFFF;
        this.shiftOnBg = 0xFFFFFFFF;
        this.shiftOnFg = 0xFF000000;
        this.separator = separator;
        this.dim = dim;
        this.highlight = highlight;
    }

    static Theme light() {
        return new Theme(false,
                0xFFD1D3D9, 0xFFFFFFFF, 0xFFABB0BA, 0xFF000000, 0xFF898A8D,
                0xFFB3B6BD, 0xFF7C7F86, 0xFFBDC0C7);
    }

    static Theme dark() {
        return new Theme(true,
                0xFF2A2A2C, 0xFF6A6A6C, 0xFF454547, 0xFFFFFFFF, 0xFF101010,
                0xFF4C4C4E, 0xFF98989D, 0xFF4A4A4C);
    }
}
