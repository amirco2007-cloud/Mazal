(function () {
  'use strict';

  const input = document.getElementById('numbers');
  const drawBtn = document.getElementById('draw');
  const resultEl = document.getElementById('result');
  const resetBtn = document.getElementById('reset');
  const modeBtns = document.querySelectorAll('.mode-btn');

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

    resetBtn.hidden = false;
    resetBtn.focus();
  }

  function reset() {
    input.value = '';
    setLocked(false);
    resultEl.innerHTML = '';
    resetBtn.hidden = true;
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

  if ('serviceWorker' in navigator) {
    window.addEventListener('load', () => {
      navigator.serviceWorker.register('./sw.js').catch(() => {});
    });
  }
})();
