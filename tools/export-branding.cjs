// SPDX-License-Identifier: GPL-3.0-or-later
// node tools/export-branding.cjs [absolute path to an installed sharp module]
const path = require('node:path');
const fs = require('node:fs/promises');
const sharp = require(process.argv[2] || 'sharp');
const root = path.resolve(__dirname, '..');
async function main() {
  const source = path.join(root, 'branding/source/bookcovermatcher-logo.svg');
  for (const [size, file] of [[1024, 'source/bookcovermatcher-logo-master.png'], [120, 'bookcovermatcher-logo-120.png'], [512, 'bookcovermatcher-play-icon-512.png']]) {
    await sharp(source, {density: 768}).resize(size,size).ensureAlpha().png().toFile(path.join(root,'branding',file));
  }
  const feature = `<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="500"><rect width="1024" height="500" fill="#001F3F"/><text x="370" y="228" fill="#FFFFFF" font-family="sans-serif" font-size="46" font-weight="bold">BookCoverMatcher</text><text x="370" y="286" fill="#22E0C8" font-family="sans-serif" font-size="24">Match covers to your collection</text></svg>`;
  await fs.writeFile(path.join(root,'branding/source/feature-graphic.svg'),feature);
  const logo=await sharp(source,{density:768}).resize(300,300).png().toBuffer();
  await sharp(Buffer.from(feature)).composite([{input:logo,left:40,top:100}]).removeAlpha().png().toFile(path.join(root,'branding/bookcovermatcher-feature-1024x500.png'));
  for (const file of ['bookcovermatcher-logo-120.png','bookcovermatcher-play-icon-512.png','bookcovermatcher-feature-1024x500.png','source/bookcovermatcher-logo-master.png']) {
    const p=path.join(root,'branding',file); const m=await sharp(p).metadata(); const s=await fs.stat(p);
    console.log(JSON.stringify({file,format:m.format,width:m.width,height:m.height,bytes:s.size}));
  }
}
main().catch(e=>{console.error(e.message);process.exitCode=1;});
