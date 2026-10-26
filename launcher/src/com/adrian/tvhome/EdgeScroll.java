package com.adrian.tvhome;

import android.content.Context;
import android.graphics.Rect;
import android.widget.HorizontalScrollView;

/** Horizontal row that keeps the focused item a margin away from the screen edges. */
final class EdgeScroll extends HorizontalScrollView {
    private final int margin;

    EdgeScroll(Context c, int marginPx) {
        super(c);
        margin = marginPx;
    }

    @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        Rect r = new Rect(rect);
        r.left -= margin;
        r.right += margin;
        return super.computeScrollDeltaToGetChildRectOnScreen(r);
    }
}
