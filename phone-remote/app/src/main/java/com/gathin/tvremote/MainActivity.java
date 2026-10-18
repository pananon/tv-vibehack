package com.gathin.tvremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dadb.AdbKeyPair;
import dadb.Dadb;

/**
 * Phone remote for the TV, over the TV's network ADB (the same way TV Remote on the PC works).
 * The phone has its own ADB key: the first connection shows "Allow USB debugging?" on the TV.
 */
public class MainActivity extends Activity {

    private static final int PORT = 5555;
    private static final int REQ_SPEECH = 1;
    private static final String DEFAULT_HOST = "192.168.1.21";
    private static final String TIMER_PID = "/data/local/tmp/tvremote-sleep.pid";
    private static final String TIMER_END = "/data/local/tmp/tvremote-sleep.end";

    /** Friendly names for common TV apps; others show their package's last part. */
    private static final Map<String, String> NAMES = new LinkedHashMap<>();

    static {
        NAMES.put("com.netflix.ninja", "Netflix");
        NAMES.put("com.amazon.amazonvideo.livingroom", "Prime Video");
        NAMES.put("com.google.android.youtube.tv", "YouTube");
        NAMES.put("in.startv.hotstar", "JioHotstar");
        NAMES.put("com.fancode.tv", "FanCode");
        NAMES.put("tv.airtel.xstream.tvapp", "Xstream Play");
        NAMES.put("com.android.vending", "Play Store");
        NAMES.put("com.gathin.tvhome/.AmbientActivity", "Ambient");
        NAMES.put("com.gathin.tvhome/.ClaudeActivity", "Claude");
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile Dadb dadb;  // used on the io thread (the watchdog may close it)
    private volatile boolean connecting;
    private AdbKeyPair keys;
    private String host;
    private SharedPreferences prefs;

    private TextView status, sleepStatus;
    private View dot;
    private EditText hostField, textField;
    private LinearLayout appsBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("remote", MODE_PRIVATE);
        status = findViewById(R.id.status);
        sleepStatus = findViewById(R.id.sleepStatus);
        dot = findViewById(R.id.dot);
        hostField = findViewById(R.id.host);
        textField = findViewById(R.id.text);
        appsBox = findViewById(R.id.apps);
        hostField.setText(prefs.getString("host", DEFAULT_HOST));

        findViewById(R.id.connect).setOnClickListener(v -> connect(hostField.getText().toString().trim()));
        hostField.setOnEditorActionListener((v, id, e) -> {
            connect(hostField.getText().toString().trim());
            return true;
        });
        findViewById(R.id.find).setOnClickListener(v -> findTvs());

        key(R.id.power, "KEYCODE_POWER", false);
        key(R.id.input, "KEYCODE_TV_INPUT", false);
        click(R.id.settings, () -> shell("am start -a android.settings.SETTINGS"));
        click(R.id.ambient, () -> shell("am start -n com.gathin.tvhome/.AmbientActivity"));
        key(R.id.up, "KEYCODE_DPAD_UP", true);
        key(R.id.down, "KEYCODE_DPAD_DOWN", true);
        key(R.id.left, "KEYCODE_DPAD_LEFT", true);
        key(R.id.right, "KEYCODE_DPAD_RIGHT", true);
        key(R.id.ok, "KEYCODE_DPAD_CENTER", false);
        key(R.id.back, "KEYCODE_BACK", false);
        key(R.id.home, "KEYCODE_HOME", false);
        key(R.id.menu, "KEYCODE_MENU", false);
        key(R.id.rew, "KEYCODE_MEDIA_REWIND", false);
        key(R.id.play, "KEYCODE_MEDIA_PLAY_PAUSE", false);
        key(R.id.ff, "KEYCODE_MEDIA_FAST_FORWARD", false);
        key(R.id.voldown, "KEYCODE_VOLUME_DOWN", true);
        key(R.id.mute, "KEYCODE_VOLUME_MUTE", false);
        key(R.id.volup, "KEYCODE_VOLUME_UP", true);

        click(R.id.send, this::sendText);
        textField.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_SEND) {
                sendText();
                return true;
            }
            return false;
        });
        click(R.id.mic, this::dictate);

        click(R.id.sleep30, () -> sleepIn(30));
        click(R.id.sleep60, () -> sleepIn(60));
        click(R.id.sleep90, () -> sleepIn(90));
        click(R.id.sleepOff, () -> sleepIn(0));

        setStatus("Not connected", 0xFF8A8F9A);
        connect(hostField.getText().toString().trim());
    }

    @Override
    protected void onDestroy() {
        io.execute(this::closeQuietly);
        io.shutdown();
        super.onDestroy();
    }

    // ---- buttons ---------------------------------------------------------------------------------

    private void click(int id, Runnable r) {
        findViewById(id).setOnClickListener(v -> r.run());
    }

    /** A key button; repeating ones repeat while held, like a real remote. */
    private void key(int id, String code, boolean repeat) {
        View b = findViewById(id);
        if (!repeat) {
            b.setOnClickListener(v -> shell("input keyevent " + code));
            return;
        }
        Runnable[] again = new Runnable[1];
        again[0] = () -> {
            shell("input keyevent " + code);
            ui.postDelayed(again[0], 150);
        };
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    shell("input keyevent " + code);
                    ui.postDelayed(again[0], 450);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    ui.removeCallbacks(again[0]);
                    return true;
                default:
                    return true;
            }
        });
    }

    // ---- the TV connection (io thread) -----------------------------------------------------------

    private void connect(String newHost) {
        if (newHost.isEmpty()) return;
        setStatus("Connecting to " + newHost + "… (first time: allow it on the TV)", 0xFFF5A524);
        connecting = true;
        // Unanswered "Allow USB debugging?" on the TV: give up after 75 s instead of waiting forever.
        ui.postDelayed(() -> {
            Dadb d = dadb;
            if (connecting && d != null) new Thread(() -> {
                try {
                    d.close();
                } catch (Exception ignored) {
                }
            }).start();
        }, 75_000);
        io.execute(() -> {
            closeQuietly();
            host = newHost;
            boolean ok = open();
            connecting = false;
            if (ok) {
                prefs.edit().putString("host", newHost).apply();
                ui.post(() -> setStatus("Connected · " + newHost, 0xFF3ECF8E));
                loadApps();
                readSleep();
            } else {
                ui.post(() -> setStatus("Can't reach the TV at " + newHost + ". Allowed it on the TV? Tap Connect", 0xFFE5484D));
            }
        });
    }

    /** Opens the connection. Allows a minute for "Allow USB debugging?" on a first connection. */
    private boolean open() {
        try {
            if (keys == null) {
                File priv = new File(getFilesDir(), "adbkey"), pub = new File(getFilesDir(), "adbkey.pub");
                if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub);
                keys = AdbKeyPair.read(priv, pub);
            }
            dadb = Dadb.create(host, PORT, keys, 10_000, 60_000);
            return dadb.shell("echo ok").getOutput().contains("ok");
        } catch (Exception e) {
            closeQuietly();
            return false;
        }
    }

    private void closeQuietly() {
        if (dadb != null) {
            try {
                dadb.close();
            } catch (Exception ignored) {
            }
        }
        dadb = null;
    }

    /** Runs a shell command on the TV; reconnects once if the connection dropped. */
    private String run(String cmd) {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (dadb == null && (host == null || !open())) break;
            try {
                return dadb.shell(cmd).getOutput();
            } catch (Exception e) {
                closeQuietly();
            }
        }
        ui.post(() -> setStatus("Lost the TV: tap Connect", 0xFFE5484D));
        return null;
    }

    private void shell(String cmd) {
        io.execute(() -> run(cmd));
    }

    // ---- typing and dictation --------------------------------------------------------------------

    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private void sendText() {
        String text = textField.getText().toString().trim();
        if (text.isEmpty()) return;
        if (text.matches("(?i)^(https?://|www\\.)\\S+$")) {
            String url = text.contains("://") ? text : "https://" + text;
            shell("am start -a android.intent.action.VIEW -d " + quote(url));   // opens in its TV app
        } else {
            shell("input text " + quote(text.replace("%", "\\%").replace(" ", "%s")));
        }
        textField.setText("");
    }

    private void dictate() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say what to type on the TV");
        try {
            startActivityForResult(i, REQ_SPEECH);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No speech recogniser on this phone", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SPEECH || resultCode != RESULT_OK || data == null) return;
        ArrayList<String> said = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (said != null && !said.isEmpty()) {
            textField.setText(said.get(0));   // shown first, so you can check it before Send
            textField.setSelection(textField.length());
        }
    }

    // ---- sleep timer (runs on the TV, same as TV Remote on the PC) -------------------------------

    private void sleepIn(int minutes) {
        String cancel = "kill $(cat " + TIMER_PID + " 2>/dev/null) 2>/dev/null; rm -f " + TIMER_PID + " " + TIMER_END;
        if (minutes == 0) {
            shell(cancel);
        } else {
            int secs = minutes * 60;
            // setsid + nohup detach it from this session; the trailing sleep lets it detach before
            // the session ends (otherwise adbd kills it).
            shell(cancel + "; echo $(( $(date +%s) + " + secs + " )) > " + TIMER_END
                    + "; (setsid nohup sh -c 'echo $$ > " + TIMER_PID + "; sleep " + secs
                    + " && input keyevent KEYCODE_SLEEP; rm -f " + TIMER_PID + " " + TIMER_END
                    + "' > /dev/null 2>&1 < /dev/null &); sleep 1");
        }
        io.execute(this::readSleep);
    }

    private void readSleep() {
        String out = run("if kill -0 $(cat " + TIMER_PID + " 2>/dev/null) 2>/dev/null; then echo END=$(cat "
                + TIMER_END + "); fi; echo NOW=$(date +%s)");
        if (out == null) return;
        Matcher end = Pattern.compile("END=(\\d+)").matcher(out), now = Pattern.compile("NOW=(\\d+)").matcher(out);
        String text = "Off";
        if (end.find() && now.find()) {
            long mins = (Long.parseLong(end.group(1)) - Long.parseLong(now.group(1)) + 59) / 60;
            text = mins > 1 ? "TV sleeps in " + mins + " min" : "TV sleeps in under a minute";
        }
        String t = text;
        ui.post(() -> sleepStatus.setText(t));
    }

    // ---- apps from the TV ------------------------------------------------------------------------

    private void loadApps() {
        String out = run("cmd package query-activities --brief -a android.intent.action.MAIN"
                + " -c android.intent.category.LEANBACK_LAUNCHER");
        if (out == null) return;
        List<String[]> apps = new ArrayList<>();   // {label, component}
        for (String line : out.split("\n")) {
            line = line.trim();
            if (!line.matches("[\\w.]+/[\\w.$]+")) continue;
            String pkg = line.substring(0, line.indexOf('/'));
            String short1 = pkg + "/" + line.substring(line.indexOf('/') + 1).replace(pkg, "");
            String label = NAMES.containsKey(short1) ? NAMES.get(short1) : NAMES.get(pkg);
            if (label == null) {
                String last = pkg.substring(pkg.lastIndexOf('.') + 1);
                label = Character.toUpperCase(last.charAt(0)) + last.substring(1);
            }
            apps.add(new String[]{label, line});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        ui.post(() -> showApps(apps));
    }

    private void showApps(List<String[]> apps) {
        appsBox.removeAllViews();
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                appsBox.addView(row);
            }
            String[] app = apps.get(i);
            Button b = (Button) getLayoutInflater().inflate(R.layout.app_button, row, false);
            b.setText(app[0]);
            b.setOnClickListener(v -> shell("am start -n " + app[1]));
            row.addView(b);
        }
        if (apps.size() % 2 == 1 && row != null) {   // keep the last button half width
            View spacer = new View(this);
            row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        }
    }

    // ---- finding the TV --------------------------------------------------------------------------

    /** Lists devices on this Wi-Fi with the ADB port open. Nothing is logged in to: only a port check. */
    private void findTvs() {
        String prefix = wifiPrefix();
        if (prefix == null) {
            Toast.makeText(this, "Connect the phone to your Wi-Fi first", Toast.LENGTH_SHORT).show();
            return;
        }
        setStatus("Looking for TVs on " + prefix + "x…", 0xFFF5A524);
        new Thread(() -> {
            ExecutorService pool = Executors.newFixedThreadPool(48);
            List<String> found = Collections.synchronizedList(new ArrayList<>());
            for (int i = 1; i < 255; i++) {
                String ip = prefix + i;
                pool.execute(() -> {
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress(ip, PORT), 400);
                        found.add(ip);
                    } catch (Exception ignored) {
                    }
                });
            }
            pool.shutdown();
            try {
                pool.awaitTermination(15, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            List<String> sorted = new ArrayList<>(found);
            Collections.sort(sorted);
            ui.post(() -> pickTv(sorted));
        }, "find-tv").start();
    }

    private void pickTv(List<String> ips) {
        if (ips.isEmpty()) {
            setStatus("No TV found: is network/USB debugging on?", 0xFFE5484D);
            return;
        }
        if (ips.size() == 1) {
            hostField.setText(ips.get(0));
            connect(ips.get(0));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Pick your TV")
                .setItems(ips.toArray(new String[0]), (d, which) -> {
                    hostField.setText(ips.get(which));
                    connect(ips.get(which));
                })
                .show();
    }

    private String wifiPrefix() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        LinkProperties lp = cm != null ? cm.getLinkProperties(cm.getActiveNetwork()) : null;
        if (lp == null) return null;
        for (LinkAddress la : lp.getLinkAddresses()) {
            if (la.getAddress() instanceof Inet4Address) {
                String ip = la.getAddress().getHostAddress();
                return ip.substring(0, ip.lastIndexOf('.') + 1);
            }
        }
        return null;
    }

    private void setStatus(String text, int colour) {
        status.setText(text);
        dot.getBackground().mutate().setTint(colour);
    }
}
