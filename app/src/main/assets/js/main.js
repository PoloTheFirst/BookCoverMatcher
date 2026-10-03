import {
  ensureExcelJS, ensureJSZip, ensureOpenCV
} from './libraries.js';
import {
  startCamera, stopCamera, grabVideoFrame
} from './camera.js';
import { computePHashFromCanvas } from './phash.js';
import { makeThumb, fileToCanvas } from './image.js';
import { parseRange, extractAllImages } from './excel.js';
import { rankMatches } from './matcher.js';
import { updateScanPreview, renderResults } from './ui.js';
import { initWishlist } from './wishlist.js';
import {
  initAutoScan, setAutoScanEnabled, isAutoScanEnabled
} from './auto-scan.js';
import { state } from './state.js';
import {
  fileXlsx, rangeInput, fileImage,
  btnMatch, btnScan, btnRestart, btnAutoScan
} from './dom.js';
import {
  toast, setBusy, setBadge, readFileAsArrayBuffer
} from './utils.js';
import {
  THUMB_SCAN_W, THUMB_SCAN_H, TOP_MATCHES
} from './config.js';

/* ---------- capture handlers ---------- */

  function applyCapture(canvas, hash, origin) {
    state.scanHash = hash;
    state.scanThumbUrl = makeThumb(canvas, THUMB_SCAN_W, THUMB_SCAN_H);
    state.lastRanked = [];
    renderResults([]);
    updateScanPreview();
    if (origin === 'auto') {
      // Badge is already managed by auto-scan
    } else {
      toast('Cover captured — pHash computed.', 'ok');
    }
  }

  /**
   * Handles a new capture (manual, auto, or from file) and then performs the online search.
   */
  async function handleCapture(canvas, hash, origin) {
    // Capture handling without online search (Phase -1 removal)
    applyCapture(canvas, hash, origin);
    // No further online processing.
  }

  btnScan.addEventListener('click', async () => {
    if (state.busy) return;
    if (!state.stream) {
      if (await startCamera()) toast('Camera ready — tap Scan again to capture.', 'ok');
      return;
    }
    try {
      const canvas = grabVideoFrame();
      const hash = computePHashFromCanvas(canvas);
      // Use handleCapture to also trigger online search
      await handleCapture(canvas, hash, 'manual');
    } catch (err) {
      toast(err.message || 'Could not capture the frame.', 'err');
    }
  });

btnRestart.addEventListener('click', () => { startCamera(); });

fileImage.addEventListener('change', async () => {
  const f = fileImage.files && fileImage.files[0];
  if (!f) return;
  try {
    const canvas = await fileToCanvas(f);
    const hash = computePHashFromCanvas(canvas);
    // Use handleCapture to also trigger online search
    await handleCapture(canvas, hash, 'manual');
  } catch (err) {
    toast('Could not read image: ' + (err.message || err), 'err');
  }
});

/* ---------- auto-scan toggle ---------- */

function syncAutoToggleUI() {
  if (isAutoScanEnabled()) {
    btnAutoScan.classList.add('active');
    btnAutoScan.setAttribute('aria-pressed', 'true');
    btnAutoScan.title = 'Auto-scan: ON (tap to switch to Manual)';
  } else {
    btnAutoScan.classList.remove('active');
    btnAutoScan.setAttribute('aria-pressed', 'false');
    btnAutoScan.title = 'Auto-scan: OFF (tap to switch to Auto)';
  }
}

btnAutoScan.addEventListener('click', async () => {
  if (state.busy) return;
  const next = !isAutoScanEnabled();
  setAutoScanEnabled(next);
  syncAutoToggleUI();
  if (next && !state.stream) {
    await startCamera();
  }
});

/* ---------- matching ---------- */

btnMatch.addEventListener('click', async () => {
  if (state.busy) return;

  if (!state.scanHash) {
    toast('Scan a book cover (or upload an image) first.', 'err');
    return;
  }

  const file = fileXlsx.files && fileXlsx.files[0];
  if (!file) {
    toast('Choose an .xlsx or .xlsm spreadsheet first.', 'err');
    return;
  }

  let range;
  try { range = parseRange(rangeInput.value); }
  catch (err) { toast(err.message, 'err'); return; }

  try {
    setBusy(true, 'Loading libraries…');
    await ensureExcelJS();
    await ensureJSZip();
    await ensureOpenCV();

    setBusy(true, 'Reading spreadsheet…');
    const arrayBuffer = await readFileAsArrayBuffer(file);

    const workbook = new window.ExcelJS.Workbook();
    await workbook.xlsx.load(arrayBuffer.slice(0));

    const sheet = workbook.worksheets[0];
    if (!sheet) throw new Error('The workbook contains no worksheets.');

    setBusy(true, 'Extracting images…');
    state.excelImages = await extractAllImages(arrayBuffer, workbook, sheet, range);

    if (!state.excelImages.length) {
      state.lastRanked = [];
      renderResults([]);
      toast('No images found inside ' + rangeInput.value.toUpperCase() +
            ' (checked floating images and comment fills).', 'err');
      return;
    }

    setBusy(true, 'Ranking…');
    const ranked = rankMatches(state.scanHash, state.excelImages, TOP_MATCHES);
    state.lastRanked = ranked;
    renderResults(ranked);
    toast('Compared against ' + state.excelImages.length + ' image(s).', 'ok');

  } catch (err) {
    console.error(err);
    toast('Error: ' + (err && err.message ? err.message : err), 'err');
  } finally {
    setBusy(false);
  }
});

/* ---------- boot ---------- */

(function init() {
  setBadge('Tap Scan to start');
  ensureExcelJS().catch((e) => console.warn(e));
  ensureJSZip().catch((e) => console.warn(e));
  ensureOpenCV().catch(() => {});
  initWishlist();

  // Wire auto-scan with a capture callback
  initAutoScan(async (canvas, hash) => {
    // Use handleCapture to also trigger online search for auto captures
    await handleCapture(canvas, hash, 'auto');
  });
  syncAutoToggleUI();
})();

let resumeCamera = false;
document.addEventListener('visibilitychange', () => {
  if (document.hidden) {
    if (state.stream) { resumeCamera = true; stopCamera(); }
  } else if (resumeCamera) {
    resumeCamera = false;
    startCamera();
  }
});
window.addEventListener('pagehide', stopCamera);