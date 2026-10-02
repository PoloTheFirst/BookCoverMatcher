import { targetPreview, targetThumb, targetHash, resultsEl } from './dom.js';
import { state } from './state.js';
import { levelFromDistance } from './phash.js';

export function updateScanPreview() {
  targetPreview.hidden = false;
  targetThumb.src = state.scanThumbUrl || '';
  targetHash.textContent = state.scanHash || '—';
}

export function renderResults(list) {
  if (!list || !list.length) {
    resultsEl.innerHTML = '<li class="empty">No matches to show.</li>';
    return;
  }
  resultsEl.innerHTML = '';
  list.forEach((item, i) => {
    const pct = item.percent.toFixed(1);
    const level = levelFromDistance(item.distance);
    const li = document.createElement('li');
    li.className = 'result';
    li.innerHTML =
      '<div class="rank">#' + (i + 1) + '</div>' +
      '<img class="thumb" alt="Match ' + (i + 1) + '" src="' + item.thumb + '">' +
      '<div class="meta">' +
        '<div class="cell">Cell <strong>' + item.cell + '</strong></div>' +
        '<div class="sub">Hamming ' + item.distance + '/64 · ' + item.source + '</div>' +
        '<div class="bar"><i class="' + level + '" style="width:' +
          Math.max(2, Math.min(100, item.percent)).toFixed(1) + '%"></i></div>' +
      '</div>' +
      '<div class="pct ' + level + '">' + pct + '%</div>';
    resultsEl.appendChild(li);
  });
}