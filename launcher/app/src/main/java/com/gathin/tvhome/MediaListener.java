package com.gathin.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so the screensaver may read what is playing: Android lets an enabled notification
 * listener see media sessions. It ignores notifications. Enabled over adb with
 * `cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.MediaListener`
 * (build-and-install does this).
 */
public class MediaListener extends NotificationListenerService {
}
