package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Current weather from open-meteo.com (free, no account, no key) for the place in config.xml.
 * The last reading is kept in SharedPreferences so the clock row shows it straight away.
 */
final class Weather {

    /** How often to refresh, and how old a reading may be before it is hidden. */
    static final long REFRESH_MS = 55 * 60_000L;
    static final long STALE_MS = 3 * 60 * 60_000L;

    final float temperature;
    final int code;   // WMO weather code
    final boolean day;
    final long time;

    private Weather(float temperature, int code, boolean day, long time) {
        this.temperature = temperature;
        this.code = code;
        this.day = day;
        this.time = time;
    }

    /** Blocking: call off the main thread. */
    static Weather fetch(String lat, String lon, String unit) throws IOException {
        URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,weather_code,is_day&temperature_unit=" + unit + "&timezone=auto");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
            JSONObject now = new JSONObject(buf.toString(StandardCharsets.UTF_8.name())).getJSONObject("current");
            return new Weather((float) now.getDouble("temperature_2m"), now.getInt("weather_code"),
                    now.optInt("is_day", 1) == 1, System.currentTimeMillis());
        } catch (org.json.JSONException e) {
            throw new IOException("unexpected weather response", e);
        } finally {
            c.disconnect();
        }
    }

    static Weather cached(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (!p.contains("time")) return null;
        return new Weather(p.getFloat("temp", 0f), p.getInt("code", 0), p.getBoolean("day", true), p.getLong("time", 0));
    }

    void save(Context ctx) {
        prefs(ctx).edit().putFloat("temp", temperature).putInt("code", code).putBoolean("day", day)
                .putLong("time", time).apply();
    }

    String label() {
        return Math.round(temperature) + "°";
    }

    /** WMO weather interpretation codes, grouped into the launcher's icons. */
    int icon() {
        if (code == 0) return day ? R.drawable.ic_wx_clear_day : R.drawable.ic_wx_clear_night;
        if (code <= 2) return R.drawable.ic_wx_partly;
        if (code == 3) return R.drawable.ic_wx_cloudy;
        if (code == 45 || code == 48) return R.drawable.ic_wx_fog;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.ic_wx_snow;
        if (code >= 95) return R.drawable.ic_wx_storm;
        if (code >= 51) return R.drawable.ic_wx_rain;   // drizzle, rain, freezing rain, showers
        return R.drawable.ic_wx_cloudy;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("weather", Context.MODE_PRIVATE);
    }
}
