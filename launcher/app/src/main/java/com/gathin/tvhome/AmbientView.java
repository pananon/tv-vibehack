package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * Ambient mode: three soft blobs of light drifting slowly over black, their colours turning through
 * the hue wheel every few minutes and adding where they overlap. Mood lighting for when the TV is on
 * in the background. Redraws at about 20 fps, which is plenty for motion this slow.
 */
public class AmbientView extends View {

    private static final long FRAME_MS = 50;
    private static final float HUE_PERIOD_S = 360f;   // one trip round the colour wheel: 6 minutes

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] hsv = new float[3];
    private final long start = SystemClock.uptimeMillis();
    /** Per blob: hue offset, drift speeds and phases, size. */
    private static final float[][] BLOBS = {
            {0f, 0.050f, 0.037f, 0.0f, 1.3f, 0.62f},
            {120f, 0.031f, 0.046f, 2.1f, 0.4f, 0.55f},
            {240f, 0.042f, 0.027f, 4.2f, 2.6f, 0.70f},
    };

    private final Runnable frame = this::invalidate;

    public AmbientView(Context ctx) {
        super(ctx);
        setBackgroundColor(Color.BLACK);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float t = (SystemClock.uptimeMillis() - start) / 1000f;
        float baseHue = (t / HUE_PERIOD_S * 360f) % 360f;
        for (float[] b : BLOBS) {
            float x = w * (0.5f + 0.38f * (float) Math.sin(t * b[1] + b[3]));
            float y = h * (0.5f + 0.34f * (float) Math.cos(t * b[2] + b[4]));
            float r = Math.max(w, h) * b[5];
            hsv[0] = (baseHue + b[0]) % 360f;
            hsv[1] = 0.85f;
            hsv[2] = 0.6f;
            int c = Color.HSVToColor(hsv) & 0x00FFFFFF;
            paint.setShader(new RadialGradient(x, y, r, new int[]{0xB3000000 | c, 0x40000000 | c, 0x00000000 | c},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(x, y, r, paint);
        }
        postDelayed(frame, FRAME_MS);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(frame);
        super.onDetachedFromWindow();
    }
}
