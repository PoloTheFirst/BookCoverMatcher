import { GUIDE_W, GUIDE_H } from './config.js';
import { video } from './dom.js';
import { state } from './state.js';
import { setBadge, toast } from './utils.js';

export function stopCamera() {
  if (state.stream) {
    state.stream.getTracks().forEach((t) => t.stop());
    state.stream = null;
  }
}

export async function startCamera() {
  setBadge('Starting…');
  stopCamera();

  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    setBadge('No camera API');
    toast('Camera API unavailable — use the image upload instead.', 'err');
    return false;
  }
  try {
    state.stream = await navigator.mediaDevices.getUserMedia({
      audio: false,
      video: {
        facingMode: { ideal: 'environment' },
        width:  { ideal: 1280 },
        height: { ideal: 1280 }
      }
    });
    video.srcObject = state.stream;
    video.setAttribute('playsinline', '');
    await video.play().catch(() => {});
    setBadge('Live');
    return true;
  } catch (err) {
    setBadge('Camera off');
    const msg = (err && err.name === 'NotAllowedError')
      ? 'Camera permission denied. Upload an image instead.'
      : 'Camera error: ' + (err && err.message ? err.message : err);
    toast(msg, 'err');
    return false;
  }
}

export function grabVideoFrame() {
  const vw = video.videoWidth, vh = video.videoHeight;
  if (!vw || !vh) throw new Error('Camera stream is not ready yet.');

  const rect = video.getBoundingClientRect();
  let dw = Math.round(rect.width);
  let dh = Math.round(rect.height);
  if (dw < 1 || dh < 1) {
    dw = Math.round(vw * GUIDE_W);
    dh = Math.round(vh * GUIDE_H);
  }

  // Reproduce object-fit: cover
  const srcAspect = vw / vh;
  const dstAspect = dw / dh;
  let sx, sy, sw, sh;
  if (srcAspect > dstAspect) {
    sh = vh;
    sw = vh * dstAspect;
    sx = (vw - sw) / 2;
    sy = 0;
  } else {
    sw = vw;
    sh = vw / dstAspect;
    sx = 0;
    sy = (vh - sh) / 2;
  }

  const stage = document.createElement('canvas');
  stage.width = dw; stage.height = dh;
  const sctx = stage.getContext('2d');
  sctx.imageSmoothingEnabled = true;
  sctx.imageSmoothingQuality = 'high';
  sctx.drawImage(video, sx, sy, sw, sh, 0, 0, dw, dh);

  // Crop the guide rectangle
  const gw = Math.max(1, Math.round(dw * GUIDE_W));
  const gh = Math.max(1, Math.round(dh * GUIDE_H));
  const gx = Math.round((dw - gw) / 2);
  const gy = Math.round((dh - gh) / 2);

  const out = document.createElement('canvas');
  out.width = gw; out.height = gh;
  const octx = out.getContext('2d');
  octx.imageSmoothingEnabled = true;
  octx.imageSmoothingQuality = 'high';
  octx.drawImage(stage, gx, gy, gw, gh, 0, 0, gw, gh);
  return out;
}