# Next steps

Updated 2026-11-06. Two lists: what happens before the test build is handed over, and what comes after you've tried it.

## Before the test build (in progress)

1. **Restyle (done in code, unreviewed):** new dark-first look, soft rounded cards, pill buttons, quiet sentence-case labels, a light "inverse" fill for
   selected and "on" states. Applies to every screen through the design tokens. ADR 0009.
2. **Phone layout:** bottom tab bar, a device tile grid (tap a light or plug to toggle it, tap anything else to open it), device pages with large titles.
3. **Desktop layout:** the same tiles and the restyled navigation rail.
4. **Look at the real renders** from CI, fix what looks wrong, then record the new screenshot references.
5. **Build the installable test app:** Android APK (easiest to try first) and the Windows installer, from the CI run, with direct download links.

## After the test build (your side, then mine)

6. **You:** install the APK and walk through `docs/HARDWARE_TEST.md` in this order: app lock (L1), Govee (G1), LG TV (T1), Bambu (B1), then Kasa (K1),
   Tuya bulbs (U1), eufy cameras (E1, including Watch). Send me each step that failed, with what you saw. Also tell me what feels off in the new look.
7. **Me, from your results:** fix what broke (adapters first, simulator test before the fix), tune the look from your feedback.
8. **Waiting on you:** Lefant vacuum model number and its data-point list; the protocol version of your Lepro bulbs (3.3 works today).
9. **Then, in order:** Tuya protocol 3.4/3.5 if your bulbs need it; desktop camera player; Cast / Google TV; rooms and floor-plan editor for real devices;
   Windows Hello; fuzzing in CI; scenes and automations (Phase 2); merge `ci/verification-bootstrap` into `main` through a pull request.
