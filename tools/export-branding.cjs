// SPDX-License-Identifier: GPL-3.0-or-later
// node tools/export-branding.cjs [absolute path to an installed sharp module]
const path = require('node:path');
const fs = require('node:fs/promises');
const {createHash} = require('node:crypto');
const {execFileSync} = require('node:child_process');
const sharp = require(process.argv[2] || 'sharp');
const root = path.resolve(__dirname, '..');
const branding = path.join(root, 'branding');

async function main() {
  execFileSync(process.platform === 'win32' ? 'python' : 'python3', [path.join(__dirname, 'export-logo-vectors.py')]);
  const source = path.join(branding, 'source/bookcovermatcher-logo.svg');
  const files = [
    ['bookcovermatcher-logo-120.png', 120],
    ['bookcovermatcher-play-icon-512.png', 512],
    ['source/bookcovermatcher-logo-master.png', 1024],
  ];
  for (const [name, size] of files) {
    await sharp(source, {density: 384}).resize(size, size).ensureAlpha().png().toFile(path.join(branding, name));
  }

  const tileMask = Buffer.from('<svg width="256" height="256"><rect width="256" height="256" rx="58" fill="white"/></svg>');
  const tile = await sharp(source, {density: 384}).resize(256, 256)
    .composite([{input: tileMask, blend: 'dest-in'}]).png().toBuffer();
  const feature = 'bookcovermatcher-feature-1024x500.png';
  await sharp(path.join(branding, 'source/feature-graphic.svg'))
    .composite([{input: tile, left: 88, top: 122}]).removeAlpha().png().toFile(path.join(branding, feature));

  const manifest = [];
  for (const name of [feature, ...files.map(([name]) => name)]) {
    const bytes = await fs.readFile(path.join(branding, name));
    manifest.push({path: 'branding/' + name, bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex')});
  }
  await fs.writeFile(path.join(branding, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
}
main().catch(error => {console.error(error.message); process.exitCode = 1;});
