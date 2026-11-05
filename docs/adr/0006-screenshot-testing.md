# 0006 — Screenshot tests with `ImageComposeScene` instead of Roborazzi

Status: Proposed · Date: 2026-11-05

## Context

The brief asks for JVM screenshot tests ("Roborazzi or equivalent") that render key screens in
light/dark/night at phone and desktop sizes, committed as PNGs for review.

## Decision

Use Compose Desktop's own `ImageComposeScene` (part of `compose.ui`, already a dependency) to render to a
Skia image and encode PNG. About 60 lines of test code in `ui/src/desktopTest/.../ScreenshotTest.kt` does
the work:

- `./gradlew :ui:desktopTest` renders to `ui/build/screenshots-actual/`. If goldens exist in
  `docs/design/screens/`, it compares and fails when more than 0.2 % of pixels differ by more than 24/255
  in any channel. That tolerance absorbs antialiasing noise across Skia versions.
- `./gradlew :ui:recordScreens` rewrites the goldens.
- Goldens are recorded on `ubuntu-latest` (the CI `screens` job), so comparisons always run on the same
  platform and font rasteriser.

## Alternatives considered

- **Roborazzi (compose-desktop):** nicer diff images and reports. It's another dependency tree (and
  Roborazzi's Android path pulls Robolectric). Revisit if we need diff visualisation.
- **Paparazzi:** Android-only, layoutlib-based. It doesn't cover desktop layouts.

## Consequences

- Phone screens are rendered with the desktop Skia backend at density 2. Text shaping is the same Skia
  engine Android uses, but these aren't pixel-identical to a device. They're design review renders, not
  device QA.
