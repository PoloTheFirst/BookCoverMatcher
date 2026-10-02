import { state } from './state.js';

function hashFromCoeffs(coeffs) {
  const ac = coeffs.slice(1).sort((a, b) => a - b);
  const mid = Math.floor(ac.length / 2);
  const median = (ac.length % 2) ? ac[mid] : (ac[mid - 1] + ac[mid]) / 2;
  let bits = '';
  for (let i = 0; i < coeffs.length; i++) bits += (coeffs[i] > median) ? '1' : '0';
  return bits;
}

function phashWithOpenCV(canvas) {
  const cv = window.cv;
  let src = null, gray = null, blurred = null, small = null, f32 = null, dctMat = null;
  try {
    src = cv.imread(canvas);
    gray = new cv.Mat();
    cv.cvtColor(src, gray, cv.COLOR_RGBA2GRAY);

    cv.equalizeHist(gray, gray);

    blurred = new cv.Mat();
    cv.GaussianBlur(gray, blurred, new cv.Size(3, 3), 0, 0, cv.BORDER_DEFAULT);

    small = new cv.Mat();
    cv.resize(blurred, small, new cv.Size(32, 32), 0, 0, cv.INTER_AREA);

    f32 = new cv.Mat();
    small.convertTo(f32, cv.CV_32F);
    dctMat = new cv.Mat();
    cv.dct(f32, dctMat);

    const d = dctMat.data32F;
    const coeffs = new Array(64);
    for (let r = 0; r < 8; r++)
      for (let c = 0; c < 8; c++)
        coeffs[r * 8 + c] = d[r * 32 + c];
    return hashFromCoeffs(coeffs);
  } finally {
    if (src)     src.delete();
    if (gray)    gray.delete();
    if (blurred) blurred.delete();
    if (small)   small.delete();
    if (f32)     f32.delete();
    if (dctMat)  dctMat.delete();
  }
}

const DCT_COS = (() => {
  const t = new Float64Array(32 * 32);
  for (let u = 0; u < 32; u++)
    for (let x = 0; x < 32; x++)
      t[u * 32 + x] = Math.cos(((2 * x + 1) * u * Math.PI) / 64);
  return t;
})();

function equalizeInPlace(gray) {
  const n = gray.length;
  const hist = new Uint32Array(256);
  for (let i = 0; i < n; i++) {
    const v = gray[i] | 0;
    hist[v < 0 ? 0 : (v > 255 ? 255 : v)]++;
  }
  const cdf = new Uint32Array(256);
  let acc = 0;
  for (let i = 0; i < 256; i++) { acc += hist[i]; cdf[i] = acc; }
  let cdfMin = 0;
  for (let i = 0; i < 256; i++) { if (cdf[i] > 0) { cdfMin = cdf[i]; break; } }
  const scale = 255 / Math.max(1, n - cdfMin);
  const lut = new Uint8Array(256);
  for (let i = 0; i < 256; i++) {
    const v = Math.round((cdf[i] - cdfMin) * scale);
    lut[i] = v < 0 ? 0 : (v > 255 ? 255 : v);
  }
  for (let i = 0; i < n; i++) {
    const v = gray[i] | 0;
    gray[i] = lut[v < 0 ? 0 : (v > 255 ? 255 : v)];
  }
}

function downscaleTo32(canvas) {
  let cur = canvas, cw = canvas.width, ch = canvas.height;

  while (cw > 64 || ch > 64) {
    const nw = Math.max(32, cw >> 1);
    const nh = Math.max(32, ch >> 1);
    const c = document.createElement('canvas');
    c.width = nw; c.height = nh;
    const ctx = c.getContext('2d', { willReadFrequently: true });
    ctx.imageSmoothingEnabled = true;
    ctx.imageSmoothingQuality = 'high';
    ctx.drawImage(cur, 0, 0, cw, ch, 0, 0, nw, nh);
    cur = c; cw = nw; ch = nh;
  }

  const small = document.createElement('canvas');
  small.width = 32; small.height = 32;
  const ctx = small.getContext('2d', { willReadFrequently: true });
  ctx.imageSmoothingEnabled = true;
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(cur, 0, 0, cw, ch, 0, 0, 32, 32);
  return ctx.getImageData(0, 0, 32, 32).data;
}

function phashPureJS(canvas) {
  const px = downscaleTo32(canvas);

  const gray = new Float64Array(1024);
  for (let i = 0; i < 1024; i++) {
    const o = i * 4;
    gray[i] = 0.299 * px[o] + 0.587 * px[o + 1] + 0.114 * px[o + 2];
  }

  // 3x3 box blur, separable
  const tmp = new Float64Array(1024);
  const out = new Float64Array(1024);
  for (let y = 0; y < 32; y++) {
    for (let x = 0; x < 32; x++) {
      const x0 = x > 0 ? x - 1 : 0, x1 = x < 31 ? x + 1 : 31;
      tmp[y * 32 + x] = (gray[y * 32 + x0] + gray[y * 32 + x] + gray[y * 32 + x1]) / 3;
    }
  }
  for (let x = 0; x < 32; x++) {
    for (let y = 0; y < 32; y++) {
      const y0 = y > 0 ? y - 1 : 0, y1 = y < 31 ? y + 1 : 31;
      out[y * 32 + x] = (tmp[y0 * 32 + x] + tmp[y * 32 + x] + tmp[y1 * 32 + x]) / 3;
    }
  }

  equalizeInPlace(out);

  const dct = new Float64Array(1024);
  for (let y = 0; y < 32; y++) {
    const rowOff = y * 32;
    for (let u = 0; u < 32; u++) {
      const cosOff = u * 32;
      let s = 0;
      for (let x = 0; x < 32; x++) s += out[rowOff + x] * DCT_COS[cosOff + x];
      tmp[rowOff + u] = s;
    }
  }
  for (let x = 0; x < 32; x++) {
    for (let v = 0; v < 32; v++) {
      const cosOff = v * 32;
      let s = 0;
      for (let y = 0; y < 32; y++) s += tmp[y * 32 + x] * DCT_COS[cosOff + y];
      dct[v * 32 + x] = s;
    }
  }

  const coeffs = new Array(64);
  for (let r = 0; r < 8; r++)
    for (let c = 0; c < 8; c++)
      coeffs[r * 8 + c] = dct[r * 32 + c];
  return hashFromCoeffs(coeffs);
}

export function computePHashFromCanvas(canvas) {
  if (state.cvIsReady && window.cv && window.cv.Mat && typeof window.cv.dct === 'function') {
    try { return phashWithOpenCV(canvas); }
    catch (e) { console.warn('[pHash] OpenCV path failed, using fallback:', e); }
  }
  return phashPureJS(canvas);
}

export function hammingDistance(a, b) {
  if (!a || !b || a.length !== b.length) return 64;
  let d = 0;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) d++;
  return d;
}

export function similarityFromDistance(d) {
  if (d <= 0) return 100;
  if (d >= 32) return 0;
  return 100 * (1 - d / 32);
}

export function levelFromDistance(d) {
  if (d <= 5)  return 'high';
  if (d <= 12) return 'mid';
  return 'low';
}