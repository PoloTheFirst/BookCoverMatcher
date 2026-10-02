import { OPENCV_URL, EXCELJS_URL, JSZIP_URL } from './config.js';
import { state } from './state.js';
import { loadScript } from './utils.js';

let excelJsPromise = null;
export function ensureExcelJS() {
  if (window.ExcelJS) return Promise.resolve();
  if (!excelJsPromise) excelJsPromise = loadScript(EXCELJS_URL);
  return excelJsPromise.then(() => {
    if (!window.ExcelJS) throw new Error('ExcelJS failed to initialise.');
  });
}

let jsZipPromise = null;
export function ensureJSZip() {
  if (window.JSZip) return Promise.resolve();
  if (!jsZipPromise) jsZipPromise = loadScript(JSZIP_URL);
  return jsZipPromise.then(() => {
    if (!window.JSZip) throw new Error('JSZip failed to initialise.');
  });
}

function waitForOpenCV() {
  if (window.cv && window.cv.Mat) return Promise.resolve();
  return new Promise((resolve, reject) => {
    let settled = false;
    const finish = () => { if (!settled) { settled = true; resolve(); } };
    try { window.cv['onRuntimeInitialized'] = finish; } catch (e) {}
    const started = Date.now();
    const iv = setInterval(() => {
      if (window.cv && window.cv.Mat) {
        clearInterval(iv);
        finish();
      } else if (Date.now() - started > 30000) {
        clearInterval(iv);
        reject(new Error('OpenCV runtime timed out'));
      }
    }, 100);
  });
}

let openCvPromise = null;
export function ensureOpenCV() {
  if (state.cvIsReady) return Promise.resolve(true);
  if (!openCvPromise) {
    openCvPromise = loadScript(OPENCV_URL)
      .then(waitForOpenCV)
      .then(() => {
        state.cvIsReady = true;
        return true;
      })
      .catch((err) => {
        console.warn('[OpenCV] unavailable, using pure-JS DCT fallback:', err);
        return false;
      });
  }
  return openCvPromise;
}