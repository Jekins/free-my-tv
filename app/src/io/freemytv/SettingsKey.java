package io.freemytv;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.Locale;

/**
 * The remote's Settings button on the home screen, where no app can see it. Android 14 answers the key itself, before
 * any app and before an accessibility service's key filter (PhoneWindowManager: SETTINGS_KEY_BEHAVIOR_NOTIFICATION_PANEL):
 * it asks SystemUI for the notification handler's panel, which is Google TV's side panel, in its home app (launcherx),
 * off in Free My TV mode. So nothing opened, and SystemUI said why in its log, once a press. While the home screen is
 * in front, `logcat` over the TV's own ADB waits for that line, and TV Settings open.
 * Seen on JMGO's projectors (K93A, MediaTek, Google TV 14), whose gear sends it; only there for now.
 */
final class SettingsKey {
    static final boolean ON = RemoteKeys.JMGO && Build.VERSION.SDK_INT >= 34;
    private static final String SAID = "TvNotificationPanel: Not launching notification handler activity";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable open;
    private Watch watch;

    /** `open` runs on the main thread, once a press. */
    SettingsKey(Runnable open) {
        this.open = open;
    }

    /** On the home screen's resume. */
    void start(Context c) {
        if (!ON || watch != null) return;
        watch = new Watch(c.getApplicationContext());
        new Thread(watch, "settings-key").start();
    }

    /** On its pause: the connection closes, and logcat with it. */
    void stop() {
        if (watch == null) return;
        watch.stop();
        watch = null;
    }

    /** One run from a resume to its pause, with its own connection (a quick pause and resume may overlap two). */
    private final class Watch implements Runnable {
        private final Context app;
        private volatile boolean stopped;
        private volatile Adb adb;

        Watch(Context app) {
            this.app = app;
        }

        void stop() {
            stopped = true;
            Adb a = adb;
            if (a != null) a.close();   // unblocks the read
        }

        @Override public void run() {
            Adb a = Adb.known(app);   // no prompt: without the TV's OK from the switch, nothing
            if (a == null) return;
            adb = a;
            if (stopped) {
                a.close();
                return;
            }
            // Only what SystemUI says from now on (the time in epoch seconds, as -v epoch prints it).
            final double since = System.currentTimeMillis() / 1000.0;
            try {
                a.stream(String.format(Locale.ROOT, "logcat -v epoch -T %.3f -b main -s TvNotificationPanel:E", since), line -> {
                    if (!line.contains(SAID) || at(line) < since) return;
                    main.post(() -> {
                        if (!stopped) open.run();
                    });
                });
            } catch (IOException ignored) {
                // closed by stop(), or ADB went away: the button does nothing again until the next resume
            } finally {
                a.close();
            }
        }
    }

    /** The line's time ("1793052057.488  1507  1507 E …"); unreadable: now. */
    private static double at(String line) {
        String t = line.trim();
        int end = t.indexOf(' ');
        try {
            return Double.parseDouble(end > 0 ? t.substring(0, end) : t);
        } catch (NumberFormatException e) {
            return Double.MAX_VALUE;
        }
    }
}
