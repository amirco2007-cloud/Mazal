(function () {
  'use strict';

  const input = document.getElementById('numbers');
  const drawBtn = document.getElementById('draw');
  const resultEl = document.getElementById('result');
  const resetBtn = document.getElementById('reset');
  const modeBtns = document.querySelectorAll('.mode-btn');

  const interceptNote = document.getElementById('intercept-note');
  const continueBtn = document.getElementById('continue-app');
  const openSettingsBtn = document.getElementById('open-settings');
  const settingsEl = document.getElementById('settings');
  const appListEl = document.getElementById('app-list');
  const saveBtn = document.getElementById('save-settings');
  const closeBtn = document.getElementById('close-settings');
  const permWarning = document.getElementById('perm-warning');
  const grantBtn = document.getElementById('grant-perm');

  const MESSAGES = {
    avoid: {
      hit:  'אתה יכול להמשיך אם אתה מתעקש',
      miss: 'ממש תנסה לוותר הפעם'
    },
    start: {
      hit:  'אתה יכול להתפנק עוד קצת באי עשייה, מקווה שתגיע לזה בהמשך',
      miss: 'אתה צריך לשנס מותניים! קדימה, להתחיל'
    }
  };

  let mode = 'avoid';
  let interceptTarget = null; // package name when launched before a blocked app

  function parseInput(text) {
    const tokens = text.split(/[\s,]+/).filter(Boolean);
    const valid = tokens
      .filter(t => /^\d+$/.test(t))
      .map(Number)
      .filter(n => n >= 1 && n <= 10);
    return new Set(valid);
  }

  function updateDrawState() {
    const set = parseInput(input.value);
    drawBtn.disabled = set.size < 1;
  }

  function setMode(next) {
    mode = next;
    modeBtns.forEach(btn => {
      const active = btn.dataset.mode === next;
      btn.classList.toggle('active', active);
      btn.setAttribute('aria-checked', active ? 'true' : 'false');
    });
  }

  function setLocked(locked) {
    input.disabled = locked;
    drawBtn.disabled = locked || parseInput(input.value).size < 1;
    modeBtns.forEach(btn => { btn.disabled = locked; });
  }

  function randomOneToTen() {
    if (window.crypto && window.crypto.getRandomValues) {
      const max = Math.floor(0xFFFFFFFF / 10) * 10;
      const buf = new Uint32Array(1);
      let n;
      do { window.crypto.getRandomValues(buf); n = buf[0]; } while (n >= max);
      return (n % 10) + 1;
    }
    return Math.floor(Math.random() * 10) + 1;
  }

  function draw() {
    if (drawBtn.disabled) return;
    drawBtn.disabled = true;
    setLocked(true);

    const chosen = parseInput(input.value);
    const result = randomOneToTen();
    const win = chosen.has(result);
    const text = win ? MESSAGES[mode].hit : MESSAGES[mode].miss;

    resultEl.innerHTML = '';
    const numEl = document.createElement('div');
    numEl.className = 'number';
    numEl.textContent = String(result);
    const msgEl = document.createElement('div');
    msgEl.className = 'message ' + (win ? 'win' : 'lose');
    msgEl.textContent = text;
    resultEl.appendChild(numEl);
    resultEl.appendChild(msgEl);

    if (interceptTarget) {
      // Continue is always allowed — pure reflection, no blocking.
      continueBtn.hidden = false;
      continueBtn.focus();
    } else {
      resetBtn.hidden = false;
      resetBtn.focus();
    }
  }

  function reset() {
    input.value = '';
    setLocked(false);
    resultEl.innerHTML = '';
    resetBtn.hidden = true;
    continueBtn.hidden = true;
    input.focus();
  }

  modeBtns.forEach(btn => {
    btn.addEventListener('click', () => setMode(btn.dataset.mode));
  });
  input.addEventListener('input', updateDrawState);
  input.addEventListener('keydown', e => {
    if (e.key === 'Enter' && !drawBtn.disabled) {
      e.preventDefault();
      draw();
    }
  });
  drawBtn.addEventListener('click', draw);
  resetBtn.addEventListener('click', reset);

  continueBtn.addEventListener('click', () => {
    if (!interceptTarget) return;
    continueBtn.disabled = true;
    window.MazalNative.continueToApp(interceptTarget);
  });

  // ---- Intercept mode ----------------------------------------------------

  async function enterInterceptMode(packageName) {
    interceptTarget = packageName;
    interceptNote.hidden = false;
    interceptNote.textContent = 'רגע לפני שאתה נכנס — בוא נגריל';
    continueBtn.textContent = 'המשך לאפליקציה';
    continueBtn.disabled = false;
    // Best-effort: replace with the app's friendly name if we can find it.
    try {
      const { apps } = await window.MazalNative.getInstalledApps();
      const match = (apps || []).find(a => a.packageName === packageName);
      if (match) continueBtn.textContent = 'המשך ל' + match.label;
    } catch (_) { /* keep generic label */ }
  }

  // Fresh launch: read the target the AccessibilityService put on the intent.
  async function applyInterceptMode() {
    try {
      const { packageName } = await window.MazalNative.getInterceptTarget();
      if (!packageName) {
        interceptTarget = null;
        interceptNote.hidden = true;
        continueBtn.hidden = true;
        return;
      }
      await enterInterceptMode(packageName);
    } catch (_) {
      interceptTarget = null;
    }
  }

  // ---- Settings ----------------------------------------------------------

  async function openSettings() {
    settingsEl.hidden = false;
    await refreshPermWarning();
    await populateAppList();
  }

  function closeSettings() {
    settingsEl.hidden = true;
  }

  async function refreshPermWarning() {
    try {
      const { enabled } = await window.MazalNative.isAccessibilityEnabled();
      permWarning.hidden = !!enabled;
    } catch (_) {
      permWarning.hidden = true;
    }
  }

  async function populateAppList() {
    appListEl.innerHTML = '';
    let apps = [];
    let blocked = new Set();
    try {
      const [appsRes, blockedRes] = await Promise.all([
        window.MazalNative.getInstalledApps(),
        window.MazalNative.getBlockedApps()
      ]);
      apps = appsRes.apps || [];
      blocked = new Set(blockedRes.packages || []);
    } catch (_) { /* fall through to empty */ }

    if (!apps.length) {
      const li = document.createElement('li');
      li.className = 'app-list-empty';
      li.textContent = window.MazalNative.isNative
        ? 'לא נמצאו אפליקציות.'
        : 'זמין רק באפליקציה המותקנת בטלפון.';
      appListEl.appendChild(li);
      return;
    }

    apps.forEach(app => {
      const li = document.createElement('li');
      const cb = document.createElement('input');
      cb.type = 'checkbox';
      cb.id = 'app-' + app.packageName;
      cb.value = app.packageName;
      cb.checked = blocked.has(app.packageName);
      const label = document.createElement('label');
      label.setAttribute('for', cb.id);
      label.textContent = app.label;
      li.appendChild(cb);
      li.appendChild(label);
      appListEl.appendChild(li);
    });
  }

  async function saveSettings() {
    const checked = appListEl.querySelectorAll('input[type="checkbox"]:checked');
    const packages = Array.from(checked).map(cb => cb.value);
    try {
      await window.MazalNative.setBlockedApps(packages);
    } catch (_) { /* no-op on web */ }
    closeSettings();
  }

  openSettingsBtn.addEventListener('click', openSettings);
  closeBtn.addEventListener('click', closeSettings);
  saveBtn.addEventListener('click', saveSettings);
  grantBtn.addEventListener('click', () => window.MazalNative.openAccessibilitySettings());

  // ---- Boot --------------------------------------------------------------

  applyInterceptMode();

  // Activity reused while in memory: a new blocked app fires this event.
  window.MazalNative.addInterceptListener(packageName => {
    if (!packageName) return;
    reset();
    closeSettings();
    enterInterceptMode(packageName);
  });

  if ('serviceWorker' in navigator) {
    window.addEventListener('load', () => {
      navigator.serviceWorker.register('./sw.js').catch(() => {});
    });
  }
})();
