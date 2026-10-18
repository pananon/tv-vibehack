package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Draws the tvOS style lighter band behind all the app tiles, with a hairline under the first (dock)
 * row, and adds a gap below the dock row. Rows scrolled off screen stretch the band past the edge
 * instead of showing a rounded end mid-screen.
 */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint divider = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    DockDecoration(int dockSize, float density) {
        this.dockSize = dockSize;
        this.density = density;
        band.setColor(0xFF141416);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        edge.setColor(0x1FFFFFFF);
        divider.setColor(0x1AFFFFFF);
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && pos < dockSize) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (12 * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        int count = state.getItemCount();
        if (count == 0) return;
        int lastRowStart = ((count - 1) / dockSize) * dockSize;
        float pad = 14 * density;
        float left = Float.MAX_VALUE, right = -Float.MAX_VALUE;
        float top = Float.NaN, bottom = Float.NaN;
        float dockBottom = Float.NaN, gridTop = Float.NaN;

        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0) continue;
            View card = child.findViewById(R.id.card);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float b = t + card.getHeight();
            left = Math.min(left, l);
            right = Math.max(right, l + card.getWidth());
            if (pos == 0) top = t;
            if (pos >= lastRowStart) bottom = Float.isNaN(bottom) ? b : Math.max(bottom, b);
            if (pos < dockSize) dockBottom = Float.isNaN(dockBottom) ? b : Math.max(dockBottom, b);
            else if (pos < 2 * dockSize) gridTop = Float.isNaN(gridTop) ? t : Math.min(gridTop, t);
        }
        if (left > right) return;

        // A row that isn't on screen means the band continues past that edge.
        float radius = 26 * density;
        rect.set(left - pad,
                Float.isNaN(top) ? -radius * 2 : top - pad,
                right + pad,
                Float.isNaN(bottom) ? parent.getHeight() + radius * 2 : bottom + pad);
        c.drawRoundRect(rect, radius, radius, band);
        c.drawRoundRect(rect, radius, radius, edge);

        if (!Float.isNaN(dockBottom) && !Float.isNaN(gridTop)) {
            float y = (dockBottom + gridTop) / 2f;
            c.drawRect(left + pad, y, right - pad, y + density, divider);
        }
    }
}
