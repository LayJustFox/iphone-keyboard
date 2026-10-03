package com.iphonekb;

/** A full keyboard page: rows of keys; the last row is the bottom (123 / emoji / space / return) row. */
final class Layout {
    /** How many letter keys fit across the widest row; sets the key width. */
    final int cols;
    final Key[][] rows;

    Layout(int cols, Key[][] rows) {
        this.cols = cols;
        this.rows = rows;
    }
}
