## v3.0.0-dev.9 (2026-11-26)

# Match the book icon to your accent

Choose **More → Display → Custom palette** to give the open-book icon your accent color. Both BCM and BCM Plus use the hue you save with the slider or the hex color field.

### Added

- Each selectable hue has light and dark launcher artwork. The phone’s light or dark setting selects the launcher artwork, and BCM’s Appearance setting selects the artwork on Home.

### Changed

- Switching back to **Sophie palette** restores the original cream and rose icon. On Android 12 and newer, **Use phone colors** also restores the Sophie full-color artwork and remembers the last manual hue.
- The bundled Privacy Policy and Terms and Conditions cover both dev.9 editions at revision `2026-11-26.3`. The icon reads the accent already saved on the device.

### Fixed

- The launcher and Home previously kept the Sophie colors when a custom accent was active. An existing custom accent now takes effect on the first launch after updating.

### Removed

- None.

### Tests

- BCM passed 1,271 JVM tests with 12 skipped. BCM Plus passed 1,444 with 16 skipped. Android lint completed with zero errors for both editions.
- Five instrumented launcher and Display tests passed per edition. They covered slider and hex input, palette switching, hue wraparound, light and dark resources, and a single active launcher entry.

### Verified

- Pixel Launcher showed the selected accent in light and dark mode on Android 16 (BCM, 181°) and Android 8 (BCM Plus, 52°). The book on Home matched the dark accent in both editions.
- Both signed APKs passed signature and ZIP alignment checks. In-place updates from dev.8 codes `234` and `2999908` retained the first-install timestamp.

### Known limitations

- A launcher with themed icons enabled applies its own colors to the book silhouette. Home-screen shortcut placement and refresh timing depend on the launcher. Physical-device appearance, including on the Vivo X100 Pro, was outside this emulator check.

**Version code/name: 2999909 / 3.0.0-dev.9.**
