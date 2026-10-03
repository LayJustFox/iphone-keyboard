package com.iphonekb;

/** One key. Position (rect) and touch area (hit) are filled in by KeyboardView. */
final class Key {
    static final int CHAR = 0, SHIFT = 1, DELETE = 2, MODE = 3, MORE = 4,
            SPACE = 5, RETURN = 6, EMOJI = 7, GLOBE = 8;

    final int type;
    final String label;
    final String output;
    final String[] alts;
    final float width;

    /** Label in capitals for the current language (set by KeyboardView). */
    String upper;

    final android.graphics.RectF rect = new android.graphics.RectF();
    final android.graphics.RectF hit = new android.graphics.RectF();

    // Press animation: 0 = idle, 1 = fully pressed. Eased every frame toward pressTarget.
    float press, pressTarget;
    // Where the finger touched (for the "liquid" glow).
    float touchX, touchY;

    Key(int type, String label, String output, String[] alts, float width) {
        this.type = type;
        this.label = label;
        this.output = output;
        this.alts = alts;
        this.width = width;
        this.upper = label;
    }

    Key(int type, String label) {
        this(type, label, label, null, 1f);
    }

    static Key ch(String label, String output, String[] alts, float width) {
        return new Key(CHAR, label, output, alts, width);
    }

    boolean isSpecial() {
        return type != CHAR && type != SPACE;
    }
}
