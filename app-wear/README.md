# app-wear (Phase 3, optional)

A Wear OS tile for quick scenes and printer progress. Deliberately **not** in `settings.gradle.kts` yet,
so it adds no build time or dependencies until Phase 3 (brief §10).

When it's added: same privacy invariants as `app-android`, no Google Play Services (it must work on watches
without Play — check what Wear tiles need), LAN-only via the shared `core`.
