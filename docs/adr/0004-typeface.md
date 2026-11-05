# 0004 — Typeface: IBM Plex Sans + IBM Plex Mono

Status: Proposed · Date: 2026-11-05

## Context

The brief (§6) asks for one bundled OFL family with **true tabular figures** and a matching mono for
codes/IDs. It rules out Inter and platform defaults, and asks for two or three candidates with rationale.
Numbers are first-class in the "Control Room" design, so figure behaviour matters more than personality.

I inspected the actual font files (fontTools, 2026-11-05):

| Candidate | Licence | Digits | Weights | Mono companion | Notes |
|---|---|---|---|---|---|
| **IBM Plex Sans + Plex Mono** | OFL-1.1 (RFN "Plex") | **Tabular by default** (all 10 digit advances equal); `onum`, `lnum`, `zero` (slashed zero) features | 7 + italics; Condensed sibling | Yes, designed together | ~1 000 glyphs in Sans (Latin Extended, Greek, Cyrillic). Engineering heritage; neutral without being generic. |
| Atkinson Hyperlegible Next + Mono | OFL-1.1 | Proportional by default; `tnum` available | 7 + italics | Yes (2025) | Designed for low vision (Braille Institute). Excellent legibility, but distinctive letterforms read "accessibility product". We must remember `tnum` on every value style. |
| B612 + B612 Mono | OFL-1.1 | Tabular by default | **2** (Regular, Bold) + italics | Yes | Designed for Airbus cockpit displays, literal HMI heritage. Two weights limit hierarchy; small glyph set. |

## Decision

**IBM Plex Sans** (Regular 400, Medium 500, SemiBold 600) and **IBM Plex Mono** (Regular, Medium).

- Default tabular digits mean a readout can't jitter even if a style forgets the feature flag. We still
  set `tnum, lnum` on all value styles.
- The slashed zero (`zero`) in Mono disambiguates access codes, serials and HMS codes (`O` vs `0`).
- Three weights give enough hierarchy without relying on colour.
- Plex Sans Condensed is available if dense desktop tables need it later (not bundled now).

**Source and integrity:** TTFs copied unmodified from the upstream `IBM/plex` repository at commit
`48e8676114c04f29033b4b53da4ec579f8846297`, paths `packages/plex-{sans,mono}/fonts/complete/ttf/`.
We didn't take them from npm: IBM's npm packages ship a `telemetry.yml` for IBM's npm-install telemetry.
We never run it, but we prefer the git source. SHA-256:

```
975dcda37d80f038dcd143c22e33ca2d97a0cc5a929aace1c749153b0fe1afa5  plex_sans_regular.ttf
331c8639d7598b2cde62a911a71db195e30cb655cd6bdf2e324a7e984955f907  plex_sans_medium.ttf
a20caf8286023a6a7a85e40b1d2a4ae9fc3e3b1f9eda8f4c542dd4986af67bb1  plex_sans_semibold.ttf
7c6fbddca4b700be918f5f6183d9bd4464fa427fe435f0b480d77fe2bb8c5a43  plex_mono_regular.ttf
98fbd727aae340b236955879dabed4d991aac9e8e90b3b2a67ce4a59221cc97c  plex_mono_medium.ttf
```

Licence text: `third_party/fonts/IBM-Plex-OFL-1.1.txt`. Only the files were renamed, because Android
resource names must be lowercase. Font data and internal names are untouched.

## Consequences

- **OFL Reserved Font Name:** if we ever **subset or otherwise modify** the fonts (for example to save APK
  size), the modified font must not be called "Plex". We'd have to rename the family internally.
  Until then, ship the unmodified files.
- About 940 KB of fonts in each app. Acceptable.
- One source directory (`ui/src/fonts/font/`) feeds both Android resources and desktop classpath
  resources.

## Verification

- [x] Feature/advance inspection above (fontTools).
- [ ] Screenshot review by the owner (`docs/design/screens/`).
