# BookCoverMatcher branding assets

These PNGs derive from the existing GPL-3.0-or-later Android launcher vector and background (#001F3F), whose original resources are unchanged. The editable SVG preserves the path geometry; the 1024px PNG master is retained separately. No generated third-party mark or AI artwork is used.

- `bookcovermatcher-logo-120.png`: OAuth Branding upload, 120 x 120, below 1,000,000 bytes.
- `bookcovermatcher-play-icon-512.png`: 512 x 512 RGBA Play store icon; separate from launcher resources.
- `bookcovermatcher-feature-1024x500.png`: 1024 x 500 RGB feature graphic; not an app screenshot.
- `source/bookcovermatcher-logo-master.png`: 1024 x 1024 PNG master.
- `source/bookcovermatcher-logo.svg`: editable vector source derived from `app/src/main/res/drawable/ic_launcher_foreground.xml` and `values/colors.xml`.
- `source/feature-graphic.svg`: feature-graphic background/text source, composited with the logo by the export script.

See `manifest.json` for byte sizes and SHA-256 digests. Visually checked at native OAuth size and feature-graphic size. Real app screenshots remain separate release work.

Reproduce with Node and sharp: `node tools/export-branding.cjs <absolute-path-to-installed-sharp-module>` (or omit the argument when sharp is resolvable). The exporter preserves Android originals and does not edit the website. Font rasterization of the feature graphic depends on local sans-serif font resolution; retain reviewed exports and re-review regenerated text.

Project-owned artwork and export code: GPL-3.0-or-later; see root LICENSE. Console approval and deployment are not implied by these assets.
