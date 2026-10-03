package com.iphonekb;

import android.graphics.RectF;

/** One key. Position (rect) and touch area (hit) are filled in by KeyboardView. */
final class Key {
    static final int CHAR = 0, SHIFT = 1, DELETE = 2, MODE = 3, MORE = 4,
            SPACE = 5, RETURN = 6, EMOJI = 7, GLOBE = 8;

    final int type;
    final String label;
    final String output;
    final String[] alts;
    final float width;

    final RectF rect = new RectF();
    final RectF hit = new RectF();

    Key(int type, String label, String output, String[] alts, float width) {
        this.type = type;
        this.label = label;
        this.output = output;
        this.alts = alts;
        this.width = width;
    }

    Key(int type, String label) {
        this(type, label, label, null, 1f);
    }

    static Key ch(String label, String output, String[] alts, float width) {
        return new Key(CHAR, label, output, alts, width);
    }
}
