import { toastEl, btnMatch, btnScan, camBadge } from './dom.js';
import { BTN_MATCH_LABEL } from './config.js';
import { state } from './state.js';

let toastTimer = null;

export function toast(msg, kind) {
  toastEl.textContent = msg;
  toastEl.className = 'show' + (kind ? ' ' + kind : '');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { toastEl.className = ''; }, 4500);
}

export function setBadge(text) {
  camBadge.textContent = text;
}

export function setBusy(isBusy, label) {
  state.busy = isBusy;
  btnMatch.disabled = isBusy;
  btnScan.disabled  = isBusy;
  btnMatch.textContent = isBusy ? (label || 'Working…') : BTN_MATCH_LABEL;
}

export function loadOneScript(src) {
  return new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.src = src;
    s.async = true;
    s.onload  = () => resolve();
    s.onerror = () => reject(new Error('Could not load ' + src));
    document.head.appendChild(s);
  });
}

export async function loadScript(src) {
  const list = Array.isArray(src) ? src : [src];
  let lastErr = null;
  for (const url of list) {
    try {
      await loadOneScript(url);
      return;
    } catch (e) {
      lastErr = e;
    }
  }
  throw lastErr || new Error('No script sources provided');
}

export function readFileAsArrayBuffer(file) {
  if (typeof file.arrayBuffer === 'function') return file.arrayBuffer();
  return new Promise((resolve, reject) => {
    const fr = new FileReader();
    fr.onload  = () => resolve(fr.result);
    fr.onerror = () => reject(fr.error || new Error('File read error'));
    fr.readAsArrayBuffer(file);
  });
}