# Design screenshots (goldens)

These PNGs are rendered by `ui/src/desktopTest/.../ScreenshotTest.kt` from the deterministic demo home.

**Phase 0:** none are committed yet. The sandbox that wrote Phase 0 couldn't reach Google Maven, which Compose
needs, so it couldn't render them. The CI `screens` job renders all 22 on every run and uploads them as the
**`design-screens`** artifact. Once you've reviewed them, record them as goldens:

```bash
./gradlew :ui:recordScreens
```

```bash
git add docs/design/screens/*.png && git commit -m "docs(design): record screenshot goldens"
```

After that, `:ui:desktopTest` fails if a render drifts from its golden (ADR 0006).
