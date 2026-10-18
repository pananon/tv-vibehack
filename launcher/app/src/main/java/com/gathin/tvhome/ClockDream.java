package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;
import java.util.Random;

/**
 * Screensaver: the launcher's thin clock and date on black, over a slow ambient glow.
 * Every minute the clock drifts to a new spot and the glow shifts colour, so nothing burns in.
 * No network, no images: cheap enough for a 1 GB TV.
 * While music plays, a "Now playing" card (art, title, artist) sits under the clock. Reading media
 * sessions needs MediaListener enabled as a notification listener; without it the card stays hidden.
 */
public class ClockDream extends DreamService {

    private static final long MOVE_MS = 60_000L;
    /** Deep, low-brightness glow colours; the glow is drawn at partial alpha on black. */
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private View content;
    private GradientDrawable glow;
    private int colorIndex;
    private int glowColor;

    private MediaSessionManager sessions;
    private MediaController playing;
    /** Every session we listen to, so each update can drop the previous set. */
    private final java.util.ArrayList<MediaController> watched = new java.util.ArrayList<>();
    private View nowPlaying;
    private ImageView nowPlayingArt;
    private TextView nowPlayingTitle, nowPlayingArtist;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::pickSession;
    private final MediaController.Callback playingChanged = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            if (sessions != null) pickSession(activeSessions());
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            showNowPlaying();
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            moveClock();
            shiftGlow();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_clock);

        content = findViewById(R.id.dreamContent);
        View ambient = findViewById(R.id.dreamAmbient);
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.55f));
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);

        nowPlaying = findViewById(R.id.nowPlaying);
        nowPlayingArt = findViewById(R.id.nowPlayingArt);
        nowPlayingTitle = findViewById(R.id.nowPlayingTitle);
        nowPlayingArtist = findViewById(R.id.nowPlayingArtist);
        final float radius = 8 * getResources().getDisplayMetrics().density;
        nowPlayingArt.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        nowPlayingArt.setClipToOutline(true);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        content.setAlpha(0f);
        content.post(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        });
        handler.postDelayed(tick, MOVE_MS);
        watchMedia();
    }

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacks(tick);
        stopWatchingMedia();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        stopWatchingMedia();
        super.onDetachedFromWindow();
    }

    // ---- now playing -----------------------------------------------------------------------------

    private void watchMedia() {
        try {
            MediaSessionManager msm = getSystemService(MediaSessionManager.class);
            msm.addOnActiveSessionsChangedListener(sessionsChanged, new ComponentName(this, MediaListener.class), handler);
            sessions = msm;
            pickSession(activeSessions());
        } catch (SecurityException notAllowed) {   // MediaListener not enabled: clock only
            sessions = null;
        }
    }

    private void stopWatchingMedia() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
        sessions = null;
        unwatchAll();
        playing = null;
    }

    private List<MediaController> activeSessions() {
        try {
            return sessions.getActiveSessions(new ComponentName(this, MediaListener.class));
        } catch (SecurityException e) {
            return null;
        }
    }

    /** Follows the session that is playing; listens to all of them so a newly started one is seen. */
    private void pickSession(List<MediaController> list) {
        MediaController next = null;
        if (list != null) {
            for (MediaController mc : list) {
                PlaybackState st = mc.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                    next = mc;
                    break;
                }
            }
        }
        unwatchAll();
        playing = next;
        if (list != null) {
            // Watch every session's state so one that starts playing later is picked up.
            for (MediaController mc : list) {
                mc.registerCallback(playingChanged, handler);
                watched.add(mc);
            }
        }
        showNowPlaying();
    }

    private void unwatchAll() {
        for (MediaController mc : watched) mc.unregisterCallback(playingChanged);
        watched.clear();
    }

    private void showNowPlaying() {
        MediaMetadata md = playing != null ? playing.getMetadata() : null;
        CharSequence title = md != null ? md.getText(MediaMetadata.METADATA_KEY_TITLE) : null;
        if (md == null || title == null || title.length() == 0) {
            nowPlaying.setVisibility(View.GONE);
            return;
        }
        CharSequence artist = md.getText(MediaMetadata.METADATA_KEY_ARTIST);
        if (artist == null) artist = md.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        if (artist == null) artist = md.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);

        nowPlayingTitle.setText(title);
        nowPlayingArtist.setText(artist != null ? artist : "");
        nowPlayingArtist.setVisibility(artist != null ? View.VISIBLE : View.GONE);
        nowPlayingArt.setImageBitmap(art);
        nowPlayingArt.setVisibility(art != null ? View.VISIBLE : View.GONE);
        nowPlaying.setVisibility(View.VISIBLE);
        content.post(this::keepClockOnScreen);   // the card makes the block taller
    }

    private void keepClockOnScreen() {
        View parent = (View) content.getParent();
        content.setX(Math.max(0, Math.min(content.getX(), parent.getWidth() - content.getWidth())));
        content.setY(Math.max(0, Math.min(content.getY(), parent.getHeight() - content.getHeight())));
    }

    private void moveClock() {
        content.animate().alpha(0f).setDuration(900).withEndAction(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        }).start();
    }

    /** A random spot that keeps the whole clock on screen, with the glow centred behind it. */
    private void placeClock() {
        View parent = (View) content.getParent();
        int maxX = Math.max(0, parent.getWidth() - content.getWidth());
        int maxY = Math.max(0, parent.getHeight() - content.getHeight());
        float x = maxX * (0.1f + 0.8f * random.nextFloat());
        float y = maxY * (0.1f + 0.8f * random.nextFloat());
        content.setX(x);
        content.setY(y);
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            glow.setGradientCenter((x + content.getWidth() / 2f) / parent.getWidth(),
                    (y + content.getHeight() / 2f) / parent.getHeight());
        }
    }

    private void shiftGlow() {
        int from = glowColor;
        colorIndex = (colorIndex + 1) % PALETTE.length;
        ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), from, PALETTE[colorIndex]);
        anim.setDuration(8000);
        anim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        anim.start();
    }

    private void setGlow(int color) {
        glowColor = color;
        int c = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x40000000 | c, 0x14000000 | c, 0x00000000 | c});
    }
}
