# Architecture Decision Records

One file per decision: `NNNN-short-title.md`. Status is one of **Proposed**, **Accepted**, **Superseded by NNNN**.
Any change that weakens a privacy invariant (CLAUDE.md) needs an ADR **and** the owner's explicit approval.

| # | Decision | Status |
|---|---|---|
| [0001](0001-kmp-architecture.md) | Kotlin Multiplatform end-to-end, module layout, manual DI | Proposed |
| [0002](0002-peer-sync-channel.md) | Peer-sync channel: Noise (XXpsk3 pairing, KK sessions) | Proposed |
| [0003](0003-encrypted-database.md) | Encrypted DB: SQLDelight + SQLCipher (Android) / sqlite-jdbc-crypt (desktop) | Proposed |
| [0004](0004-typeface.md) | Typeface: IBM Plex Sans + IBM Plex Mono | Proposed |
| [0005](0005-device-trust-tofu.md) | Device trust: TOFU with SPKI pinning, outside platform trust stores | Proposed |
| [0006](0006-screenshot-testing.md) | Screenshot tests with `ImageComposeScene` instead of Roborazzi | Proposed |

Template:

```markdown
# NNNN — Title
Status: Proposed · Date: YYYY-MM-DD
## Context
## Decision
## Alternatives considered
## Consequences
## Verification / follow-ups
```
