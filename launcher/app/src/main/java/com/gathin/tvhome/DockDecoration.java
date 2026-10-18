package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Draws a tvOS style pane of glass behind all the app tiles, with a hairline under the first (dock)
 * row, and adds a gap below the dock row. Rows scrolled off screen stretch the pane past the edge
 * instead of showing a rounded end mid-screen.
 *
 * The glass is a translucent white wash brightest at the top, with an edge that catches light along
 * the top: the ambient glow shows through it. No live blur: what sits behind it (black and a soft
 * radial glow) is already blurry, and blur would cost too much on a 1 GB TV.
 */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint divider = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float shaderTop = Float.NaN, shaderBottom = Float.NaN;

    DockDecoration(int dockSize, float density) {
        this.dockSize = dockSize;
        this.density = density;
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        divider.setColor(0x1FFFFFFF);
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
        // Gradients are sized to what is on screen, so the sheen stays at the visible top of the pane.
        float sheenTop = Math.max(rect.top, 0f);
        float sheenBottom = Math.min(rect.bottom, parent.getHeight());
        if (sheenTop != shaderTop || sheenBottom != shaderBottom) {
            shaderTop = sheenTop;
            shaderBottom = sheenBottom;
            band.setShader(new LinearGradient(0, sheenTop, 0, sheenBottom,
                    new int[]{0x2EFFFFFF, 0x17FFFFFF, 0x0FFFFFFF}, new float[]{0f, 0.35f, 1f},
                    Shader.TileMode.CLAMP));
            edge.setShader(new LinearGradient(0, sheenTop, 0, sheenBottom,
                    new int[]{0x66FFFFFF, 0x1FFFFFFF, 0x14FFFFFF}, new float[]{0f, 0.3f, 1f},
                    Shader.TileMode.CLAMP));
        }
        c.drawRoundRect(rect, radius, radius, band);
        c.drawRoundRect(rect, radius, radius, edge);

        if (!Float.isNaN(dockBottom) && !Float.isNaN(gridTop)) {
            float y = (dockBottom + gridTop) / 2f;
            c.drawRect(left + pad, y, right - pad, y + density, divider);
        }
    }
}
