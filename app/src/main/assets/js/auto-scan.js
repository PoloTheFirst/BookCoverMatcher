import { state } from './state.js';
import { setBadge } from './utils.js';
import { grabVideoFrame } from './camera.js';
import { computePHashFromCanvas, hammingDistance } from './phash.js';
import {
  AUTO_SCAN_INTERVAL_MS,
  AUTO_SCAN_STABLE_FRAMES,
  AUTO_SCAN_STABLE_DISTANCE,
  AUTO_SCAN_DUPLICATE_DISTANCE,
  AUTO_SCAN_COOLDOWN_MS,
  AUTO_SCAN_BLUR_THRESHOLD,
  AUTO_SCAN_DARK_THRESHOLD,
  AUTO_SCAN_BRIGHT_THRESHOLD,
  AUTO_SCAN_RESET_BAD_FRAMES,
  AUTO_SCAN_STORAGE_KEY,
} from './config.js';

/* ---------- module state ---------- */
let timer = null;
let onCaptureCallback = null;

let prevHash = null;
let stableCount = 0;
let cooldownUntil = 0;
let lastAcceptedHash = null;
let badFrameCount = 0;

/* ---------- reusable analysis canvas ---------- */
let analysisCanvas = null;
let analysisCtx = null;
const ANALYSIS_SIZE = 64;

function ensureAnalysisCanvas() {
  if (!analysisCanvas) {
    analysisCanvas = document.createElement('canvas');
    analysisCanvas.width  = ANALYSIS_SIZE;
    analysisCanvas.height = ANALYSIS_SIZE;
    analysisCtx = analysisCanvas.getContext('2d', { willReadFrequently: true });
  }
}

/**
 * Compute a lightweight quality score for the current frame.
 * - blur:      Laplacian variance on a 64×64 grayscale (higher = sharper)
 * - meanLum:   average brightness (0..255)
 */
function analyzeQuality(canvas) {
  ensureAnalysisCanvas();
  const s = ANALYSIS_SIZE;
  analysisCtx.drawImage(canvas, 0, 0, s, s);
  const px = analysisCtx.getImageData(0, 0, s, s).data;

  const gray = new Float32Array(s * s);
  let sum = 0;
  for (let i = 0; i < s * s; i++) {
    const o = i * 4;
    const g = 0.299 * px[o] + 0.587 * px[o + 1] + 0.114 * px[o + 2];
    gray[i] = g;
    sum += g;
  }
  const meanLum = sum / (s * s);

  // 4-neighbor Laplacian
  let lapSum = 0, lapSumSq = 0, count = 0;
  for (let y = 1; y < s - 1; y++) {
    const row = y * s;
    for (let x = 1; x < s - 1; x++) {
      const i = row + x;
      const lap = gray[i - s] + gray[i + s] + gray[i - 1] + gray[i + 1] - 4 * gray[i];
      lapSum   += lap;
      lapSumSq += lap * lap;
      count++;
    }
  }
  const lapMean = lapSum / count;
  const variance = (lapSumSq / count) - (lapMean * lapMean);

  return { blur: variance, meanLum };
}

/* ---------- the loop ---------- */

function resetStability() {
  prevHash = null;
  stableCount = 0;
}

function resetAccepted() {
  lastAcceptedHash = null;
  badFrameCount = 0;
}

function tick() {
  if (!state.autoScanEnabled) return;
  if (state.busy) return;

  // Camera not live yet: silent, just wait.
  if (!state.stream || !state.videoReady) {
    // videoReady is optional — we also guard via grabVideoFrame try/catch
  }

  const now = Date.now();

  if (now < cooldownUntil) {
    setBadge('Captured ✓');
    return;
  }

  let frame;
  try {
    frame = grabVideoFrame();
  } catch (e) {
    // Stream not ready yet — keep the badge quiet
    return;
  }

  const { blur, meanLum } = analyzeQuality(frame);

  if (blur < AUTO_SCAN_BLUR_THRESHOLD) {
    resetStability();
    badFrameCount++;
    if (badFrameCount >= AUTO_SCAN_RESET_BAD_FRAMES) resetAccepted();
    setBadge('Hold still…');
    return;
  }
  if (meanLum < AUTO_SCAN_DARK_THRESHOLD) {
    resetStability();
    badFrameCount++;
    if (badFrameCount >= AUTO_SCAN_RESET_BAD_FRAMES) resetAccepted();
    setBadge('Too dark');
    return;
  }
  if (meanLum > AUTO_SCAN_BRIGHT_THRESHOLD) {
    resetStability();
    badFrameCount++;
    if (badFrameCount >= AUTO_SCAN_RESET_BAD_FRAMES) resetAccepted();
    setBadge('Too bright');
    return;
  }

  // Quality is good — reset the "moving away" counter
  badFrameCount = 0;

  // Compute hash
  let hash;
  try {
    hash = computePHashFromCanvas(frame);
  } catch (e) {
    return;
  }

  // Stability check
  if (prevHash) {
    const d = hammingDistance(prevHash, hash);
    if (d <= AUTO_SCAN_STABLE_DISTANCE) {
      stableCount++;
    } else {
      stableCount = 0;
    }
  } else {
    stableCount = 0;
  }
  prevHash = hash;

  if (stableCount < AUTO_SCAN_STABLE_FRAMES) {
    setBadge(`Stabilizing… ${stableCount}/${AUTO_SCAN_STABLE_FRAMES}`);
    return;
  }

  // Duplicate check — same cover, don't re-fire
  if (lastAcceptedHash) {
    const d = hammingDistance(lastAcceptedHash, hash);
    if (d <= AUTO_SCAN_DUPLICATE_DISTANCE) {
      resetStability();
      cooldownUntil = now + AUTO_SCAN_COOLDOWN_MS;
      setBadge('Same cover — waiting');
      return;
    }
  }

  // Accept
  lastAcceptedHash = hash;
  resetStability();
  cooldownUntil = now + AUTO_SCAN_COOLDOWN_MS;
  setBadge('Captured ✓');

  // Haptic (best effort)
  if (navigator.vibrate) {
    try { navigator.vibrate(30); } catch (e) {}
  }

  if (onCaptureCallback) {
    try { onCaptureCallback(frame, hash); }
    catch (e) { console.warn('[auto-scan] capture callback failed:', e); }
  }
}

/* ---------- public API ---------- */

export function initAutoScan(onCapture) {
  onCaptureCallback = onCapture;
  try {
    if (localStorage.getItem(AUTO_SCAN_STORAGE_KEY) === '1') {
      state.autoScanEnabled = true;
    }
  } catch (e) {}
  return state.autoScanEnabled;
}

export function isAutoScanEnabled() {
  return !!state.autoScanEnabled;
}

export function setAutoScanEnabled(enabled) {
  state.autoScanEnabled = !!enabled;
  try {
    localStorage.setItem(AUTO_SCAN_STORAGE_KEY, enabled ? '1' : '0');
  } catch (e) {}

  if (state.autoScanEnabled) {
    if (!timer) {
      timer = setInterval(tick, AUTO_SCAN_INTERVAL_MS);
    }
    // Immediate feedback
    if (state.stream) setBadge('Auto-scan on');
    else               setBadge('Auto-scan armed');
  } else {
    if (timer) { clearInterval(timer); timer = null; }
    resetStability();
    resetAccepted();
    cooldownUntil = 0;
    if (state.stream) setBadge('Live');
    else               setBadge('Tap Scan to start');
  }
}

export function resetAutoScanMemory() {
  resetStability();
  resetAccepted();
  cooldownUntil = 0;
}