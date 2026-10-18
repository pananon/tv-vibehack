package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Claude on TV: the live Claude Code chat from the PC, view only. The PC side is TV Remote's
 * "Claude on TV" (remote/claude_tv.py), which starts this screen with the bridge address and key;
 * they are remembered so the tile works while the bridge is running. Up/Down scroll, OK jumps to the
 * newest message, and new messages are followed while you are at the bottom.
 */
public class ClaudeActivity extends Activity {

    private static final long POLL_MS = 1500;
    /** True while this screen is in front: the "Claude is waiting" card is skipped then. */
    static boolean visible;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String[]> items = new ArrayList<>();   // {role, text}
    private RecyclerView list;
    private LinearLayoutManager layout;
    private TextView title, status;
    private View dot;
    private String url, token;
    private int lastId;
    private boolean polling, resumed;
    private float density;

    private final Runnable poll = this::fetch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.claude_screen);
        density = getResources().getDisplayMetrics().density;
        title = findViewById(R.id.claudeTitle);
        status = findViewById(R.id.claudeStatus);
        dot = findViewById(R.id.claudeDot);
        list = findViewById(R.id.claudeList);
        layout = new LinearLayoutManager(this);
        layout.setStackFromEnd(true);
        list.setLayoutManager(layout);
        list.setItemAnimator(null);
        list.setAdapter(new ChatAdapter());
        title.setText(R.string.claude_name);
        readConnection(getIntent() != null ? getIntent().getExtras() : null);
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        readConnection(intent.getExtras());
    }

    /** Address and key come from the PC (adb am start extras); keep them for the tile. */
    private void readConnection(Bundle extras) {
        SharedPreferences prefs = getSharedPreferences("claude", MODE_PRIVATE);
        if (extras != null && extras.getString("url") != null && extras.getString("token") != null) {
            String newUrl = extras.getString("url"), newToken = extras.getString("token");
            if (!newUrl.equals(url) || !newToken.equals(token)) {
                items.clear();
                lastId = 0;
                if (list.getAdapter() != null) list.getAdapter().notifyDataSetChanged();
            }
            prefs.edit().putString("url", newUrl).putString("token", newToken).apply();
        }
        url = prefs.getString("url", null);
        token = prefs.getString("token", null);
        if (url == null) setStatus(getString(R.string.claude_not_set_up), 0xFFE5484D);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        visible = true;
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        visible = false;
        handler.removeCallbacks(poll);
    }

    // ---- polling the PC --------------------------------------------------------------------------

    private void fetch() {
        if (!resumed || url == null || polling) return;
        polling = true;
        final String u = url + "/feed?token=" + token + "&since=" + lastId;
        new Thread(() -> {
            JSONObject result = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(4000);
                c.setReadTimeout(8000);
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] chunk = new byte[8192];
                    for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
                    result = new JSONObject(buf.toString(StandardCharsets.UTF_8.name()));
                } finally {
                    c.disconnect();
                }
            } catch (Exception ignored) {   // PC off, bridge stopped, or Wi-Fi blip: say so, keep trying
            }
            final JSONObject r = result;
            handler.post(() -> {
                polling = false;
                show(r);
                if (resumed) handler.postDelayed(poll, POLL_MS);
            });
        }, "claude-feed").start();
    }

    private void show(JSONObject r) {
        if (r == null) {
            setStatus(getString(R.string.claude_offline), 0xFFE5484D);
            return;
        }
        title.setText(r.optString("title", getString(R.string.claude_name)));
        boolean busy = r.optBoolean("busy");
        setStatus(getString(busy ? R.string.claude_working : R.string.claude_waiting), busy ? 0xFFF5A524 : 0xFF3ECF8E);
        JSONArray add = r.optJSONArray("items");
        if (add == null || add.length() == 0) return;
        boolean atBottom = items.isEmpty() || layout.findLastVisibleItemPosition() >= items.size() - 1;
        int start = items.size();
        for (int i = 0; i < add.length(); i++) {
            JSONObject it = add.optJSONObject(i);
            if (it == null) continue;
            items.add(new String[]{it.optString("role"), it.optString("text")});
            lastId = Math.max(lastId, it.optInt("id"));
        }
        while (items.size() > 300) items.remove(0);
        if (start > items.size()) start = 0;
        list.getAdapter().notifyDataSetChanged();
        if (atBottom) list.scrollToPosition(items.size() - 1);
    }

    private void setStatus(String text, int colour) {
        status.setText(text);
        dot.getBackground().mutate().setTint(colour);
    }

    // ---- remote keys -----------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int page = (int) (list.getHeight() * 0.4f);
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    list.smoothScrollBy(0, -page);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    list.smoothScrollBy(0, page);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (!items.isEmpty()) list.scrollToPosition(items.size() - 1);
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    // ---- the chat --------------------------------------------------------------------------------

    /** Light markdown cleanup for reading on a TV: no ** or `, bullets as dots, headings as plain lines. */
    static String readable(String md) {
        StringBuilder out = new StringBuilder();
        for (String line : md.split("\n", -1)) {
            String l = line.replaceAll("^#{1,6}\\s+", "").replaceAll("^(\\s*)[-*]\\s+", "$1• ");
            out.append(l.replace("**", "").replace("`", "")).append('\n');
        }
        return out.toString().trim();
    }

    private final class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemViewType(int position) {
            switch (items.get(position)[0]) {
                case "user": return 0;
                case "tool": return 2;
                default: return 1;
            }
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            FrameLayout row = new FrameLayout(ClaudeActivity.this);
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView t = new TextView(ClaudeActivity.this);
            t.setTypeface(type == 2 ? Typeface.MONOSPACE : getResources().getFont(R.font.outfit));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            int v = (int) (6 * density);
            if (type == 0) {   // you: a bubble on the right
                t.setTextSize(19);
                t.setTextColor(0xFFFFFFFF);
                t.setBackgroundResource(R.drawable.claude_user_bubble);
                int p = (int) (14 * density);
                t.setPadding(p, (int) (10 * density), p, (int) (10 * density));
                t.setMaxWidth((int) (640 * density));
                lp.gravity = Gravity.END;
                lp.topMargin = (int) (16 * density);
                lp.bottomMargin = v;
            } else if (type == 1) {   // Claude: plain text, full width
                t.setTextSize(19);
                t.setTextColor(0xF2FFFFFF);
                t.setLineSpacing(0, 1.18f);
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.topMargin = v;
                lp.bottomMargin = v;
            } else {   // a command Claude ran
                t.setTextSize(13);
                t.setTextColor(0x80FFFFFF);
                t.setSingleLine(true);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.topMargin = (int) (2 * density);
            }
            row.addView(t, lp);
            return new RecyclerView.ViewHolder(row) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            String[] it = items.get(position);
            TextView t = (TextView) ((FrameLayout) h.itemView).getChildAt(0);
            t.setText("tool".equals(it[0]) ? "▸ " + it[1] : readable(it[1]));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
