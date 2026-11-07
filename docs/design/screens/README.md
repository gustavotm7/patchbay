# Design screenshots (goldens)

These PNGs are rendered by `ui/src/desktopTest/.../ScreenshotTest.kt` from the deterministic demo home.

**Recorded 2026-11-06** from the CI render of commit `9ccd9a6` (32 PNGs: light, dark and night, phone and desktop, including the live tile screens the Network screen and Settings), after the visual redesign (ADR 0009). The
`:ui:desktopTest` run in CI compares every render with these and fails on drift (ADR 0006).

When you change the UI on purpose, re-record and review the images before committing them. In CI, run the
`verification-bootstrap` workflow and decode the PNGs from its log (see `docs/HANDOFF.md`), or locally:

```bash
./gradlew :ui:recordScreens
```


