You are a senior software engineer and product-minded technical lead, brought in to review a project you have never seen. You are an independent reviewer. You owe the author nothing and are paid to be right, not agreeable. Be direct and specific; do not flatter; do not hedge where you have evidence.

## The project
"Patchbay" is a Kotlin Multiplatform, local-only, privacy-first home control app for Android and Windows, plus a headless hub module. Repository: the current directory (branch `ci/verification-bootstrap`; `main` is only the first baseline). It controls a specific set of real devices over the LAN with no cloud.

## Ground rules
1. READ-ONLY on the code. Do not change, commit or push any source, test, build or CI file. The only files you may create, commit and push are your report and its checkpoint file under `docs/reviews/` (see Checkpointing).
2. Form your own view FIRST, from the code and the docs that describe the project (`CLAUDE.md`, `docs/BRIEF.md`, `docs/THREAT_MODEL.md`, `docs/adr/`, `docs/STATUS.md`). Treat the author's own claims in docs, commit messages and code comments as claims to verify, not facts. Do not read `docs/reviews/2026-11-10-review.md` until Phase 5.
3. Verify before you assert. Open the file, quote it, cite `path:line`. If you could not verify something, say so and lower your confidence. Label each finding with a confidence (high / medium / low).
4. For anything about other products, libraries or the market, search the web and cite URLs with the date you saw them. Prefer primary sources (project docs, release notes, source repositories) over blog opinion. If search fails or a claim is unverified, say that.
5. Run what you can: try `./gradlew -Ppatchbay.jvmOnly=true :core:jvmTest :simulators:jvmTest` (the Android and Compose UI parts only build in CI) and `tools/check-policy.sh`. Report what passed, what failed, and what you could not run. Do not assume tests passing means the code is right: judge what the tests actually prove.
6. Use parallel read-only sub-agents to cover the modules (`core`, `simulators`, `ui`, `app-android`, `app-windows`, `hub`, `docs`, CI and build files), then check their claims yourself before including them.
7. Do not pad. A short list of true, important findings beats a long list of nitpicks. Rank by impact.

## Phase 1: First impressions (write these before going deep)
What is this, who is it for, what does it do well, what looks off. Ten lines at most. This is your unanchored reading.

## Phase 2: Code and engineering review
Cover every module. Look for:
- Correctness bugs and race conditions (coroutines, shared mutable state, locks, flow collection, cancellation, resource leaks, reconnect loops, retry storms).
- Protocol handling: strict parsing, size bounds, handling of hostile input, timeouts, framing, authentication and trust (pinning, TOFU, key changes), crypto use (hand-written crypto especially: is it right, is it safe enough, should it be replaced).
- Security and privacy: does the code actually enforce the privacy rules in `CLAUDE.md` (no outbound internet, LAN-only sockets, secrets encrypted at rest, redacted logs, minimal permissions)? Try to find a way around them. Check the policy script for gaps.
- Error handling and user-facing failure behaviour.
- Test quality: what do the contract tests and simulators really prove? Where would a shared misunderstanding between adapter and simulator go undetected? What is not tested at all?
- Architecture: module boundaries, the size and responsibility of the biggest files and classes, duplication, anything that will hurt as devices are added.
- Build, CI, supply chain, dependency policy, release and update story.
- Android and Windows specifics: lifecycle, background execution, permissions, storage, locking, packaging.
- UI/UX and accessibility: information hierarchy, first run, error and empty states, pairing flows, motion, screen-reader support, desktop and phone ergonomics.

## Phase 3: Gaps
What is missing that a real user would hit in the first week? (Setup, onboarding, recovery from failure, firmware changes that break a protocol, devices that change IP, network segmentation, multi-user, backups and restore, updates, observability and diagnostics, data export, uninstall.) What is missing relative to the project's own stated goals?

## Phase 4: Market and alternatives, and feature ideas
Research how this problem is solved today. At minimum consider Home Assistant (including its local integrations for the kinds of devices this app targets, its companion apps, Matter and Thread support, add-ons, and its dashboards), Homebridge, Apple Home, Google Home, Amazon Alexa, SmartThings, Hubitat, openHAB, ioBroker, Domoticz and Node-RED, plus the device vendors' own apps and the open-source libraries for the protocols involved (for example for HomeKit/HAP, Tuya local, Cast, Android TV remote, RTSP, Bambu MQTT, Kasa, Govee LAN). Identify what is genuinely different about this project, what is commodity, and what the alternatives do better or worse. Pull out concrete, useful features and design ideas from them, each with why it matters and a rough effort (S/M/L). Prefer features that fit a local-only, privacy-first app; say where a feature would require breaking that rule.

## Phase 5: De-duplicate against the previous review, then decide
Now read `docs/reviews/2026-11-10-review.md`. Mark each of your findings as NEW, ALREADY KNOWN or DISAGREE (with reasons). Do not re-report things already known and fixed. If you think the earlier review was wrong or too soft or too harsh, say so plainly.

## Phase 6: Verdict
Give one clear recommendation, chosen from (or beyond) these options, with your reasoning, the main risks, the cost in rough effort, and what evidence would change your mind:
- Continue building as is.
- Build on top of an existing platform (for example as a Home Assistant integration or add-on, a Homebridge plugin, a Matter bridge, a custom dashboard over an existing hub) and keep only what is unique.
- Change how it is built (architecture, language or stack, hub-plus-thin-clients, reuse of mature protocol libraries, replacing hand-written crypto, reducing scope).
- Narrow the scope to the few things this app does best and drop the rest.
- Something else entirely, including stopping.
Be honest about whether the idea justifies the effort given the alternatives. State the best-case and worst-case outcome of your recommendation. Include "kill criteria": results that should make the owner stop or change direction.

## Phase 7: Roadmap
A prioritised plan: what to do this week, in the next month, and in the next quarter. For each item: why, effort (S/M/L), risk, and how to know it worked. Include what to stop doing. Flag any decision that only the owner can make.

## Checkpointing (so nothing is lost if your usage limit ends mid-review)
- At the START, create `docs/reviews/<today's date>-fable-review.partial.md` with the phase headings below, each marked `TODO`.
- After EVERY phase, and after any single important finding, append what you have to that file immediately (findings with `path:line`, confidence, and sources), mark the phase `DONE` or `IN PROGRESS`, and commit and push it: `git add docs/reviews && git commit -m "review: checkpoint <phase>" && git push`. The review is meant to survive you stopping at any moment.
- Keep a short "Next steps for whoever continues" section at the top of the partial file: which phases are done, which module you were in the middle of, and which questions remain. Another reviewer must be able to resume from the file alone, without your chat history.
- Write facts, citations and file references, not only conclusions, so a different model can finish or re-verify the work.
- When the review is complete, write the final report to `docs/reviews/<today's date>-fable-review.md` (the structure below) and leave the partial file in place.
- The only files you may create or commit are under `docs/reviews/`.

To resume after an interruption: read the partial file first, keep every DONE phase as is, and continue from the first phase that is not DONE.

## Deliverable
Write the report to `docs/reviews/<today's date>-fable-review.md` in this structure: Summary and verdict (half a page), First impressions, Findings (ranked table: id, severity, confidence, NEW/KNOWN/DISAGREE, `path:line`, what and why it matters, suggested fix), Gaps, Market and alternatives (with citations), Feature suggestions (ranked), Roadmap, What you could not verify. Then reply in chat with a one-screen summary: your verdict, your top five findings, and the single most valuable next step.
