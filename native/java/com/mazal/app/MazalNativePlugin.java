package com.mazal.app;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.text.TextUtils;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@CapacitorPlugin(name = "MazalNative")
public class MazalNativePlugin extends Plugin {

    public static final String EXTRA_TARGET = "mazal_intercept_target";
    public static final String EXTRA_REASON = "mazal_intercept_reason";

    @PluginMethod
    public void getInstalledApps(PluginCall call) {
        PackageManager pm = getContext().getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(launcher, 0);

        Set<String> seen = new HashSet<>();
        List<JSObject> items = new ArrayList<>();
        String self = getContext().getPackageName();
        for (ResolveInfo ri : resolved) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(self) || !seen.add(pkg)) continue;
            String label = ri.loadLabel(pm).toString();
            JSObject o = new JSObject();
            o.put("packageName", pkg);
            o.put("label", label);
            items.add(o);
        }
        Collections.sort(items, new Comparator<JSObject>() {
            @Override public int compare(JSObject a, JSObject b) {
                return a.getString("label", "").compareToIgnoreCase(b.getString("label", ""));
            }
        });

        JSArray apps = new JSArray();
        for (JSObject o : items) apps.put(o);
        JSObject ret = new JSObject();
        ret.put("apps", apps);
        call.resolve(ret);
    }

    @PluginMethod
    public void getBlockedApps(PluginCall call) {
        JSArray arr = new JSArray();
        for (String pkg : BlockStore.getBlocked(getContext())) arr.put(pkg);
        JSObject ret = new JSObject();
        ret.put("packages", arr);
        call.resolve(ret);
    }

    @PluginMethod
    public void setBlockedApps(PluginCall call) {
        JSArray arr = call.getArray("packages", new JSArray());
        Set<String> set = new HashSet<>();
        try {
            for (int i = 0; i < arr.length(); i++) set.add(arr.getString(i));
        } catch (org.json.JSONException e) {
            call.reject("bad packages array");
            return;
        }
        BlockStore.setBlocked(getContext(), set);
        call.resolve();
    }

    @PluginMethod
    public void isAccessibilityEnabled(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("enabled", accessibilityEnabled());
        call.resolve(ret);
    }

    @PluginMethod
    public void openAccessibilitySettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    @PluginMethod
    public void getInterceptTarget(PluginCall call) {
        JSObject ret = new JSObject();
        String pkg = null;
        String reason = null;
        if (getActivity() != null && getActivity().getIntent() != null) {
            pkg = getActivity().getIntent().getStringExtra(EXTRA_TARGET);
            reason = getActivity().getIntent().getStringExtra(EXTRA_REASON);
            getActivity().getIntent().removeExtra(EXTRA_TARGET);
            getActivity().getIntent().removeExtra(EXTRA_REASON);
        }
        if (pkg == null) ret.put("packageName", org.json.JSONObject.NULL);
        else ret.put("packageName", pkg);
        if (reason == null) ret.put("reason", org.json.JSONObject.NULL);
        else ret.put("reason", reason);
        call.resolve(ret);
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        // Activity reused (already in memory): notify the web layer so it can
        // re-enter intercept mode without a full page reload.
        String pkg = intent.getStringExtra(EXTRA_TARGET);
        if (pkg != null) {
            String reason = intent.getStringExtra(EXTRA_REASON);
            JSObject data = new JSObject();
            data.put("packageName", pkg);
            data.put("reason", reason);
            notifyListeners("intercept", data);
        }
    }

    @PluginMethod
    public void continueToApp(PluginCall call) {
        String pkg = call.getString("packageName");
        if (pkg == null || pkg.isEmpty()) { call.reject("no package"); return; }
        BlockStore.setGrace(getContext(), pkg);
        Intent launch = getContext().getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) { call.reject("cannot launch " + pkg); return; }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(launch);
        call.resolve();
    }

    private boolean accessibilityEnabled() {
        String expected = new ComponentName(getContext(), AppWatchAccessibilityService.class)
                .flattenToString();
        String enabled = Settings.Secure.getString(
                getContext().getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) {
            if (splitter.next().equalsIgnoreCase(expected)) return true;
        }
        return false;
    }
}
