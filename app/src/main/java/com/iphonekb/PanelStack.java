package com.iphonekb;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Holds the keyboard (first child) and the panels that open over it (emoji, clipboard).
 * The keyboard decides the height; every panel gets exactly the same size, so a panel can
 * never stretch the keyboard window to the whole screen.
 */
final class PanelStack extends FrameLayout {

    PanelStack(Context c) {
        super(c);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        View base = getChildAt(0);
        base.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int h = base.getMeasuredHeight();
        int exactW = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
        int exactH = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
        for (int i = 1; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() != GONE) c.measure(exactW, exactH);
        }
        setMeasuredDimension(w, h);
    }
}
