# Next steps

Updated 2026-11-06. Two lists: what happens before the test build is handed over, and what comes after you've tried it.

## Before the test build (done)

1. **Restyle (done, reviewed in CI renders):** new dark-first look, soft rounded cards, pill buttons, quiet sentence-case labels, a light "inverse" fill for
   selected and "on" states. Applies to every screen through the design tokens. ADR 0009.
2. **Phone layout:** bottom tab bar, a device tile grid (tap a light or plug to toggle it, tap anything else to open it), device pages with large titles.
3. **Desktop layout:** the same tiles and the restyled navigation rail.
4. **Look at the real renders** from CI, fix what looks wrong, then record the new screenshot references.
5. **Build the installable test app:** Android APK (easiest to try first) and the Windows installer, from the CI run, with direct download links.

## Test build (CI run on `3750824`, all 7 jobs green)

Adds the Google TV remote (A1.x in `docs/HARDWARE_TEST.md`), on top of names, favourites and sun automations (F1.x), adaptive lighting and away mode (D1.x), tray and automations (R1.x), attention/removal/activity (N1.x), Cast (C1.x), rooms and scenes (S1.x), Settings and the portable build.

- Run: https://github.com/gustavotm7/patchbay/actions/runs/42426286463
- Artifacts: open the run above and download `patchbay-windows-portable` (no install, recommended), `patchbay-android` (install `app-android-debug.apk` from inside; ignore the unsigned `release` APK) or `patchbay-windows-msi` (optional installer). Direct artifact links are in the chat message that accompanied this build.
- Downloads need you to be signed in to GitHub. Artifacts are kept until 2026-12-08.

## After the test build (your side, then mine)

6. **You:** install the APK and walk through `docs/HARDWARE_TEST.md` in this order: app lock (L1), Govee (G1), LG TV (T1), Bambu (B1), then Kasa (K1),
   Tuya bulbs (U1), eufy cameras (E1, including Watch). Send me each step that failed, with what you saw. Also tell me what feels off in the new look.
7. **Me, from your results:** fix what broke (adapters first, simulator test before the fix), tune the look from your feedback.
8. **Waiting on you:** Lefant vacuum model number and its data-point list; the protocol version of your Lepro bulbs (3.3 works today).
9. **Then, in order:** Tuya protocol 3.4/3.5 if your bulbs need it; Google TV remote keys and ESPHome (both need their exact message formats researched from reference sources first: see the notes in `docs/HANDOFF.md`); desktop camera player; floor-plan editor for real devices;
   Windows Hello; fuzzing in CI; automations (scenes on a schedule or a trigger, Phase 2); merge `ci/verification-bootstrap` into `main` through a pull request.
