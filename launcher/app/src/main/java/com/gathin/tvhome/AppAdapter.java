package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock (favourites, fixed order),
 * the rest is alphabetical.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for D-pad Up/Down on a tile. Return true when handled. */
        boolean onTileVerticalKey(int position, int keyCode);

        /** D-pad Left/Right on a tile. Return true when handled. */
        boolean onTileHorizontalKey(int position, int keyCode);

        /** Menu key or long-press OK on a tile: dock editing and app info. */
        void onTileMenu(int position, View card);
    }

    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        int ambient;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this.label = label;
            this.component = component;
            this.pkg = component == null ? null : component.getPackageName();
            this.banner = banner;
            this.icon = icon;
        }

        /** An empty dock slot: shown as an outline so a short dock doesn't pull grid apps into it. */
        static Entry emptySlot() {
            return new Entry("", null, null, null);
        }

        boolean isEmptySlot() {
            return component == null;
        }

        String key() {
            return component == null ? null : component.flattenToShortString();
        }
    }

    static final float FOCUS_SCALE = 1.10f;

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    /** Dock order: edited on the TV and saved in DockPrefs; config.xml (dock_apps) is the default. */
    private List<String> favourites;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final List<Entry> items = new ArrayList<>();

    AppAdapter(Context ctx, TileListener listener, int dockSize) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.favourites = DockPrefs.load(ctx);
        this.hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    void reload() {
        favourites = DockPrefs.load(ctx);
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        List<Entry> fresh = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (hidden.contains(pkg)) continue;   // our own Ambient tile is included on purpose
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            Drawable icon = ri.loadIcon(pm);
            String label = String.valueOf(ri.loadLabel(pm));
            fresh.add(new Entry(label, new ComponentName(pkg, ri.activityInfo.name), banner, icon));
        }
        Collections.sort(fresh, (a, b) -> {
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        // Fill the rest of the dock row with empty slots, so the grid starts on the second row.
        int docked = 0;
        while (docked < fresh.size() && favourites.contains(fresh.get(docked).pkg)) docked++;
        if (docked < dockSize && fresh.size() > docked) {
            for (int i = docked; i < dockSize; i++) fresh.add(i, Entry.emptySlot());
        }
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new Holder(v, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        boolean empty = e.isEmptySlot();
        h.card.setFocusable(!empty);
        h.card.setClickable(!empty);
        h.card.setLongClickable(!empty);
        h.card.setBackgroundResource(empty ? R.drawable.tile_empty : R.drawable.tile_bg);
        h.name.setText(e.label);
        h.name.setVisibility(position < dockSize ? View.INVISIBLE : View.VISIBLE);   // the dock has no labels
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.name.setAlpha(focused ? 1f : 0f);
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        if (empty) {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }
        h.card.setOnClickListener(empty ? null : v -> launch(e));
    }

    private void launch(Entry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, "Can't open " + e.label, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        Entry e = items.get(position);
        return e.isEmptySlot() ? -1 - position : e.key().hashCode();
    }

    boolean isEmptySlot(int position) {
        return position >= 0 && position < items.size() && items.get(position).isEmptySlot();
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).key() : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (key.equals(items.get(i).key())) return i;
        }
        return -1;
    }

    String pkgAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).pkg : null;
    }

    /** The installed dock apps, in dock order (the start of the first row). */
    List<String> dockApps() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(dockSize, items.size()); i++) {
            if (favourites.contains(items.get(i).pkg)) out.add(items.get(i).pkg);
        }
        return out;
    }

    /** Label of an installed app by package, or the package name. */
    String labelOf(String pkg) {
        for (Entry e : items) {
            if (pkg.equals(e.pkg)) return e.label;
        }
        return pkg;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    /** The colour the app's banner (or icon) reads as, for the ambient glow. Cached per entry. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return NEUTRAL_GLOW;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = glowColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    private static final int NEUTRAL_GLOW = 0xFF8A8A8A;
    private static final int HUE_BUCKETS = 12;   // 30 degrees each

    /**
     * Works out a glow colour from any banner, so new apps get one automatically. Colourful pixels
     * are grouped by hue, then:
     * - one hue clearly dominates them (Netflix red, FanCode orange, Xstream red): that hue;
     * - the banner is colourful all over (a gradient, a full-colour banner): its strongest hue;
     * - otherwise the colour is small or mixed on a neutral banner (Play Store's logo on white):
     *   the banner's overall tone, so a white banner glows white instead of one logo colour.
     */
    static int glowColor(Drawable live) {
        if (live == null) return NEUTRAL_GLOW;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 24;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, n, n);
        d.draw(new Canvas(bmp));
        d.setBounds(saved);

        float[] hsv = new float[3];
        float[] weight = new float[HUE_BUCKETS];
        long[] sumR = new long[HUE_BUCKETS], sumG = new long[HUE_BUCKETS], sumB = new long[HUE_BUCKETS];
        int[] count = new int[HUE_BUCKETS];
        long r = 0, g = 0, b = 0;
        int opaque = 0, colourful = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int px = bmp.getPixel(x, y);
                if (Color.alpha(px) < 128) continue;   // transparent parts of an icon
                opaque++;
                r += Color.red(px);
                g += Color.green(px);
                b += Color.blue(px);
                Color.colorToHSV(px, hsv);
                if (hsv[1] < 0.35f || hsv[2] < 0.3f) continue;   // white, grey, black, very dark
                colourful++;
                int k = (int) (hsv[0] / (360f / HUE_BUCKETS)) % HUE_BUCKETS;
                weight[k] += hsv[1] * hsv[2];
                sumR[k] += Color.red(px);
                sumG[k] += Color.green(px);
                sumB[k] += Color.blue(px);
                count[k]++;
            }
        }
        bmp.recycle();
        if (opaque == 0) return NEUTRAL_GLOW;

        // Strongest hue: a bucket plus its neighbours, so a hue on a bucket edge isn't split in two.
        float total = 0f, bestWeight = 0f;
        int best = 0;
        for (int k = 0; k < HUE_BUCKETS; k++) {
            total += weight[k];
            float w = weight[(k + HUE_BUCKETS - 1) % HUE_BUCKETS] + weight[k] + weight[(k + 1) % HUE_BUCKETS];
            if (w > bestWeight) {
                bestWeight = w;
                best = k;
            }
        }
        float colourShare = colourful / (float) opaque;
        float dominance = total > 0f ? bestWeight / total : 0f;
        boolean useHue = colourShare >= 0.5f || (colourShare >= 0.04f && dominance >= 0.6f);

        if (useHue) {
            long cr = 0, cg = 0, cb = 0;
            int c = 0;
            for (int k = best - 1; k <= best + 1; k++) {
                int kk = (k + HUE_BUCKETS) % HUE_BUCKETS;
                cr += sumR[kk];
                cg += sumG[kk];
                cb += sumB[kk];
                c += count[kk];
            }
            Color.colorToHSV(Color.rgb((int) (cr / c), (int) (cg / c), (int) (cb / c)), hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.1f);
            hsv[2] = Math.max(0.55f, hsv[2]);
        } else {
            Color.colorToHSV(Color.rgb((int) (r / opaque), (int) (g / opaque), (int) (b / opaque)), hsv);
            hsv[1] *= 0.5f;   // keep a hint of the banner's tint, but read as white/grey
            hsv[2] = Math.max(0.6f, hsv[2]);
        }
        return Color.HSVToColor(hsv);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;

        Holder(View v, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);

            final float density = v.getResources().getDisplayMetrics().density;
            final float radius = density * 16f;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            card.setOnLongClickListener(view -> {
                int pos = getBindingAdapterPosition();
                if (listener != null && pos != RecyclerView.NO_POSITION) listener.onTileMenu(pos, view);
                return true;
            });

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    int pos = getBindingAdapterPosition();
                    return listener != null && pos != RecyclerView.NO_POSITION
                            && listener.onTileHorizontalKey(pos, keyCode);
                }
                if (keyCode == KeyEvent.KEYCODE_MENU) {
                    int pos = getBindingAdapterPosition();
                    if (listener != null && pos != RecyclerView.NO_POSITION) listener.onTileMenu(pos, view);
                    return true;
                }
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 260 : 180)
                        .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                        .start();
                name.animate().alpha(focused ? 1f : 0f)
                        .translationY(focused ? density * 4f : -density * 6f)
                        .setDuration(220).start();
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
