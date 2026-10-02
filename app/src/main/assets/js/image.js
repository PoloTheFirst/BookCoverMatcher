import { MAX_IMAGE_DECODE, MAX_BUFFER_DECODE } from './config.js';

export function makeThumb(source, w, h) {
  const c = document.createElement('canvas');
  c.width = w; c.height = h;
  const ctx = c.getContext('2d');
  ctx.fillStyle = '#001428';
  ctx.fillRect(0, 0, w, h);
  const sw = source.width || 1, sh = source.height || 1;
  const scale = Math.min(w / sw, h / sh);
  const dw = sw * scale, dh = sh * scale;
  ctx.drawImage(source, (w - dw) / 2, (h - dh) / 2, dw, dh);
  return c.toDataURL('image/jpeg', 0.72);
}

export function fileToCanvas(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      try {
        const iw = img.naturalWidth || img.width;
        const ih = img.naturalHeight || img.height;
        const scale = Math.min(1, MAX_IMAGE_DECODE / Math.max(iw, ih));
        const c = document.createElement('canvas');
        c.width  = Math.max(1, Math.round(iw * scale));
        c.height = Math.max(1, Math.round(ih * scale));
        const ctx = c.getContext('2d');
        ctx.imageSmoothingEnabled = true;
        ctx.imageSmoothingQuality = 'high';
        ctx.drawImage(img, 0, 0, c.width, c.height);
        resolve(c);
      } catch (e) { reject(e); }
      finally { URL.revokeObjectURL(url); }
    };
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('Unsupported or corrupt image file.')); };
    img.src = url;
  });
}

export function bufferToCanvas(bytes, mime) {
  return new Promise((resolve, reject) => {
    const blob = new Blob([bytes], { type: mime });
    const url  = URL.createObjectURL(blob);
    const img  = new Image();
    img.onload = () => {
      try {
        const iw = img.naturalWidth || img.width;
        const ih = img.naturalHeight || img.height;
        const scale = Math.min(1, MAX_BUFFER_DECODE / Math.max(iw, ih));
        const c = document.createElement('canvas');
        c.width  = Math.max(1, Math.round(iw * scale));
        c.height = Math.max(1, Math.round(ih * scale));
        const ctx = c.getContext('2d');
        ctx.imageSmoothingEnabled = true;
        ctx.imageSmoothingQuality = 'high';
        ctx.drawImage(img, 0, 0, c.width, c.height);
        resolve(c);
      } catch (e) { reject(e); }
      finally { URL.revokeObjectURL(url); }
    };
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('Could not decode embedded image.')); };
    img.src = url;
  });
}