# Fable review — working checkpoint (PARTIAL, not the final report)

Reviewer: independent review per `docs/reviews/FABLE_REVIEW_PROMPT.md`. Branch `ci/verification-bootstrap` at `0097eab`.
Started 2026-11-16. This file is a running log of facts and citations so the review can be resumed from it alone.
The final report goes to `docs/reviews/2026-11-16-fable-review.md`; delete this file once that exists.

## Next steps for whoever continues

1. Rules: read-only on everything except `docs/reviews/`. Do **not** read `docs/reviews/2026-11-10-review.md` until Phase 5.
2. Phase 2 is IN PROGRESS. Eight read-only sub-agents were launched (core net/security infra; HAP crypto + Tuya/Kasa/Govee;
   webOS/Cast/Android TV/Bambu/RTSP; LanHome/discovery/model; ui + app shells; build/CI/supply chain/docs; market: platforms;
   market: protocol libraries). Their output is not in this file until a "Sub-agent results" section below says so. If that
   section is missing, re-run those slices. Every sub-agent claim must be re-checked against the code before it is kept.
3. The documented test command fails on a clean checkout (finding F-01 below). To actually run the tests without touching
   repo files use `./gradlew -Ppatchbay.jvmOnly=true --dependency-verification lenient :core:jvmTest :simulators:jvmTest`
   and record pass/fail counts under "Run results".
4. Then: Phase 3 gaps, Phase 4 market (needs web search, cite URL + access date), Phase 5 (now read the 2026-11-10 review
   and label every finding NEW / ALREADY KNOWN / DISAGREE), Phase 6 verdict, Phase 7 roadmap, then write the final report
   in the structure the prompt gives and reply in chat with verdict, top five findings and the single best next step.
5. After each phase: append here, mark the phase, `git add docs/reviews && git commit -m "review: checkpoint" && git push`.

## Phase status

| Phase | Status |
|---|---|
| 1. First impressions | DONE |
| 2. Code and engineering review | IN PROGRESS |
| 3. Gaps | TODO |
| 4. Market, alternatives, feature ideas | TODO (research agents running) |
| 5. De-duplicate against 2026-11-10 review | TODO (that file is still unread) |
| 6. Verdict | TODO |
| 7. Roadmap | TODO |
| Final report written | TODO |

## Phase 1 — first impressions (DONE; written before reading any source)

Basis: `README.md`, `docs/BRIEF.md`, `docs/STATUS.md`, `docs/THREAT_MODEL.md`, the build files, the file listing and `git log` only.

1. A single-owner, AI-authored project (50 commits; `docs/STATUS.md:5` says "autonomous session") in Kotlin Multiplatform that
   talks directly to about twelve device families on the LAN from an Android phone and a Windows laptop. About 24,500 lines of
   Kotlin: core 12,655, simulators 5,154, ui 6,008, app-android 481, app-windows 233, hub 12 (counted with `wc -l`).
2. The audience is one household. `docs/BRIEF.md` is a personal spec, not a product plan. That changes what "worth it" means.
3. Good on paper: a real threat model, invariants written as testable rules, one network choke point (`NetGuard`), a simulator
   and contract tests per adapter, pinned and checksum-verified dependencies, CI actions pinned by SHA.
4. Off immediately: every device row in `docs/STATUS.md:17-47` ends "Not yet tried on your <device>". Twelve protocol clients and
   no recorded contact with hardware. The simulators come from the same author and the same reading of each protocol, so the
   contract tests can prove self-consistency only.
5. Breadth before depth: scenes, automations, adaptive lighting, away mode, quiet hours, a command bar and history graphs were
   stacked on adapters that have never met a device. The riskiest assumption has been tested last, which so far means not at all.
6. Hand-written X25519, Ed25519, SRP-6a and ChaCha20-Poly1305 for HomeKit "no new dependency" (`docs/STATUS.md:43`, ADR 0010), in a
   project whose brief says "Prefer boring, well-maintained libraries over clever ones" (`docs/BRIEF.md:186`).
7. Docs drift: `README.md:7` still says "Status: **Phase 0 (foundation)**. The apps render a simulated demo home; no real devices
   yet"; `docs/STATUS.md:5` is dated 2026-11-05 but commits continue to 2026-11-12 or later; `docs/STATUS.md:34` marks Cast done
   while `docs/STATUS.md:69` lists "Cast v2 / Android TV Remote v2" under "Not done yet".
8. `hub` is 12 lines. Peer sync (a Phase 2 headline in `docs/BRIEF.md:106`) is not visible in the tree. ESPHome (Phase 1 in
   `docs/BRIEF.md:172`) is absent. `core/src/jvmShared/kotlin/dev/patchbay/core/home/LanHome.kt` at 850 lines looks like a god object.
9. The branch is named `ci/verification-bootstrap`, and `docs/STATUS.md:112-116` says pushing to it makes CI print fresh
   verification metadata into public job logs for copy-back. That is trust-on-first-download, pasted back by the author.
10. Strategic smell: this rebuilds the device-integration layer of Home Assistant for an audience of one. To be tested in Phase 4.

## Phase 2 — code and engineering review (IN PROGRESS)

### Run results

- `./gradlew -Ppatchbay.jvmOnly=true :core:jvmTest :simulators:jvmTest` on a clean checkout, 2026-11-16, JDK from the sandbox,
  Gradle 9.8.2 downloaded by the wrapper: **BUILD FAILED in 50s, zero tests ran.** See F-01.
- `tools/check-policy.sh`: not yet run.
- Android, Compose UI, MSI: cannot be built here (prompt says CI only). Not run.

### Findings so far

**F-01 (high severity, high confidence, status vs previous review: not yet compared).** The documented core-only build does not pass
the project's own dependency verification on a clean checkout.
- Command: the one in `README.md:33` and `CLAUDE.md` ("core only; no Android SDK or Google Maven needed").
- Output: "A problem occurred configuring root project 'patchbay'. > Dependency verification failed for configuration 'classpath'
  12 artifacts failed verification". The HTML report says "Checksums are missing from verification metadata" for each of:
  `guava-parent-33.7.1-jre.pom`, `jackson-base-2.18.4.pom`, `jackson-base-2.22.2.pom`, `jackson-bom-2.18.4.pom`,
  `jackson-dataformats-text-2.18.4.pom`, `jackson-dataformats-text-2.22.2.pom`, `jackson-parent-2.18.1.pom`,
  `junit-bom-5.10.2.module`, `junit-bom-5.14.3.module`, `junit-bom-5.14.4.module`, `junit-bom-6.1.3.module`, `oss-parent-61.pom`.
- Evidence in the metadata: `gradle/verification-metadata.xml:5356-5357` has `junit-bom` 5.10.2 with a `.pom` entry only, likewise
  5.14.3 (`:5382-5383`) and 5.14.4 (`:5387-5388`); `guava-parent` exists only for `26.0-android`, `33.3.1-android`, `33.4.0-android`
  (`:2496-2507`), not `33.7.1-jre`; `oss-parent` exists for 68 and 79 (`:2222-2228`), not 61.
- It is a missing-checksum failure, not a mismatch, so it is not evidence of tampering. It does mean the metadata was generated
  for a different resolution of the root buildscript classpath than the JVM-only build performs.
- Why it matters: `docs/STATUS.md:13` claims verification is "Strict … Every build", and the author's own sandbox instructions
  (`docs/STATUS.md:103-110`) use a locally installed `gradle`, not the wrapper. Either the author's local runs relied on a warm
  cache or lenient mode, or the JVM-only path has never passed strict verification from clean. Cause still to be established
  (sub-agent asked; also check what command CI's JVM job runs).
- Also noted: `gradle/verification-metadata.xml:5` has `<verify-signatures>false</verify-signatures>` and every entry seen so far
  is `origin="Generated by Gradle"`, so the checksums record what CI downloaded once; nothing ties them to a publisher signature.

### Sub-agent results

None recorded yet.

## Phase 3 — gaps (TODO)

## Phase 4 — market and alternatives (TODO)

## Phase 5 — comparison with the 2026-11-10 review (TODO; file not yet read)

## Phase 6 — verdict (TODO)

## Phase 7 — roadmap (TODO)

## What could not be verified (running list)

- Anything on real hardware. This sandbox has no LAN devices.
- The Android app, the Compose UI and the Windows MSI: not buildable here.
