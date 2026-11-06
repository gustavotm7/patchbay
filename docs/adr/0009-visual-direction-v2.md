# 0009 — Visual direction v2: modern, dark-first, rounded

Status: Accepted (owner direction 2026-11-06) · Date: 2026-11-06

## Context

The original brief asked for an ISA-101-style "Control Room" look: squared panels, hairline borders, uppercase legends, and it banned soft rounded
cards, a tile grid and Inter. After the first builds the owner asked for a modern, clean, minimalist dark look in the spirit of the Tesla app and
Apple's design language, with the best possible UX.

## Decision

- Adopt the visual direction in `docs/design/DESIGN_SPEC.md` (v2 note): tonal layers instead of borders, large radii, pill controls, an inverse pair for
  emphasis, sentence-case labels, large titles, a tile-based home, a bottom tab bar on phone.
- The owner's direction overrides the brief's bans on rounded cards and a tile grid. The tiles are not identical: each shows what that device is
  doing (brightness, print progress, temperature, stream state), and only lights and plugs toggle on a tap.
- Keep Plex Sans and Plex Mono (ADR 0004). Inter stays unused; the look does not depend on it.
- Keep everything about meaning and access: colour only for meaning, shape markers alongside colour, WCAG 2.2 AA contrast (enforced by `ContrastTest`
  on every palette, including the new inverse and on-signal pairs), 48 dp touch targets, screen-reader names with state, reduced motion.
- Keep three palettes: dark (primary), light (designed, not inverted), night (warm and very dim).

## Consequences

- Every screen picked up the new look through the design tokens; screenshot references were re-recorded.
- `Shapes`, `Legend`, `Panel`, `PButton`, `Segmented`, `PToggle` and `StatusTag` changed look; callers didn't change.
- Edge-to-edge insets are now handled in the Android shell (they were missing).
- If the look needs adjusting after real-device use, change tokens first, screens second.
