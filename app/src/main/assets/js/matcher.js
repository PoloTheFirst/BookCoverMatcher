import { TOP_MATCHES } from './config.js';
import { hammingDistance, similarityFromDistance } from './phash.js';

export function rankMatches(scanHash, excelImages, limit = TOP_MATCHES) {
  return excelImages
    .map((item) => {
      const dist = hammingDistance(scanHash, item.hash);
      return Object.assign({}, item, {
        distance: dist,
        percent: similarityFromDistance(dist)
      });
    })
    .sort((a, b) => (a.distance - b.distance) || a.cell.localeCompare(b.cell))
    .slice(0, limit);
}