import { state } from './state.js';
import { toast } from './utils.js';
import { WISHLIST_STORAGE_KEY, WISHLIST_PROMPT_MS } from './config.js';
import {
  wishlistPrompt, wishlistPromptText, btnPromptRemove, btnPromptClose,
  wishlistOverlay, wishlistList, btnOpenWishlist, btnCloseWishlist,
  btnWishlist, confirmModal, confirmMessage,
  btnConfirmCancel, btnConfirmDelete,
} from './dom.js';

/* ---------------- storage ---------------- */

function loadWishlist() {
  try {
    const raw = localStorage.getItem(WISHLIST_STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch (e) {
    console.warn('[wishlist] load failed', e);
    return [];
  }
}

function saveWishlist(list) {
  try {
    localStorage.setItem(WISHLIST_STORAGE_KEY, JSON.stringify(list));
  } catch (e) {
    console.warn('[wishlist] save failed', e);
    toast('Wishlist storage is full — could not save.', 'err');
  }
}

/* ---------------- helpers ---------------- */

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function formatTimestamp(iso) {
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '—';
  const y  = d.getFullYear();
  const mo = String(d.getMonth() + 1).padStart(2, '0');
  const da = String(d.getDate()).padStart(2, '0');
  const h  = String(d.getHours()).padStart(2, '0');
  const mi = String(d.getMinutes()).padStart(2, '0');
  return `${y}-${mo}-${da} ${h}:${mi}`;
}

/* ---------------- add record ---------------- */

let promptTimer = null;
let promptRecordId = null;

function showPrompt(recordId) {
  promptRecordId = recordId;
  wishlistPromptText.textContent = 'Record added to wishlist';
  wishlistPrompt.hidden = false;
  // force layout so transition runs
  void wishlistPrompt.offsetWidth;
  wishlistPrompt.classList.add('show');

  clearTimeout(promptTimer);
  promptTimer = setTimeout(hidePrompt, WISHLIST_PROMPT_MS);
}

function hidePrompt() {
  clearTimeout(promptTimer);
  promptTimer = null;
  wishlistPrompt.classList.remove('show');
  // hide after transition
  setTimeout(() => { wishlistPrompt.hidden = true; }, 320);
  promptRecordId = null;
}

export function addCurrentToWishlist() {
  if (!state.scanHash) {
    toast('Scan a cover first.', 'err');
    return;
  }
  if (!state.lastRanked || !state.lastRanked.length) {
    toast('Run “Link & Find Top 5” before saving to wishlist.', 'err');
    return;
  }

  const list = loadWishlist();
  const record = {
    id: 'rec_' + Date.now() + '_' + Math.random().toString(36).slice(2, 7),
    name: '#' + (list.length + 1),
    createdAt: new Date().toISOString(),
    scanThumbUrl: state.scanThumbUrl || '',
    scanHash: state.scanHash,
    matches: state.lastRanked.map((m) => ({
      cell: m.cell,
      distance: m.distance,
      percent: m.percent,
      thumb: m.thumb,
      source: m.source
    }))
  };

  list.push(record);
  saveWishlist(list);
  showPrompt(record.id);

  if (!wishlistOverlay.hidden) renderWishlist();
}

/* ---------------- render wishlist ---------------- */

function renderWishlist() {
  const list = loadWishlist();
  if (!list.length) {
    wishlistList.innerHTML =
      '<div class="wishlist-empty">No saved records yet. Scan a cover and add it to your wishlist.</div>';
    return;
  }
  wishlistList.innerHTML = '';
  list.forEach((rec) => {
    const el = document.createElement('div');
    el.className = 'wishlist-record';
    el.dataset.id = rec.id;

    const matchesHtml = (rec.matches && rec.matches.length)
      ? rec.matches.map((m, i) => (
          '<div class="record-match">' +
            '<img src="' + m.thumb + '" alt="Match ' + (i + 1) + '">' +
            '<span class="rc-cell">' + escapeHtml(m.cell) + '</span>' +
            '<span class="rc-src">' + escapeHtml(m.source) + '</span>' +
            '<span class="rc-pct">' + (m.percent != null ? m.percent.toFixed(1) : '0.0') + '%</span>' +
          '</div>'
        )).join('')
      : '<div class="record-match"><span class="rc-src">No matches saved.</span></div>';

    el.innerHTML =
      '<div class="record-main">' +
        '<img class="record-thumb" src="' + (rec.scanThumbUrl || '') + '" alt="Cover">' +
        '<div class="record-info">' +
          '<div class="record-name">' + escapeHtml(rec.name) + '</div>' +
          '<div class="record-time">' + formatTimestamp(rec.createdAt) + '</div>' +
        '</div>' +
        '<div class="record-actions">' +
          '<button class="record-icon-btn" data-act="rename" title="Rename">✏️</button>' +
          '<button class="record-icon-btn" data-act="toggle" title="Expand">⌄</button>' +
        '</div>' +
      '</div>' +
      '<div class="record-expanded" hidden>' + matchesHtml + '</div>' +
      '<button class="record-delete" data-act="delete" title="Delete record">✕</button>';

    wishlistList.appendChild(el);
  });
}

/* ---------------- rename / delete ---------------- */

function renameRecord(id, name) {
  const list = loadWishlist();
  const rec = list.find((r) => r.id === id);
  if (!rec) return;
  rec.name = name;
  saveWishlist(list);
}

function deleteRecord(id) {
  const list = loadWishlist().filter((r) => r.id !== id);
  saveWishlist(list);
}

/* ---------------- confirmation modal ---------------- */

let pendingDeleteId = null;

function openConfirm(id) {
  pendingDeleteId = id;
  confirmModal.hidden = false;
}

function closeConfirm() {
  pendingDeleteId = null;
  confirmModal.hidden = true;
}

/* ---------------- events ---------------- */

wishlistList.addEventListener('click', (e) => {
  const btn = e.target.closest('[data-act]');
  if (!btn) return;
  const record = btn.closest('.wishlist-record');
  if (!record) return;
  const id  = record.dataset.id;
  const act = btn.dataset.act;

  if (act === 'rename') {
    const list = loadWishlist();
    const rec = list.find((r) => r.id === id);
    if (!rec) return;
    const next = window.prompt('Rename record', rec.name);
    if (next != null) {
      const trimmed = next.trim() || rec.name;
      renameRecord(id, trimmed);
      renderWishlist();
    }
  } else if (act === 'toggle') {
    const exp = record.querySelector('.record-expanded');
    if (exp) {
      exp.hidden = !exp.hidden;
      btn.classList.toggle('active', !exp.hidden);
    }
  } else if (act === 'delete') {
    openConfirm(id);
  }
});

btnConfirmCancel.addEventListener('click', closeConfirm);

btnConfirmDelete.addEventListener('click', () => {
  if (pendingDeleteId) deleteRecord(pendingDeleteId);
  closeConfirm();
  renderWishlist();
});

btnPromptRemove.addEventListener('click', () => {
  if (promptRecordId) {
    deleteRecord(promptRecordId);
    if (!wishlistOverlay.hidden) renderWishlist();
  }
  wishlistPromptText.textContent = 'Removed from wishlist';
  hidePrompt();
});

btnPromptClose.addEventListener('click', hidePrompt);

btnOpenWishlist.addEventListener('click', () => {
  renderWishlist();
  wishlistOverlay.hidden = false;
});

btnCloseWishlist.addEventListener('click', () => {
  wishlistOverlay.hidden = true;
});

/* Escape to close overlay (not modal — modal must be answered) */
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && !wishlistOverlay.hidden && confirmModal.hidden) {
    wishlistOverlay.hidden = true;
  }
});

/* ---------------- init ---------------- */

export function initWishlist() {
  wishlistPrompt.hidden = true;
  wishlistOverlay.hidden = true;
  confirmModal.hidden = true;
  btnWishlist.addEventListener('click', addCurrentToWishlist);
}