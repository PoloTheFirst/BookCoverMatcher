# BCM branding assets

BCM’s mark is an open book with a four-point glint on the right page. The full-color launcher uses Polo’s Atelier’s Sophie palette, with a cream tile and rose book in daytime. At night it uses a deep rose tile with blush and ivory pages. The Home header follows **More → Display**. On Android launchers that offer themed icons, Android supplies the colors for a separate silhouette with a transparent page crease and glint.

## Artwork and exports

Edit the path geometry and day/night fills in [the source SVG](source/bookcovermatcher-logo.svg). The SVG crops the center 72 units from Android’s 108-unit adaptive canvas. Android applies its own launcher mask. The feature graphic uses its own [editable layout](source/feature-graphic.svg).

| Asset | Purpose |
| --- | --- |
| [120 × 120 PNG](bookcovermatcher-logo-120.png) | OAuth branding artwork, under 1 MB |
| [512 × 512 RGBA PNG](bookcovermatcher-play-icon-512.png) | Google Play listing icon |
| [1024 × 1024 PNG](source/bookcovermatcher-logo-master.png) | Full-size logo |
| [1024 × 500 RGB PNG](bookcovermatcher-feature-1024x500.png) | Feature graphic |

From the repository root, use Node, Python 3 and sharp to regenerate the Android vectors, PNGs and [digest manifest](manifest.json).

```sh
node tools/export-branding.cjs
```

Pass the absolute path to an installed sharp module if it is outside the repository. The feature graphic uses local serif and sans-serif fonts. Review the text when rendering on another system.

The dev.3 vector was drawn with Codex for this project. Project-owned artwork and export code are licensed under [GPL-3.0-or-later](../LICENSE).
