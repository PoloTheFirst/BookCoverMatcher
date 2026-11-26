# BCM branding assets

BCM’s mark is an open book with a four-point glint. The default Sophie icon has a cream tile by day and a deep rose tile at night. Selecting **More → Display → Custom palette** gives the full-color launcher icon the saved accent hue. Android's day/night setting determines the tile brightness. Launchers with themed icons enabled apply their own colors to the monochrome silhouette.

## Artwork and exports

Edit the path geometry, day/night fills and light-mode page outlines in [the source SVG](source/bookcovermatcher-logo.svg). The SVG crops the center 72 units from Android’s 108-unit adaptive canvas. Android applies its own launcher mask. The feature graphic uses its own [editable layout](source/feature-graphic.svg).

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

Gradle generates the custom adaptive icons and launcher aliases from the same SVG. Each integer hue uses the app’s AndroidX HCT color calculation. The palette source contains the exact RGB values for chroma 40 at five tones. To regenerate those rows with the AndroidX version used by the app, run the following Kotlin in an Android instrumentation session and save the resulting text as UTF-8. [Launcher HCT swatches](source/launcher-hct.csv)

```kotlin
val csv = buildString {
    appendLine("hue,tone20,tone30,tone40,tone80,tone90")
    for (hue in 0 until 360) {
        append(hue)
        for (tone in listOf(20, 30, 40, 80, 90)) {
            val rgb = androidx.core.graphics.ColorUtils.M3HCTToColor(hue.toFloat(), 40f, tone.toFloat())
            append(",#%06X".format(java.util.Locale.ROOT, rgb and 0xFFFFFF))
        }
        appendLine()
    }
}
```

The dev.3 vector was drawn with Codex for this project. Project-owned artwork and export code are licensed under [GPL-3.0-or-later](../LICENSE).
