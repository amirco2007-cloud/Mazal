package com.mazal.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

/**
 * Watches foreground app changes. Two triggers, both opening the same Mazal draw screen:
 *   - "entry":    a blocked app comes to the foreground (virtually every open / re-open).
 *   - "duration": a blocked app stays in the foreground continuously for 5 minutes.
 *
 * Loop prevention: a short grace window after "continue", foreground-change edge
 * detection, and exclusion of self / system UI. (Launchers and other non-blocked
 * apps are naturally ignored — they simply aren't in the blocked set.)
 */
public class AppWatchAccessibilityService extends AccessibilityService {

    public static final String REASON_ENTRY = "entry";
    public static final String REASON_DURATION = "duration";

    private static final long DURATION_MS = 5 * 60 * 1000L; // 5 minutes continuous

    private String lastForeground = null;
    private String ownPackage;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable durationRunnable;
    private String timedPackage; // blocked app currently being timed for continuous use

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        ownPackage = getPackageName();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;
        String pkg = pkgCs.toString();

        if (pkg.equals(lastForeground)) return;          // edge detection: ignore repeats
        String previous = lastForeground;
        lastForeground = pkg;

        // Ignore our own screen and system surfaces without disturbing the timer:
        // Mazal popping over a blocked app must NOT cancel the running 5-min timer.
        if (pkg.equals(ownPackage)) return;
        if (pkg.equals("android") || pkg.startsWith("com.android.systemui")) return;

        boolean blocked = BlockStore.getBlocked(this).contains(pkg);

        if (!blocked) {
            // Left the blocked app for the launcher or another (non-blocked) app.
            cancelDurationTimer();
            return;
        }

        // A blocked app is in the foreground.
        startDurationTimer(pkg);

        // Entry intercept — suppressed only for the immediate post-continue transition.
        if (BlockStore.isInGrace(this, pkg)) return;
        if (ownPackage.equals(previous)) return;

        launchInterceptor(pkg, REASON_ENTRY);
    }

    private void startDurationTimer(final String pkg) {
        cancelDurationTimer();
        timedPackage = pkg;
        durationRunnable = new Runnable() {
            @Override public void run() {
                // Still the foreground app after 5 continuous minutes?
                if (pkg.equals(lastForeground)) {
                    launchInterceptor(pkg, REASON_DURATION);
                }
            }
        };
        handler.postDelayed(durationRunnable, DURATION_MS);
    }

    private void cancelDurationTimer() {
        if (durationRunnable != null) {
            handler.removeCallbacks(durationRunnable);
            durationRunnable = null;
        }
        timedPackage = null;
    }

    private void launchInterceptor(String targetPkg, String reason) {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        i.putExtra(MazalNativePlugin.EXTRA_TARGET, targetPkg);
        i.putExtra(MazalNativePlugin.EXTRA_REASON, reason);
        startActivity(i);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        cancelDurationTimer();
        return super.onUnbind(intent);
    }
}
