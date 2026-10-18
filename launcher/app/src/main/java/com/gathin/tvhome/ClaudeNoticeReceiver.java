package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * "Claude is waiting" card over whatever is on screen, for a few seconds. TV Remote sends it over adb
 * when the Claude Code chat goes from working to waiting:
 * `am broadcast -n com.gathin.tvhome/.ClaudeNoticeReceiver --es title64 <base64> --es text64 <base64>`.
 * The receiver needs android.permission.DUMP, which the adb shell has and apps don't, so other apps
 * can't put text on screen through it. Drawing over apps needs SYSTEM_ALERT_WINDOW, granted with
 * appops by build-and-install. Skipped while the Claude screen is open.
 */
public class ClaudeNoticeReceiver extends BroadcastReceiver {

    private static final long SHOW_MS = 8000;
    private static View showing;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable hide = ClaudeNoticeReceiver::hideNow;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ClaudeActivity.visible || !Settings.canDrawOverlays(context)) return;
        Context app = context.getApplicationContext();
        String title = extra(intent, "title");
        String text = extra(intent, "text");
        handler.post(() -> show(app, title != null ? title : "Claude is waiting for you", text));
    }

    /** Text arrives base64-encoded (title64/text64): the TV's shell mangles quotes in am arguments. */
    private static String extra(Intent intent, String name) {
        String b64 = intent.getStringExtra(name + "64");
        if (b64 != null) {
            try {
                return new String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return intent.getStringExtra(name);
    }

    private static void show(Context ctx, String title, String text) {
        hideNow();
        WindowManager wm = ctx.getSystemService(WindowManager.class);
        View card = LayoutInflater.from(ctx).inflate(R.layout.claude_notice, null);
        ((TextView) card.findViewById(R.id.noticeTitle)).setText(title);
        TextView body = card.findViewById(R.id.noticeText);
        if (text == null || text.trim().isEmpty()) body.setVisibility(View.GONE);
        else body.setText(text.trim());

        float d = ctx.getResources().getDisplayMetrics().density;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // Never takes focus or touches: whatever is playing keeps the remote.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.x = (int) (40 * d);
        lp.y = (int) (40 * d);
        try {
            wm.addView(card, lp);
        } catch (RuntimeException e) {   // overlay permission revoked meanwhile
            return;
        }
        showing = card;
        card.setAlpha(0f);
        card.setTranslationY(16 * d);
        card.animate().alpha(1f).translationY(0f).setDuration(300).start();
        handler.postDelayed(hide, SHOW_MS);
    }

    private static void hideNow() {
        handler.removeCallbacks(hide);
        if (showing == null) return;
        View v = showing;
        showing = null;
        try {
            v.getContext().getSystemService(WindowManager.class).removeView(v);
        } catch (RuntimeException ignored) {
        }
    }
}
