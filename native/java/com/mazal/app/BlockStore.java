package com.mazal.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** SharedPreferences-backed store for the blocked-app list and the post-continue grace window. */
public final class BlockStore {
    private static final String PREFS = "mazal_prefs";
    private static final String KEY_BLOCKED = "blocked_packages";
    private static final String KEY_GRACE_PKG = "grace_pkg";
    private static final String KEY_GRACE_UNTIL = "grace_until";
    // Short grace only to absorb the continue → target transition flicker, so that
    // virtually every genuine re-entry still triggers a fresh draw.
    private static final long DEFAULT_GRACE_MS = 2000L;

    private BlockStore() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Set<String> getBlocked(Context ctx) {
        // Copy out — the Set returned by getStringSet must not be mutated.
        return new HashSet<>(prefs(ctx).getStringSet(KEY_BLOCKED, Collections.<String>emptySet()));
    }

    public static void setBlocked(Context ctx, Set<String> packages) {
        prefs(ctx).edit().putStringSet(KEY_BLOCKED, new HashSet<>(packages)).apply();
    }

    public static void setGrace(Context ctx, String pkg) {
        prefs(ctx).edit()
                .putString(KEY_GRACE_PKG, pkg)
                .putLong(KEY_GRACE_UNTIL, System.currentTimeMillis() + DEFAULT_GRACE_MS)
                .apply();
    }

    public static boolean isInGrace(Context ctx, String pkg) {
        SharedPreferences p = prefs(ctx);
        return pkg.equals(p.getString(KEY_GRACE_PKG, null))
                && System.currentTimeMillis() < p.getLong(KEY_GRACE_UNTIL, 0L);
    }
}
