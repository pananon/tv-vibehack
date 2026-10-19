package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * The dock's apps, in order. Edited on the TV (Menu or long-press OK on a tile) and saved here;
 * config.xml (dock_apps) or a theme's config.json is only the starting order before the first edit.
 */
final class DockPrefs {

    private static final String FILE = "dock";
    private static final String KEY = "apps";

    private DockPrefs() {
    }

    /** The saved dock, or defaults (config.xml dock_apps, or a theme's config.json) before the first edit. */
    static List<String> load(Context ctx, List<String> defaults) {
        String saved = prefs(ctx).getString(KEY, null);
        if (saved == null) {
            return new ArrayList<>(defaults);
        }
        List<String> out = new ArrayList<>();
        for (String pkg : saved.split(",")) {
            if (!pkg.isEmpty()) out.add(pkg);
        }
        return out;
    }

    static void save(Context ctx, List<String> pkgs) {
        prefs(ctx).edit().putString(KEY, TextUtils.join(",", pkgs)).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
