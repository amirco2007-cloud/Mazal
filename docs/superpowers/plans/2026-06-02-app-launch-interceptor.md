# App-Launch Interceptor (Feature 1) Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking. NOTE: This is an Android-native feature with no practical unit-test harness for the OS-integration parts; the primary verification is on-device. TDD is applied only to pure-logic helpers. Do not force test-first on AccessibilityService / PackageManager code.

**Goal:** Before the user opens chosen apps (e.g. WhatsApp, Telegram, a dating app), the Mazal "draw" screen pops up first; after drawing any result the user can always continue to the target app.

**Architecture:** Keep the existing PWA web UI for the draw + settings screens (Approach A). Commit a Capacitor Android project to the repo so native Kotlin code persists. A local Capacitor plugin (`MazalNative`) bridges JS ↔ native for: listing installed apps, reading/writing the blocked-app list, checking/opening the Accessibility settings, and continuing to a target app. A native `AccessibilityService` watches foreground app changes and launches Mazal in "intercept mode" when a blocked app comes to the foreground.

**Tech Stack:** HTML/CSS/JS (existing), Capacitor 6, Kotlin, Android AccessibilityService, SharedPreferences, GitHub Actions (existing APK build).

---

## Key design decisions (locked during brainstorming)

- **Continue is always allowed** — even on a "miss", a "המשך ל-{app}" button appears. Pure reflection, no blocking, no timers.
- **App selection** — a settings screen inside Mazal lists installed (launchable) apps with checkboxes.
- **Platform** — Android only (sideloaded APK). Accessibility permission is granted manually by the user; Play Store restrictions are irrelevant.
- **Spotify (Feature 2) is OUT OF SCOPE** for this plan — separate plan, separate APK.

## The loop hazard (most important correctness concern)

Naively, this happens: blocked app → Mazal pops over it → user taps continue → target app returns to foreground → AccessibilityService fires again → Mazal pops again → infinite loop.

Mitigations, all required:
1. **Grace window:** when user continues to package `P`, store `gracePkg=P` + `graceUntil=now+8000ms`. The service ignores `P` while `now < graceUntil`.
2. **Foreground-change edge detection:** only trigger when the foreground package *transitions* from something else into a blocked package (track `lastForegroundPkg`). `TYPE_WINDOW_STATE_CHANGED` fires repeatedly for the same app; without edge detection it re-triggers.
3. **Self/exclusion list:** never trigger for our own package, the current launcher, or `android`/SystemUI.

---

## File Structure

**Native sources committed under `native/` (Java; CI injects them into the generated project — no local toolchain, no committed `android/`):**
- Create: `native/java/com/mazal/app/MainActivity.java` — overwrites the generated one; registers the plugin + `onNewIntent`.
- Create: `native/java/com/mazal/app/MazalNativePlugin.java` — Capacitor plugin (JS bridge).
- Create: `native/java/com/mazal/app/AppWatchAccessibilityService.java` — foreground watcher + interceptor launcher.
- Create: `native/java/com/mazal/app/BlockStore.java` — SharedPreferences wrapper.
- Create: `native/res/xml/accessibility_service_config.xml` — service config.
- Create: `native/res/values/strings_mazal.xml` — accessibility service description string.
- Create: `native/inject.py` — copies the above into the generated `android/` tree and patches `AndroidManifest.xml` (`<queries>` + `<service>`).

**Web (existing files in repo root of `Mazal/`):**
- Modify: `index.html` — add settings view + interceptor "continue" button container.
- Modify: `styles.css` — styles for settings list + continue button.
- Modify: `app.js` — settings UI logic, permission prompt, intercept-mode handling, calls into `MazalNative`.
- Create: `mazal-native.js` — thin JS wrapper over the Capacitor plugin with a web fallback (so the PWA still loads in a browser).
- Modify: `sw.js` — bump cache to `fortune-v3`, add `mazal-native.js` to `ASSETS`.

**CI:**
- Modify: `.github/workflows/build-apk.yml` — after `cap add android`, run `python native/inject.py` to drop in native sources + patch the manifest, then `cap sync` + gradle build. (Keeps regeneration; adds injection.)

**Optional pure-logic tests (only if a JS test runner is cheap to add):**
- `tests/grace.test.js` — the should-trigger decision function, extracted as a pure function.

---

> **IMPLEMENTATION DEVIATION (decided after review):** To require zero local Android toolchain, we do NOT commit `android/` and do NOT run `cap add` locally. Instead CI keeps generating the project each build and INJECTS committed native sources from `native/`. All native code is written in **Java** (not Kotlin) so the stock Capacitor Gradle template compiles it without enabling the Kotlin plugin. The Kotlin snippets in Phases 1–2 below are the original design reference; the actual committed files are the Java equivalents under `native/`.

## Phase 0 — CI injects native sources into the generated project

Goal: native Java code persists in `native/` and lands in every CI build, with no committed `android/` and no local tooling.

### Task 0.1: Add the manifest/source injection script

**Files:**
- Create: `native/inject.py`

- [ ] **Step 1: Write `native/inject.py`** — copies `native/java/**` into `android/app/src/main/java/`, copies `native/res/**` into `android/app/src/main/res/`, then patches `android/app/src/main/AndroidManifest.xml`: insert the `<queries>` block before `</manifest>` and the `<service>` block before `</application>` (idempotent — skip if already present). Fail loudly if anchors are missing.

### Task 0.2: Update CI to run injection

**Files:**
- Modify: `.github/workflows/build-apk.yml`

- [ ] **Step 1: Add a step after "Add Android platform" and before "Sync Capacitor":**

```yaml
      - name: Inject native sources and patch manifest
        run: python native/inject.py
```

Everything else (icon generation, `cap sync android`, gradle `assembleDebug`, APK rename, artifact upload, rolling release) stays as-is.

- [ ] **Step 2: Verify CI green** after the native files + web changes exist (push to main → APK in the `apk-latest` release). Expected: APK builds; manifest contains the service + queries.

---

## Phase 1 — Blocked-app list + installed-apps picker

Goal: user can pick which apps to intercept; the choice persists natively. No interception yet.

### Task 1.1: BlockStore (SharedPreferences wrapper)

**Files:**
- Create: `android/app/src/main/java/com/mazal/app/BlockStore.kt`

- [ ] **Step 1: Implement BlockStore**

```kotlin
package com.mazal.app

import android.content.Context

object BlockStore {
    private const val PREFS = "mazal_prefs"
    private const val KEY_BLOCKED = "blocked_packages"
    private const val KEY_GRACE_PKG = "grace_pkg"
    private const val KEY_GRACE_UNTIL = "grace_until"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getBlocked(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_BLOCKED, emptySet())!!.toSet()

    fun setBlocked(ctx: Context, packages: Set<String>) {
        prefs(ctx).edit().putStringSet(KEY_BLOCKED, packages).apply()
    }

    fun setGrace(ctx: Context, pkg: String, durationMs: Long = 8000L) {
        prefs(ctx).edit()
            .putString(KEY_GRACE_PKG, pkg)
            .putLong(KEY_GRACE_UNTIL, System.currentTimeMillis() + durationMs)
            .apply()
    }

    fun isInGrace(ctx: Context, pkg: String, now: Long = System.currentTimeMillis()): Boolean {
        val p = prefs(ctx)
        return p.getString(KEY_GRACE_PKG, null) == pkg && now < p.getLong(KEY_GRACE_UNTIL, 0L)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/mazal/app/BlockStore.kt
git commit -m "feat: native blocked-app store with grace window"
```

### Task 1.2: MazalNative Capacitor plugin

**Files:**
- Create: `android/app/src/main/java/com/mazal/app/MazalNativePlugin.kt`
- Modify: `android/app/src/main/java/com/mazal/app/MainActivity.java`

- [ ] **Step 1: Implement the plugin**

```kotlin
package com.mazal.app

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONObject

@CapacitorPlugin(name = "MazalNative")
class MazalNativePlugin : Plugin() {

    @PluginMethod
    fun getInstalledApps(call: PluginCall) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = JSArray()
        pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != context.packageName }
            .sortedBy { it.second.lowercase() }
            .forEach { (pkg, label) ->
                apps.put(JSObject().put("packageName", pkg).put("label", label))
            }
        call.resolve(JSObject().put("apps", apps))
    }

    @PluginMethod
    fun getBlockedApps(call: PluginCall) {
        val arr = JSArray()
        BlockStore.getBlocked(context).forEach { arr.put(it) }
        call.resolve(JSObject().put("packages", arr))
    }

    @PluginMethod
    fun setBlockedApps(call: PluginCall) {
        val arr = call.getArray("packages") ?: JSArray()
        val set = (0 until arr.length()).map { arr.getString(it) }.toSet()
        BlockStore.setBlocked(context, set)
        call.resolve()
    }

    @PluginMethod
    fun isAccessibilityEnabled(call: PluginCall) {
        call.resolve(JSObject().put("enabled", accessibilityEnabled()))
    }

    @PluginMethod
    fun openAccessibilitySettings(call: PluginCall) {
        val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        call.resolve()
    }

    @PluginMethod
    fun getInterceptTarget(call: PluginCall) {
        // Consume the target package set by the AccessibilityService on the launch intent.
        val pkg = activity?.intent?.getStringExtra(EXTRA_TARGET)
        activity?.intent?.removeExtra(EXTRA_TARGET)
        call.resolve(JSObject().put("packageName", pkg ?: JSONObject.NULL))
    }

    @PluginMethod
    fun continueToApp(call: PluginCall) {
        val pkg = call.getString("packageName")
        if (pkg.isNullOrEmpty()) { call.reject("no package"); return }
        BlockStore.setGrace(context, pkg)
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) { call.reject("cannot launch $pkg"); return }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        call.resolve()
    }

    private fun accessibilityEnabled(): Boolean {
        val expected = ComponentName(context, AppWatchAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) if (splitter.next().equals(expected, true)) return true
        return false
    }

    companion object { const val EXTRA_TARGET = "mazal_intercept_target" }
}
```

- [ ] **Step 2: Register the plugin in MainActivity**

In `MainActivity.java` (convert to Kotlin optionally), inside `onCreate` before `super.onCreate(savedInstanceState)` is fine via `registerPlugin`:

```java
import com.mazal.app.MazalNativePlugin;
// ...
public class MainActivity extends BridgeActivity {
  @Override
  public void onCreate(Bundle savedInstanceState) {
    registerPlugin(MazalNativePlugin.class);
    super.onCreate(savedInstanceState);
  }
}
```

- [ ] **Step 3: Add `<queries>` and package-launch visibility to AndroidManifest.xml**

Inside `<manifest>` (sibling of `<application>`):

```xml
<queries>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent>
</queries>
```

(If listing proves incomplete on the device, fall back to `<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />` — acceptable for sideload.)

- [ ] **Step 4: Build APK via CI, install on phone, verify** (no automated test for PackageManager). Expected after Phase 1 web UI (Task 1.3): settings list shows installed apps; selections persist across app restarts.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/mazal/app/MazalNativePlugin.kt android/app/src/main/java/com/mazal/app/MainActivity.* android/app/src/main/AndroidManifest.xml
git commit -m "feat: MazalNative plugin (installed apps, blocked list, accessibility, continue)"
```

### Task 1.3: Web settings UI + native JS wrapper

**Files:**
- Create: `mazal-native.js`
- Modify: `index.html`, `styles.css`, `app.js`, `sw.js`

- [ ] **Step 1: Create `mazal-native.js`** — wrapper with web fallback so the PWA still loads in a browser:

```js
(function () {
  const cap = window.Capacitor;
  const native = cap && cap.isNativePlatform && cap.isNativePlatform();
  const plugin = native ? cap.Plugins.MazalNative : null;

  window.MazalNative = {
    isNative: !!native,
    async getInstalledApps() {
      if (!plugin) return { apps: [] };
      return plugin.getInstalledApps();
    },
    async getBlockedApps() {
      if (!plugin) return { packages: [] };
      return plugin.getBlockedApps();
    },
    async setBlockedApps(packages) {
      if (!plugin) return;
      return plugin.setBlockedApps({ packages });
    },
    async isAccessibilityEnabled() {
      if (!plugin) return { enabled: false };
      return plugin.isAccessibilityEnabled();
    },
    async openAccessibilitySettings() {
      if (!plugin) return;
      return plugin.openAccessibilitySettings();
    },
    async getInterceptTarget() {
      if (!plugin) return { packageName: null };
      return plugin.getInterceptTarget();
    },
    async continueToApp(packageName) {
      if (!plugin) return;
      return plugin.continueToApp({ packageName });
    },
  };
})();
```

- [ ] **Step 2: Add markup to `index.html`** — a settings section (toggle via a gear button) and a continue-button container in the result area. Add `<script src="./mazal-native.js"></script>` BEFORE `app.js`. Sketch:

```html
<button id="open-settings" type="button" class="btn-gear" aria-label="הגדרות">⚙</button>

<section id="settings" class="settings" hidden>
  <h2 class="settings-title">אפליקציות שיפעילו את מזל</h2>
  <p id="perm-warning" class="perm-warning" hidden>
    יש להפעיל הרשאת נגישות כדי שמזל יקפוץ לפני אפליקציות.
    <button id="grant-perm" type="button">הפעלה</button>
  </p>
  <ul id="app-list" class="app-list"></ul>
  <button id="save-settings" type="button" class="btn-draw">שמירה</button>
  <button id="close-settings" type="button" class="btn-reset">סגירה</button>
</section>

<!-- inside #result flow, after the message -->
<button id="continue-app" type="button" class="btn-continue" hidden></button>
```

- [ ] **Step 3: Style in `styles.css`** — `.btn-gear` (top corner), `.settings` overlay, `.app-list` scrollable checkbox rows, `.perm-warning`, `.btn-continue`. Match the existing maroon/gold palette.

- [ ] **Step 4: Wire logic in `app.js`** — on load: `MazalNative.getInterceptTarget()`; if a package came back, set intercept mode (store target, label the continue button "המשך ל-…"). After a draw in intercept mode, reveal `#continue-app` (always, hit or miss); clicking it calls `MazalNative.continueToApp(target)`. Settings: gear opens `#settings`, populate `#app-list` from `getInstalledApps()` checked against `getBlockedApps()`, save calls `setBlockedApps(selected)`. On opening settings, call `isAccessibilityEnabled()` and toggle `#perm-warning`; the grant button calls `openAccessibilitySettings()`. On a NORMAL launch (`getInterceptTarget()` returns null), ensure intercept mode is off and `#continue-app` stays hidden — don't leave a stale continue button from a previous intercept.

- [ ] **Step 5: Bump SW cache and add the new asset** in `sw.js`:

```js
const CACHE = 'fortune-v3';
const ASSETS = [ './', './index.html', './styles.css', './app.js', './mazal-native.js', './manifest.json', './icons/icon-192.png', './icons/icon-512.png' ];
```

- [ ] **Step 6: Build APK, install on phone, verify** — gear shows installed apps; check a few; save; reopen app → selections persisted; permission warning reflects real state.

- [ ] **Step 7: Commit**

```bash
git add mazal-native.js index.html styles.css app.js sw.js
git commit -m "feat: settings UI to pick intercepted apps + accessibility prompt"
```

---

## Phase 2 — AccessibilityService interception

Goal: opening a blocked app pops Mazal first; continue returns to the app without looping.

### Task 2.1: Accessibility service config + manifest

**Files:**
- Create: `android/app/src/main/res/xml/accessibility_service_config.xml`
- Create: `android/app/src/main/res/values/strings_mazal.xml`
- Modify: `android/app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Service config**

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:notificationTimeout="100"
    android:canRetrieveWindowContent="false"
    android:description="@string/mazal_a11y_desc" />
```

- [ ] **Step 2: Description string** (`strings_mazal.xml`):

```xml
<resources>
    <string name="mazal_a11y_desc">מזל מזהה מתי נפתחת אפליקציה שבחרת ומציג מסך הגרלה קצר לפני הכניסה.</string>
</resources>
```

- [ ] **Step 3: Register the service** inside `<application>` in AndroidManifest.xml:

```xml
<service
    android:name=".AppWatchAccessibilityService"
    android:exported="false"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/res/xml/accessibility_service_config.xml android/app/src/main/res/values/strings_mazal.xml android/app/src/main/AndroidManifest.xml
git commit -m "feat: declare Mazal accessibility service"
```

### Task 2.2: The service itself

**Files:**
- Create: `android/app/src/main/java/com/mazal/app/AppWatchAccessibilityService.kt`

- [ ] **Step 1: Implement** — edge detection + grace + self/launcher exclusion:

```kotlin
package com.mazal.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class AppWatchAccessibilityService : AccessibilityService() {

    private var lastForeground: String? = null

    private val ownPackage by lazy { packageName }
    private val launchers by lazy { resolveLaunchers() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == lastForeground) return            // edge detection
        val previous = lastForeground
        lastForeground = pkg

        if (pkg == ownPackage) return
        if (pkg in launchers) return
        if (pkg == "android" || pkg.startsWith("com.android.systemui")) return
        if (BlockStore.isInGrace(this, pkg)) return
        if (pkg !in BlockStore.getBlocked(this)) return
        if (previous == ownPackage) return           // came back from Mazal itself

        launchInterceptor(pkg)
    }

    private fun launchInterceptor(targetPkg: String) {
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(MazalNativePlugin.EXTRA_TARGET, targetPkg)
        }
        startActivity(i)
    }

    private fun resolveLaunchers(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }.toSet()
    }

    override fun onInterrupt() {}
}
```

NOTE on `getInterceptTarget`: because `MainActivity` may be `singleTop` and reused, the extra is read from `activity.intent`. If a reused activity does not refresh `getIntent()`, add `onNewIntent` handling in MainActivity to call `setIntent(intent)` so the plugin reads the latest target. Include this if device testing shows a stale target.

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/mazal/app/AppWatchAccessibilityService.kt
git commit -m "feat: foreground watcher launches Mazal before blocked apps"
```

### Task 2.3: On-device verification (the real test)

- [ ] **Step 1:** Build APK via CI; install on phone; enable Accessibility for Mazal in system settings.
- [ ] **Step 2:** In Mazal settings, block WhatsApp. Go to home, open WhatsApp → Mazal pops up. Draw → continue button → lands in WhatsApp. Confirm NO loop (Mazal does not re-pop immediately).
- [ ] **Step 3:** Open a non-blocked app → Mazal does NOT pop.
- [ ] **Step 4:** Open a blocked app, draw, press Home instead of continue → no loop, no crash. Re-open the blocked app after grace expires (>8s) → Mazal pops again (expected).
- [ ] **Step 5:** Reboot phone → confirm the service resumes intercepting (Android restarts enabled accessibility services automatically). If not, document the manual re-enable step in README.
- [ ] **Step 6:** Update `Mazal/README.md` — add a "פיצ'ר: מסך לפני אפליקציות" section: how to enable Accessibility, how to pick apps, the grace behavior, and that it never blocks.

```bash
git add Mazal/README.md
git commit -m "docs: document app-launch interceptor feature"
```

---

## Known limitations (acceptable for personal sideload)
- `launchers`/`ownPackage` in the service are resolved once via `by lazy`; if the user switches default launcher later, the cached set is stale until the service restarts.
- An app whose launcher activity package differs from its main UI package could mismatch the grace check — rare, acceptable.

## Out of scope / follow-ups
- **Feature 2 (Spotify per-song stop)** — separate plan. Decision from research: do NOT rely on Spotify's `metadatachanged` broadcasts (Beta, often disabled by default, buggy); use `MediaSessionManager`/`MediaController` via Notification Access. Accept ~1s imperfection and skip-vs-natural-end ambiguity.
- Per-app custom messages, schedules ("only intercept 9pm–1am"), or usage stats — not now (YAGNI).
