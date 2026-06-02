(function () {
  'use strict';

  const cap = window.Capacitor;
  const isNative = !!(cap && cap.isNativePlatform && cap.isNativePlatform());
  const plugin = isNative && cap.Plugins ? cap.Plugins.MazalNative : null;

  // Web fallback: when running in a plain browser there is no native plugin,
  // so these no-op (the draw screen still works as a PWA).
  window.MazalNative = {
    isNative: isNative,

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

    // Fires when a blocked app is opened while Mazal is already in memory.
    addInterceptListener(cb) {
      if (!plugin || !plugin.addListener) return;
      plugin.addListener('intercept', data => cb(data && data.packageName));
    }
  };
})();
