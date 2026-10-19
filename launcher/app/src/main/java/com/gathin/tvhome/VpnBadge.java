package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;

/**
 * The green "VPN" capsule drawn inside the network pill while a VPN is up: a lock and the word VPN, dark on
 * green, so it reads on both the frosted and the focused (white) pill.
 */
final class VpnBadge extends ReplacementSpan {

    private static final int GREEN = 0xFF30D158;
    private static final int INK = 0xFF06290F;

    private final Drawable lock;
    private final float density;
    private final RectF rect = new RectF();

    VpnBadge(Drawable lock, float density) {
        this.lock = lock.mutate();
        this.lock.setTint(INK);
        this.density = density;
    }

    private float textSize(Paint p) {
        return p.getTextSize() * 0.78f;
    }

    private float lockSize(Paint p) {
        return p.getTextSize() * 0.8f;
    }

    @Override
    public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        float old = paint.getTextSize();
        paint.setTextSize(textSize(paint));
        float w = paint.measureText(text, start, end);
        paint.setTextSize(old);
        // margin + left pad + lock + gap + text + right pad
        return (int) (8 * density + 7 * density + lockSize(paint) + 4 * density + w + 9 * density);
    }

    @Override
    public void draw(@NonNull Canvas c, CharSequence text, int start, int end, float x, int top, int y, int bottom,
                     @NonNull Paint paint) {
        float base = paint.getTextSize();
        float lockPx = lockSize(paint);
        float h = base * 1.3f;
        float cy = (top + bottom) / 2f;
        float left = x + 8 * density;
        float right = x + getSize(paint, text, start, end, null);
        rect.set(left, cy - h / 2f, right, cy + h / 2f);

        int oldColor = paint.getColor();
        paint.setColor(GREEN);
        c.drawRoundRect(rect, h / 2f, h / 2f, paint);

        int lx = (int) (left + 7 * density);
        lock.setBounds(lx, (int) (cy - lockPx / 2f), (int) (lx + lockPx), (int) (cy + lockPx / 2f));
        lock.draw(c);

        paint.setColor(INK);
        paint.setTextSize(textSize(paint));
        Paint.FontMetrics fm = paint.getFontMetrics();
        float ty = cy - (fm.ascent + fm.descent) / 2f;
        c.drawText(text, start, end, lx + lockPx + 4 * density, ty, paint);
        paint.setTextSize(base);
        paint.setColor(oldColor);
    }
}
