package com.gathin.tvhome;

import android.service.dreams.DreamService;

/** Ambient mode as a screensaver, next to Clock. */
public class AmbientDream extends DreamService {

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setContentView(new AmbientView(this));
    }
}
