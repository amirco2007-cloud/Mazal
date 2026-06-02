package com.mazal.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Watches foreground app changes. When a blocked app comes to the foreground,
 * launches Mazal in intercept mode. Three guards prevent an infinite re-pop loop:
 * grace window after "continue", foreground-change edge detection, and a
 * self/launcher/system exclusion list.
 */
public class AppWatchAccessibilityService extends AccessibilityService {

    private String lastForeground = null;
    private String ownPackage;
    private Set<String> launchers;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        ownPackage = getPackageName();
        launchers = resolveLaunchers();
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

        if (pkg.equals(ownPackage)) return;
        if (launchers != null && launchers.contains(pkg)) return;
        if (pkg.equals("android") || pkg.startsWith("com.android.systemui")) return;
        if (BlockStore.isInGrace(this, pkg)) return;     // just continued into this app
        if (ownPackage.equals(previous)) return;         // returning straight from Mazal
        if (!BlockStore.getBlocked(this).contains(pkg)) return;

        launchInterceptor(pkg);
    }

    private void launchInterceptor(String targetPkg) {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        i.putExtra(MazalNativePlugin.EXTRA_TARGET, targetPkg);
        startActivity(i);
    }

    private Set<String> resolveLaunchers() {
        Set<String> set = new HashSet<>();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> resolved = getPackageManager().queryIntentActivities(home, 0);
        for (ResolveInfo ri : resolved) set.add(ri.activityInfo.packageName);
        return set;
    }

    @Override
    public void onInterrupt() {
    }
}
