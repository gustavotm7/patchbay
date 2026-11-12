# Design screenshots (goldens)

These PNGs are rendered by `ui/src/desktopTest/.../ScreenshotTest.kt` from the deterministic demo home.

**Recorded 2026-11-12** from the CI render of commit `e7c1fde` (34 PNGs: light, dark and night, phone and desktop, including the live tile screens the Network screen and Settings), after the devices-first layout, the Automations screen and the motion pass (ADR 0009). The
`:ui:desktopTest` run in CI compares every render with these and fails on drift (ADR 0006).

When you change the UI on purpose, re-record and review the images before committing them. In CI, run the
`verification-bootstrap` workflow and decode the PNGs from its log (see `docs/HANDOFF.md`), or locally:

```bash
./gradlew :ui:recordScreens
```


