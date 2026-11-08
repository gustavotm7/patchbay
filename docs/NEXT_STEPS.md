# Next steps

Updated 2026-11-06. Two lists: what happens before the test build is handed over, and what comes after you've tried it.

## Before the test build (done)

1. **Restyle (done, reviewed in CI renders):** new dark-first look, soft rounded cards, pill buttons, quiet sentence-case labels, a light "inverse" fill for
   selected and "on" states. Applies to every screen through the design tokens. ADR 0009.
2. **Phone layout:** bottom tab bar, a device tile grid (tap a light or plug to toggle it, tap anything else to open it), device pages with large titles.
3. **Desktop layout:** the same tiles and the restyled navigation rail.
4. **Look at the real renders** from CI, fix what looks wrong, then record the new screenshot references.
5. **Build the installable test app:** Android APK (easiest to try first) and the Windows installer, from the CI run, with direct download links.

## Test build (CI run on `8d7be5e`, all 7 jobs green)

Adds the ecobee over HomeKit (E2.x), thermostat and TV-volume scenes (S2.x), Android background watching (N2.x, ADR 0011, approved by the owner 2026-11-08), print-finished alerts and Windows camera video (C2.x, needs ffmpeg.exe next to Patchbay.exe), on top of everything earlier.

- Run: https://github.com/gustavotm7/patchbay/actions/runs/42432501053
- Windows portable (no install): https://github.com/gustavotm7/patchbay/actions/runs/42432501053/artifacts/13597709613
- Android: https://github.com/gustavotm7/patchbay/actions/runs/42432501053/artifacts/13597807809 (install `app-android-debug.apk` from inside)
- Windows MSI (optional): https://github.com/gustavotm7/patchbay/actions/runs/42432501053/artifacts/13598640951
- Downloads need you to be signed in to GitHub.

## After the test build (your side, then mine)

6. **You:** install the APK and walk through `docs/HARDWARE_TEST.md` in this order: app lock (L1), Govee (G1), LG TV (T1), Bambu (B1), then Kasa (K1),
   Tuya bulbs (U1), eufy cameras (E1, including Watch). Send me each step that failed, with what you saw. Also tell me what feels off in the new look.
7. **Me, from your results:** fix what broke (adapters first, simulator test before the fix), tune the look from your feedback.
8. **Waiting on you:** Lefant vacuum model number and its data-point list; the protocol version of your Lepro bulbs (3.3 works today).
9. **Then, in order:** Tuya protocol 3.4/3.5 if your bulbs need it; Google TV remote keys and ESPHome (both need their exact message formats researched from reference sources first: see the notes in `docs/HANDOFF.md`); desktop camera player; floor-plan editor for real devices;
   Windows Hello; fuzzing in CI; automations (scenes on a schedule or a trigger, Phase 2); merge `ci/verification-bootstrap` into `main` through a pull request.
