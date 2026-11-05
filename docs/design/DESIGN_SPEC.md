# Design spec — "Control Room"

Status: Phase 0 · 2026-11-05 · Code: `ui/src/commonMain/kotlin/dev/patchbay/ui/designsystem/`
Renders: `docs/design/screens/` (produced by the `screens` CI job; see §11).

## 1. Principles (ISA-101, adapted for a home)

1. **Neutral baseline.** Normal is calm grey. If nothing needs you, the screen is almost monochrome.
2. **Colour means something.** There are only seven meanings: *active output*, *heat*, *cool*,
   *attention*, *alarm*, *selection/focus*, and *a device's real emitted colour* (a Govee light's output,
   a filament's colour). No decorative colour, gradients, glows or tinted cards.
3. **Never colour alone.** Every signal pairs colour with a **shape** (■ active, ▲ attention, ◆ alarm,
   ○ neutral; filled glyph = on, outline = off, dashed = unmanaged), with **position**, and with **text**
   ("HEATING", "CLOUD-ONLY").
4. **Values are first-class.** Tabular figures, units always shown, and an inline trend where history
   helps. A number is never shown without its unit.
5. **Controls mirror physics.** Each control behaves like the physical quantity it changes (§5).
6. **Space is navigation.** The floor plan is home base. You go to a room, then a device.

## 2. Colour roles

All text roles meet ≥ 4.5:1 and `outline` meets ≥ 3:1 against `background`, `surface` and `raised` in every
theme (enforced by `ContrastTest`).

| Role | Dark | Light | Night | Use |
|---|---|---|---|---|
| background | `#111315` | `#E3E5E8` | `#0A0806` | page |
| surface | `#181B1E` | `#F2F3F5` | `#100D0A` | panels |
| raised | `#202428` | `#FFFFFF` | `#17130F` | controls, selected room |
| outline | `#6E757D` | `#7A8088` | `#725F4C` | meaningful boundaries (rooms, controls) |
| divider | `#2A2F34` | `#D0D3D7` | `#1F1A15` | decorative hairlines only |
| text | `#E7E9EB` | `#16191C` | `#A88F76` | primary |
| textSecondary | `#A9AFB6` | `#454B53` | `#977F68` | labels |
| textTertiary | `#8A9199` | `#585E66` | `#937C66` | units, captions |
| active | `#4FC3D9` | `#00697C` | `#9A9A6A` | output energised, print progress |
| heat | `#F08A4B` | `#A8481A` | `#C06A44` | heating demand, heat setpoint |
| cool | `#5AA8F0` | `#1C5DA8` | `#7D9297` | cooling demand, cool setpoint |
| attention | `#F2B13C` | `#7F5300` | `#B8913F` | unknown device, cloud-only, low filament |
| alarm | `#FF6B66` | `#B71C1C` | `#D0574A` | fault, HMS fault, trust changed |

- **Light is designed, not inverted.** It's a mid-light ISA-style grey with darker, more saturated signals
  so they still hit AA on white.
- **Night** is warm-shifted, with no blue-dominant colours, and very low luminance for a dark bedroom.
  Text levels collapse to almost one, so hierarchy comes from size and weight. Real light colours are
  dimmed (×0.55/0.5/0.4 per channel) so a bright RGB strip doesn't flood the room.

## 3. Typography — IBM Plex Sans / Plex Mono (ADR 0004)

| Style | Size/line | Weight | Use |
|---|---|---|---|
| valueXL | 44/48 | Medium, `tnum lnum` | hero readout (indoor temp, print %) |
| valueL | 28/32 | Medium, `tnum` | setpoints |
| valueM | 17/22 | Medium, `tnum` | list values |
| valueS | 13/16 | Medium, `tnum` | plan annotations, chips |
| unit | 13/16 | Regular | "°C", "%" — always tertiary, baseline-aligned |
| title | 20/24 | SemiBold | panel titles |
| legend | 11/14, +0.09 em, UPPERCASE | Medium | instrument legends, section headings (semantic heading) |
| body / bodyStrong | 14/20 | Regular / Medium | text |
| caption | 12/16 | Regular | metadata |
| mono | 12/16, `zero` | Plex Mono | codes, IDs, filenames, fingerprints |

Units are SI throughout: °C, %, K, mm, m, m². Negative numbers use a true minus (U+2212).

## 4. Layout, spacing, shape

- 4 dp grid (`Space.xs` = 4 … `Space.xxxl` = 48).
- Touch target ≥ 48 dp on phone; pointer target ≥ 32 dp on desktop.
- Radii are small: panels 4 dp, controls 3 dp, chips 2 dp. No pills, no soft cards, no shadows, no blur.
- Panels are flat `surface` with a `divider` hairline. Density comes from rows of `legend + value`, not
  from tiles.

## 5. Controls that mirror the physics

| Device | Control | Phase |
|---|---|---|
| Thermostat | **Vertical setpoint scale.** Hotter is up. Ticks every 0.5 °C, labels every 2 °C. Heat and cool handles are coloured bars with grips, dragged directly. The deadband between them is shaded. Current temperature is a solid marker on the left, with its value. −/+0.5 buttons give keyboard and screen-reader users an alternative. In AUTO, a drag can't violate the minimum deadband. | Phase 0 (rendered) |
| Light | 2D pad: brightness (y) × colour temperature (x, warm→cool, K labels). Colour wheel only with `RgbColour`. The glyph shows the real output colour. | Phase 1 |
| Purifier / fan | Discrete detents (one segment per level), never a slider. | Phase 1 (ESPHome) |
| Printer | **Layer stack**: slices fill from the bottom, the current slice is outlined, and the stack tapers slightly to read as an object. Dual-nozzle / bed / chamber readouts with targets and sparklines. AMS slots as real-colour swatches with remaining % bars; the active slot is outlined "IN USE"; low filament gets ▲ + attention colour. Stop is two-step. | Phase 0 (rendered) |
| TV / Cast | Transport bar; volume as a vertical fader with detented 5-step marks; input selector as a segmented list. | Phase 1 |

## 6. Glyphs and signals

Geometric, drawn in code (`Glyphs.kt`). No icon font, no emoji. Each device kind has a distinct silhouette:
light = disc, TV = 16:10 frame on a stand, receiver = puck, speaker = ring with a centre, thermostat = dial
with ticks, printer = frame with gantry and bed, purifier = louvred column, alarm = concentric rings,
appliance = box with a drum, microcontroller = chip with pins, unknown = diamond.

State is shown by **fill**: ON is filled (with the emitted colour for lights), OFF is outlined, PASSIVE
(sensors) is outlined with a dot, and UNMANAGED (cloud-only or unknown) is a dashed outline on a hatched tile
with a ▲ marker.

## 7. Platform layouts

- **Phone:** single pane. Floor plan → room → device, with a back affordance naming the parent. Exposure
  summary and "Needs a look" sit under the plan. Large targets. Phase 2 adds widgets and Quick Settings tiles.
- **Desktop:** multi-pane. Navigation rail (208 dp) | floor plan with device names | 408 dp inspector.
  Keyboard-first: every target is focusable, with a 2 dp primary-colour focus ring, and a Ctrl+K command
  bar (Phase 2). The rail footer always shows **"NetGuard: LAN only · 0 internet connections"**.
- **Custom title bar (open decision, Phase 1).** An undecorated AWT window loses native resize and
  Windows 11 snap layouts. Two options:
  1. Run on the **JetBrains Runtime** (JBR) and use its custom-title-bar API. That gives native snap,
     but bundles JBR instead of Temurin.
  2. Keep native decorations, themed via DWM dark mode.

  Phase 0 keeps native decorations. Recommendation: option 2 unless you want the custom bar enough to
  accept JBR.

## 8. Motion

- ≤ 200 ms (`short` 90 ms, `medium` 180 ms), using a standard decelerate curve.
- Motion only shows state change or spatial transition (room zoom). Nothing bounces, and nothing animates
  on its own.
- Reduced motion (OS setting): every duration is 0.

## 9. Accessibility

- WCAG 2.2 AA contrast, enforced by test.
- Every glyph and readout has a spoken description ("Floor lamp, on, Local (after setup)";
  "20.6 degrees Celsius"). Legends are semantic headings. Segments expose `selected` and toggles expose
  on/off state.
- Text sizes are `sp` and scale with system font size. Layouts use flexible rows; no fixed-height text
  boxes.
- Colour-independent signalling (§1.3).

## 10. Banned clichés — compliance

No cream/terracotta, no serif display, no pastel cards, no purple-blue gradients, no glassmorphism, no emoji
icons, no uniform tile grid with toggles, no stock Material 3 (we don't depend on `material3` at all), no
bouncy animation, and no greeting. Navigation glyphs ("‹") are text, not emoji.

## 11. Screenshot catalogue

`ScreenshotTest` renders 22 PNGs from `DemoHome` simulator data (deterministic, fixed clock 19:30 UTC):

- `home_phone_{dark,light,night}`, `thermostat_phone_*`, `printer_phone_*` — 412 dp wide @2×, full scroll height
- `home_desktop_*`, `thermostat_desktop_*`, `printer_desktop_*` — 1440 × 900 dp @1×
- `designsystem_*` — token sheet
- `home_phone_dark_fold` — above-the-fold phone view
