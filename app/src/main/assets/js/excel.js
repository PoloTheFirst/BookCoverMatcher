import { MAX_EXTRACTED_IMAGES } from './config.js';
import { bufferToCanvas, makeThumb } from './image.js';
import { computePHashFromCanvas } from './phash.js';
import { THUMB_MATCH_W, THUMB_MATCH_H } from './config.js';

/* ---------- binary / mime helpers ---------- */

function toUint8Array(buf) {
  if (!buf) return null;
  if (buf instanceof Uint8Array) return buf;
  if (buf instanceof ArrayBuffer) return new Uint8Array(buf);
  if (Array.isArray(buf)) return new Uint8Array(buf);
  if (buf.buffer instanceof ArrayBuffer) {
    return new Uint8Array(buf.buffer, buf.byteOffset || 0, buf.byteLength);
  }
  if (buf.type === 'Buffer' && Array.isArray(buf.data)) return new Uint8Array(buf.data);
  if (Array.isArray(buf.data)) return new Uint8Array(buf.data);
  return null;
}

function sniffMime(bytes) {
  if (!bytes || bytes.length < 4) return null;
  const b = bytes;
  if (b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4E && b[3] === 0x47) return 'image/png';
  if (b[0] === 0xFF && b[1] === 0xD8) return 'image/jpeg';
  if (b[0] === 0x47 && b[1] === 0x49 && b[2] === 0x46) return 'image/gif';
  if (b[0] === 0x42 && b[1] === 0x4D) return 'image/bmp';
  if (b[0] === 0x52 && b[1] === 0x49 && b[2] === 0x46 && b[3] === 0x46) return 'image/webp';
  return null;
}

function mimeFromExtension(ext) {
  switch (String(ext || '').toLowerCase()) {
    case 'png':  return 'image/png';
    case 'jpg': case 'jpeg': case 'jfif': return 'image/jpeg';
    case 'gif':  return 'image/gif';
    case 'bmp':  return 'image/bmp';
    case 'webp': return 'image/webp';
    default:     return 'image/png';
  }
}

function resolvePath(baseDir, target) {
  if (!target) return '';
  if (target.charAt(0) === '/') return target.slice(1);
  const parts = (baseDir + target).split('/');
  const stack = [];
  for (const p of parts) {
    if (p === '' || p === '.') continue;
    if (p === '..') stack.pop();
    else stack.push(p);
  }
  return stack.join('/');
}

/* ---------- range parsing ---------- */

function columnLettersToIndex(letters) {
  let n = 0;
  for (let i = 0; i < letters.length; i++)
    n = n * 26 + (letters.charCodeAt(i) - 64);
  return n - 1;
}

function indexToColumnLetters(index) {
  let s = '', n = index + 1;
  while (n > 0) {
    const r = (n - 1) % 26;
    s = String.fromCharCode(65 + r) + s;
    n = Math.floor((n - 1) / 26);
  }
  return s;
}

export function parseRange(str) {
  const cleaned = String(str || '').trim().toUpperCase().replace(/\$/g, '');
  const m = cleaned.match(/^([A-Z]+)(\d+)\s*:\s*([A-Z]+)(\d+)$/);
  if (!m) throw new Error('Invalid range — use a format like A1:B20.');
  const cA = columnLettersToIndex(m[1]);
  const rA = parseInt(m[2], 10) - 1;
  const cB = columnLettersToIndex(m[3]);
  const rB = parseInt(m[4], 10) - 1;
  return {
    r1: Math.min(rA, rB), r2: Math.max(rA, rB),
    c1: Math.min(cA, cB), c2: Math.max(cA, cB)
  };
}

/* ---------- floating / anchored images ---------- */

function findMediaEntry(workbook, imageId) {
  const idNum = Number(imageId);
  const pools = [];
  if (workbook && workbook.model && Array.isArray(workbook.model.media)) pools.push(workbook.model.media);
  if (workbook && Array.isArray(workbook.media)) pools.push(workbook.media);
  for (const pool of pools) {
    for (const m of pool) {
      if (!m) continue;
      if (Number(m.index) === idNum || String(m.index) === String(imageId)) return m;
    }
    if (Number.isInteger(idNum) && pool[idNum]) return pool[idNum];
  }
  return null;
}

async function extractFloatingImages(workbook, sheet, range) {
  const out = [];
  let images = [];
  try { images = sheet.getImages() || []; } catch (e) { images = []; }

  for (const img of images) {
    if (!img || !img.range) continue;
    const tl = img.range.tl;
    if (!tl) continue;
    const rawRow = (tl.row !== undefined && tl.row !== null) ? tl.row : tl.nativeRow;
    const rawCol = (tl.col !== undefined && tl.col !== null) ? tl.col : tl.nativeCol;
    if (rawRow === undefined || rawCol === undefined) continue;

    const row = Math.floor(Number(rawRow));
    const col = Math.floor(Number(rawCol));
    if (row < range.r1 || row > range.r2 || col < range.c1 || col > range.c2) continue;

    const media = findMediaEntry(workbook, img.imageId);
    if (!media || !media.buffer) continue;
    const bytes = toUint8Array(media.buffer);
    if (!bytes || !bytes.length) continue;

    const mime = sniffMime(bytes) || mimeFromExtension(media.extension);
    let canvas;
    try { canvas = await bufferToCanvas(bytes, mime); }
    catch (e) { console.warn('[float] Skipping undecodable image', e); continue; }

    out.push({
      imageId: 'img#' + img.imageId,
      cell: indexToColumnLetters(col) + (row + 1),
      row: row, col: col,
      hash: computePHashFromCanvas(canvas),
      thumb: makeThumb(canvas, THUMB_MATCH_W, THUMB_MATCH_H),
      source: 'floating'
    });

    if (out.length >= MAX_EXTRACTED_IMAGES) break;
  }
  return out;
}

/* ---------- comment-fill images (VML drawing part) ---------- */

async function extractCommentImages(arrayBuffer, range) {
  const JSZip = window.JSZip;
  if (!JSZip) return [];

  const zip = await JSZip.loadAsync(arrayBuffer);
  const out = [];
  const seenCells = new Set();

  const vmlPaths = Object.keys(zip.files).filter((n) =>
    /^xl\/drawings\/vmlDrawing\d+\.vml$/i.test(n)
  );

  for (const vmlPath of vmlPaths) {
    const vmlFile = zip.file(vmlPath);
    if (!vmlFile) continue;
    const vmlText = await vmlFile.async('string');

    const vmlName = vmlPath.split('/').pop();
    const relsPath = 'xl/drawings/_rels/' + vmlName + '.rels';
    const relsFile = zip.file(relsPath);
    if (!relsFile) continue;
    const relsText = await relsFile.async('string');

    const relMap = {};
    const relElems = relsText.match(/<Relationship\b[^>]*\/?>/g) || [];
    for (const el of relElems) {
      const idM = el.match(/\bId="([^"]+)"/);
      const tM  = el.match(/\bTarget="([^"]+)"/);
      if (idM && tM) relMap[idM[1]] = tM[1];
    }

    const shapeRe = /<v:shape\s[^>]*>([\s\S]*?)<\/v:shape>/gi;
    let sm;
    while ((sm = shapeRe.exec(vmlText)) !== null) {
      const full  = sm[0];
      const inner = sm[1];

      const rowM = inner.match(/<x:Row>\s*(\d+)\s*<\/x:Row>/i);
      const colM = inner.match(/<x:Column>\s*(\d+)\s*<\/x:Column>/i);
      if (!rowM || !colM) continue;
      const row = parseInt(rowM[1], 10);
      const col = parseInt(colM[1], 10);

      if (row < range.r1 || row > range.r2 || col < range.c1 || col > range.c2) continue;

      let relId = null, mref;
      mref = full.match(/<v:fill\b[^>]*\bo:relid="([^"]+)"/i);
      if (mref) relId = mref[1];
      if (!relId) {
        mref = full.match(/<v:imagedata\b[^>]*\br:id="([^"]+)"/i);
        if (mref) relId = mref[1];
      }
      if (!relId) {
        mref = full.match(/<v:fill\b[^>]*\br:id="([^"]+)"/i);
        if (mref) relId = mref[1];
      }
      if (!relId) {
        mref = full.match(/<v:imagedata\b[^>]*\bo:relid="([^"]+)"/i);
        if (mref) relId = mref[1];
      }
      if (!relId) continue;

      const target = relMap[relId];
      if (!target) continue;

      const mediaPath = resolvePath('xl/drawings/', target);
      const mediaFile = zip.file(mediaPath);
      if (!mediaFile) continue;

      const bytes = await mediaFile.async('uint8array');
      if (!bytes || !bytes.length) continue;

      const mime = sniffMime(bytes) || mimeFromExtension(mediaPath.split('.').pop());

      let canvas;
      try { canvas = await bufferToCanvas(bytes, mime); }
      catch (e) { console.warn('[comment] Undecodable image:', mediaPath, e); continue; }

      const cell = indexToColumnLetters(col) + (row + 1);
      if (seenCells.has(cell)) continue;
      seenCells.add(cell);

      out.push({
        imageId: mediaPath,
        cell: cell,
        row: row, col: col,
        hash: computePHashFromCanvas(canvas),
        thumb: makeThumb(canvas, THUMB_MATCH_W, THUMB_MATCH_H),
        source: 'comment-fill'
      });
    }
  }

  return out;
}

/* ---------- combined extraction ---------- */

export async function extractAllImages(arrayBuffer, workbook, sheet, range) {
  const combined = [];

  try {
    const floating = await extractFloatingImages(workbook, sheet, range);
    combined.push(...floating);
    console.log('[extract] floating images:', floating.length);
  } catch (e) {
    console.warn('[extract] Floating extraction failed:', e);
  }

  try {
    const comments = await extractCommentImages(arrayBuffer, range);
    combined.push(...comments);
    console.log('[extract] comment-fill images:', comments.length);
  } catch (e) {
    console.warn('[extract] Comment extraction failed:', e);
  }

  const seen = new Set();
  return combined.filter((r) => {
    if (seen.has(r.cell)) return false;
    seen.add(r.cell);
    return true;
  });
}