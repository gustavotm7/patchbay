# Independent review: Patchbay at `56fca43` (branch `ci/verification-bootstrap`)

Reviewer: Claude Opus 5.5, 2026-11-18. Scope: the whole repo. I read the code, ran the JVM suite and the policy script, and wrote throwaway reproductions in a scratch copy of the repo (outside the tree). Nothing tracked was changed.
Evidence key: **confirmed** = reproduced, or unambiguous from the code. **likely** = strong external evidence or platform semantics, not run on a device. **suspected** = plausible, needs a check.

What I ran:
- `gradle -Ppatchbay.jvmOnly=true --offline --rerun-tasks :core:jvmTest :simulators:jvmTest`. 465 tests, 0 failures, 0 skipped, 2 min 29 s. The 465 `@Test` annotations match the 465 executed, so no test method is silently skipped as non-void. 4 of them are env-gated no-ops, though (F-M7).
- `tools/check-policy.sh` and `--self-test`. Both pass.
- A planted-bypass tree for the policy script (F-H2).
- Kotlin reproductions for the Redactor, backup restore and Keystore init-order findings (F-M2, F-H5, F-L1).

---

## 1. Verdict

Patchbay is unusually disciplined for a two-week solo project. It has a typed `LanAddress` choke point, pinned TLS everywhere, strict bounded parsers, Wycheproof-checked curves and real interop runs against reference libraries. I found **no code path today that reaches the internet**.

Its weaknesses sit in the gap between "proven" and "true":
- The policy script that "proves" invariant 1 is a grep that a one-line import alias defeats, and it doesn't scan the source set where most adapters live.
- Several runtime behaviours advertised in STATUS/HARDWARE_TEST will not hold on real devices or platforms: Cast trust, Android screen-off automations, restore, and trust-change "stops all contact".
- One unhandled-exception path can take the Android process down.

The biggest risk is still that no protocol has met a real device. The second biggest is breadth: 9 protocols, scenes, automations with triggers and conditions, backup and diagnostics, ~21 k lines of main code in 14 days. That leaves a maintenance surface one person cannot keep correct as firmware drifts.

Recommendation: freeze features, fix the High items below, then do the hardware pass.

| Area | Rating (1-5) | Why |
|---|---|---|
| Privacy/security | **3.5** | The runtime design is sound (no DNS, typed addresses, pinning, encrypted at rest). The enforcement claim is overstated (F-H2), the redaction safety net has holes (F-M2), and there are Basic-auth downgrade and unverified-ffmpeg gaps |
| Correctness | **3** | Clean core logic, but unprotected coroutine loops (F-H4), missed automations (F-H3), restore clobbering (F-H5), and a sticky-restart storage failure on Android (F-H6) |
| Test strength | **3** | 465 green, good vectors and interop. But the adapters are only ever tested against same-author simulators, "mutation-tested" means random no-throw corruption, the interop tests are vacuous in CI, and there are 0 Android/Windows runtime tests |
| Real-world readiness | **2** | Never run on hardware. At least one design assumption (Cast leaf pinning) is very likely wrong. Android background behaviour is unproven |
| Maintainability | **3** | Good module split and small files, but breadth is high for one maintainer, there are two build files per module, and features were added against the plan's own freeze |
| Docs | **3** | Voluminous and mostly honest, but there are overclaims ("proven", "stop all contact", "mutation-tested"), stale lines and duplicate hardware-test IDs |

---

## 2. Findings (ranked)

### High

**F-H1. Cast pins a TLS leaf that Cast receivers rotate every few days.**
- **Where:** `core/.../adapter/cast/CastAdapter.kt:171,304-311`; the rationale is in `docs/protocols/cast.md:20-33`.
- **What is wrong:** The adapter pins the SPKI of the TLS leaf on port 8009. Chromium's own Cast sender rejects receiver TLS certs valid for more than `kMaxSelfSignedCertLifetimeInDays = 4`. That constant was raised from 2 days in 2015 ("Increase tolerance for receiver's certificate expiry"). Receivers therefore issue short-lived self-signed peer certs and regenerate them. Device identity in Cast is carried by the **device-auth** challenge: the device signs the TLS peer cert with its stable platform key. The protocol doc says "I found no report that it rotates", but the reference sender says otherwise.
- **Failure scenario:** A Chromecast or Google TV pairs fine, then within 1-4 days (or at its next reboot) shows the red "Security alert", and Cast controls stop working. The owner learns to click through key-change alerts, which defeats invariant 5 for every other device.
- **Fix:** Implement the `urn:x-cast:com.google.cast.tp.deviceauth` challenge:
  1. Pin the SPKI of the device-auth leaf (the platform cert, stable per device) at pairing.
  2. On each session, verify its signature over the current TLS peer cert. Do not validate Google's CA chain (keep the doc's reasoning there).

  Until then, pin by the Cast `id` from mDNS plus device-auth. Add an explicit C1.9 hardware check (below).
- **Effort / confidence:** M. **likely**: the constant is confirmed in [Open Screen cast_auth_util.cc](https://chromium.googlesource.com/openscreen/+/refs/heads/main/cast/sender/channel/cast_auth_util.cc), [Chromium cast_auth_util.cc](https://chromium.googlesource.com/chromium/src/+/dc1b807d/components/cast_channel/cast_auth_util.cc) and [the 2015 change](https://codereview.chromium.org/1011133005/patch/1/10001). Whether the *key* (not just the cert) rotates is not proven here.

**F-H2. The NetGuard policy check is a grep with trivial bypasses and a blind spot.**
- **Where:** `tools/check-policy.sh:56` (`KT_MAIN`) and `:59` (NET001 pattern).
- **What is wrong:**
  1. URL001 only scans source sets whose name ends in `main`/`Main`, so `core/src/jvmShared/**`, where every adapter, NetGuard and the media code live, is never scanned for URL literals.
  2. NET001 has no rule for `ProcessBuilder`/`Runtime.exec`, `URL(...).readText()/readBytes()`, `InetSocketAddress(String, Int)` (which resolves DNS at construction), import aliases (`import java.net.Socket as S`) or reflection.
- **Evidence:** A planted tree (scratchpad `policy-bypass/`) with these lines exits `policy check: OK`:
  - `S("8.8.8.8",53)` via the alias
  - `URL("https://example.com/collect").readText()`
  - `InetSocketAddress("telemetry.example.com",443)`
  - `ProcessBuilder("curl","https://example.com")`
  - `URL("https:"+"//example.com").readBytes()`
  - `Class.forName("java.net."+"Socket")`

  The `--self-test` only plants the patterns the rules already match.
- **Failure scenario:** A future change (or a contributor's or an AI's patch) adds telemetry or an update check in `jvmShared`, and CI stays green while CLAUDE.md says the invariant is "proven by unit tests".
- **Fix:**
  - Short term: extend `KT_MAIN` to all non-test source sets, add the patterns above, and add each bypass to the self-test.
  - Real fix: a **bytecode** check over the compiled classes **and the runtime classpath**. Use `jdeps --api-only`, or a small ASM/ArchUnit test, to fail on any reference to `java.net.{Socket,URL,URLConnection,InetAddress.getByName,DatagramSocket,...}`, `java.lang.ProcessBuilder` or `java.net.http` outside `dev.patchbay.core.net` plus a reviewed allowlist (Media3, ffmpeg launcher). Grep can't see aliases, reflection or third-party jars.
  - Optionally: on desktop, install a default `ProxySelector`/`SocketImplFactory` guard that refuses non-LAN destinations at runtime, as a belt-and-braces measure.
- **Effort / confidence:** S + M. **confirmed.**

**F-H3. Scheduled automations are skipped, not delayed, whenever no tick lands in their exact minute (Android screen-off especially).**
- **Where:** `core/.../home/Automations.kt:174-200` (exact `hour/minute` match, `lastRun` per minute key); `LanHome.kt:173-181` (a 15 s `delay` loop); `app-android/.../WatchService.kt` (no wake lock, no alarms, ADR 0011).
- **What is wrong:** With the screen off and no wake lock, the CPU suspends and `delay` timers don't fire until something wakes it. If no tick runs between hh:mm:00 and hh:mm:59, the automation never runs that day. ADR 0011 calls this "best-effort at the minute level", but the behaviour is all-or-nothing, and HARDWARE_TEST N2.2 expects a pass. The same happens on Windows across sleep or hibernate. On DST spring-forward days, a 02:30 automation silently never runs.
- **Failure scenario:** "Lights on at 06:45" doesn't happen most mornings on a phone left on the nightstand.
- **Fix:**
  - Change the rule to "due and not yet run for this occurrence": keep `lastRun` per occurrence (date + automation), run if `now` is within a grace window (say 10 min) after the scheduled instant, and persist `lastRun`. Treat a nonexistent local time as the first valid instant after it.
  - On Android, schedule the next occurrence with `AlarmManager.setExactAndAllowWhileIdle`, or `setAlarmClock` for user-visible wakes. That needs `SCHEDULE_EXACT_ALARM` or `USE_EXACT_ALARM` (an invariant-8 change, so an ADR), or use inexact `setAndAllowWhileIdle` plus the grace window.
- **Effort / confidence:** M. **likely**: the code is clear; the Doze/suspend behaviour is standard Android.

**F-H4. No exception boundary on process-wide coroutines.**
- **Where:** `core/.../boot/Bootstrap.kt:107` (`CoroutineScope(SupervisorJob() + Dispatchers.Default)` with no `CoroutineExceptionHandler`; none exists anywhere in the repo); `LanHome.kt:173-181` (the automation/adaptive/away tick loop has no try/catch, unlike the heal loop below it); `LanHome.kt:242` (`scope.launch { adapter.observe(id).collect { ... } }`).
- **What is wrong:** Any unexpected `RuntimeException` ends that coroutine. Examples: a `SQLException` from `activity.record` → `putSetting` when the disk is full, an `IllegalArgumentException` from a `require`, a JCA `GeneralSecurityException` (see F-M3). On Android, uncaught coroutine exceptions go to the thread's default handler and **kill the process**. On desktop, that loop or observer silently stops forever: automations stop, or one device freezes at its last state while the UI shows it "online".
- **Fix:**
  - Give the root scope a handler that logs (redacted), counts into diagnostics and surfaces a banner.
  - Wrap each tick and each observer `collect` in a supervised restart loop with back-off: `catch (CancellationException) { throw }`, `catch (Exception) { log; delay; retry }`.
  - Add a test that injects a throwing store or adapter.
- **Effort / confidence:** S. **confirmed** (by reading; the semantics are standard kotlinx.coroutines).

**F-H5. A restored backup is silently overwritten by the still-running session.**
- **Where:** `LanHome.kt:144-161`, `backup/Backup.kt:178-186`. Every part (`Scenes`, `Automations`, `HomePreferences`, `AwayMode`, `AdaptiveLighting`) persists its *in-memory* state on the next change.
- **What is wrong:** The message says "Close Patchbay and open it again". With Windows tray mode, closing the window doesn't quit, and on Android the watch service keeps the process alive. Any change made before a real restart writes the pre-restore state over the restored keys. A scan or self-heal also re-saves devices the restore just forgot.
- **Evidence:** A reproduction test in the scratch copy:
  1. Favourite d1, then back up.
  2. Change the favourites to {d2} and restore (store = ["d1"]).
  3. Favourite d3. The store is now ["d2","d3"], and a fresh `LanHome` on that store shows d2 and d3. The restored d1 is gone.

  Scenes and automations behave the same way.
- **Fix:** After a successful restore, put `LanHome` into a "restart required" state that refuses all persistence and adapter changes, and offer a real restart: on desktop, relaunch the process; on Android, stop the service and `finishAffinity` + `Process.killProcess`. Better still, reload the parts from the store in-process. Also wrap the restore writes in one SQL transaction.
- **Effort / confidence:** S-M. **confirmed.**

**F-H6. Android: a sticky restart while the phone is locked runs the whole process without storage.**
- **Where:** `app-android/.../AndroidStorage.kt:41` (`setUnlockedDeviceRequired(true)`), `WatchService.kt` (`START_STICKY`), `Bootstrap.kt:83-94` (in-memory fallback, decided once per process).
- **What is wrong:** If Android kills the process while background watching is on, it restarts the service. `Application.onCreate` runs Bootstrap while the screen is locked, the Keystore key is unusable, and the session falls back to `InMemoryHomeStore`. From then until the process dies, it watches an empty home, runs no automations, and after the user unlocks and opens the app it shows no devices and "nothing will be saved". The user may re-pair into memory.
- **Fix (pick one, with an ADR):**
  - Defer opening storage until `ACTION_USER_PRESENT`, or retry in `MainActivity.onStart`, and rebuild `LanHome` then.
  - Return `START_NOT_STICKY`.
  - Drop `setUnlockedDeviceRequired` (weaker at rest, but consistent with a service meant to run while locked).
- **Effort / confidence:** M. **likely.**

### Medium

**F-M1. Trust-change handling is inconsistent and doesn't match the docs.**
- **Where:** `CastAdapter.kt:304-311`, `WebOsAdapter.kt:302-306`, `BambuAdapter.kt:283-287`, `AndroidTvRemoteAdapter.kt:256-260`. Compare `HomeKitAdapter.kt:273`, which is sticky.
- **What is wrong:** After `TrustChanged`, four adapters keep retrying TLS handshakes with back-off (≤ 60 s), and **clear the alert by themselves** when a later handshake matches the old pin. STATUS says changed keys "stop all contact", and HARDWARE_TEST N1.7 says "Patchbay doesn't talk to it until you remove it and pair again". No secret is sent, because the pin check runs before application data, so this isn't a leak. But an ARP-spoof window produces an alert that later vanishes, and `DeviceWatcher` re-alerts on each flap.
- **Fix:** Make the latch sticky in every adapter (only re-pairing clears it), as HomeKit does, or fix the docs. Add a contract test per adapter: impostor, then the real device returns, and the alert must persist.
- **Effort / confidence:** S. **confirmed.**

**F-M2. Holes in the redaction safety net.**
- **Where:** `core/.../log/Redactor.kt:21-29`.
- **Evidence:** `Redactor.redact` (run in Kotlin in the scratch copy) leaves these strings unchanged:
  - `rtsp://admin:hunter2pass@192.168.1.5/live0`
  - `Authorization: Basic YWRtaW46aHVudGVyMg==` (only the word "Basic" is masked)
  - `access code 12345678 rejected`
  - `setup code 123-45-678 refused`

  It also masks its own boot line: `key=windows-dpapi` becomes `key=•••`, so the log never says which key protection was used.
- **Scope:** Current callers avoid these. The camera lease URL does carry the password (`RtspCameraAdapter.kt:181`), so one careless `log.info(lease.url)` or an exception message leaks it.
- **Fix:** Add rules for URI userinfo, `Basic <b64>` of any length, space-separated `code|key|password <value>`, and the `NNN-NN-NNN` pattern. Add these strings to `RedactorTest` and `DiagnosticsSecretsTest`. Rename the boot field to `keystore=`.
- **Effort / confidence:** S. **confirmed.**

**F-M3. The ChaCha20-Poly1305 transformation name may not exist on Android.**
- **Where:** `homekit/HapCodec.kt:98` (`Cipher.getInstance("ChaCha20-Poly1305")`).
- **What is wrong:** The JDK name is `ChaCha20-Poly1305`. Android's documented Cipher name (Conscrypt, API 28+) is `ChaCha20/Poly1305/NoPadding`, and I could not confirm that an alias exists. If it doesn't, `NoSuchAlgorithmException` (a `GeneralSecurityException`, not an `IOException`) escapes `Aead.seal` during pairing. That makes HomeKit dead on Android and, with F-H4, possibly a crash.
- **Fix:** Try `ChaCha20/Poly1305/NoPadding` first and fall back to the JDK name. Add a startup self-check of every JCA name used (list in §5) whose result goes into diagnostics.
- **Effort / confidence:** S. **suspected** (high impact, cheap to verify).

**F-M4. A failed HomeKit unpair is swallowed.**
- **Where:** `HomeKitAdapter.kt:213` (`catch (_: IOException) {}`).
- **Failure scenario:** The thermostat is offline or busy when the owner removes it. Patchbay forgets the device but the ecobee still holds the pairing, so later Apple Home or a re-pair answers "already paired" (error 6), with no hint why. This also breaks the "never swallow exceptions" convention.
- **Fix:** Return or report the failure, and keep a "pending unpair" record that is retried and shown.
- **Effort / confidence:** S. **confirmed.**

**F-M5. The Tuya parser rejects a whole report over one long data point.**
- **Where:** `TuyaProtocol.kt:25` (`MAX_PAYLOAD = 4096`) and `:133` (a string DP over 256 characters fails the whole message).
- **Failure scenario:** Robot vacuums report raw (base64) DPs, such as cleaning records or path fragments, that are often over 256 characters. Every reply is then a "protocol violation", so the Lefant "learn" mode (V1.x) can never list its data points.
- **Fix:** Drop or truncate oversize DPs one by one (count them in diagnostics). Raise the payload bound for the read-only profile to about 16 KiB.
- **Effort / confidence:** S. **likely** for the vacuum.

**F-M6. The automation loop guard is a timing heuristic, not a causal one.**
- **Where:** `Automations.kt:35-37,84-89,111-128`.
- **What is wrong:** The 30 s window only works while each device reports a commanded change within 30 s. A device in back-off (up to 60 s), offline then back online, or the camera's 15 s poll plus retries reports later. Two automations that undo each other can then oscillate indefinitely (period ≥ 30 s), and there is no per-automation rate limit.
- **Fix:** Ignore events from a device for N seconds after an automation *commanded that device*, and cap each automation at, say, 6 runs per 10 minutes with an Activity line and a notification.
- **Effort / confidence:** S. **suspected.**

**F-M7. The interop tests pass vacuously in CI and inflate the count.**
- **Where:** `HapPythonInteropTest.kt:39-40`, `RtspFfmpegInteropTest.kt:21-22,39-41`, `SimulatorHostForInterop.kt:15`.
- **What is wrong:** These 4 `@Test`s `return@runBlocking` when env vars are missing, and CI never sets them, so they report **passed**. The interop evidence is a one-off manual run (`docs/protocols/interop.md`), not a regression test.
- **Fix:** Use `assumeTrue(...)` so they report skipped. Add a CI job that installs the pinned Python reference libraries (hash-locked `requirements.txt`) and runs `tools/interop/run_clients.py` and the HAP-python test on loopback.
- **Effort / confidence:** S/M. **confirmed.**

**F-M8. ffmpeg is an unverified, unsandboxed binary outside NetGuard.**
- **Where:** `media/CameraVideo.kt:17-26` (falls back to *any* `ffmpeg` on `PATH`) and `:60`.
- **What is wrong:** `-protocol_whitelist pipe` is good. But the binary itself is arbitrary: a PATH-planted ffmpeg runs with the user's rights. It also parses hostile camera bitstreams (ffmpeg decoder CVEs are routine) with full network access, outside invariant 1's enforcement.
- **Fix:** Only accept `ffmpeg.exe` from the app folder. Show its SHA-256 and pin it on first use. Run it in a Windows Job Object with kill-on-close, and document a Windows Firewall block rule for it. Mention it in THREAT_MODEL.
- **Effort / confidence:** S-M. **confirmed** (design).

**F-M9. RTSP Basic-auth downgrade.**
- **Where:** `rtsp/RtspAuth.kt:16-19`. Basic is used whenever the server challenges with Basic.
- **Failure scenario:** An ARP-spoofer on the LAN answers DESCRIBE with `WWW-Authenticate: Basic` and receives the camera password in clear text on the next poll, which is every 15 s.
- **Fix:** Record the scheme at pairing and refuse Basic if Digest was seen. Show "this camera only offers Basic" at pairing.
- **Effort / confidence:** S. **confirmed.**

**F-M10. Dependency verification is checksum TOFU.**
- **Where:** `gradle/verification-metadata.xml:4-5` (`verify-signatures=false`; 725 components bootstrapped from CI output).
- **What is wrong:** It pins whatever CI downloaded the first time. Reviewing 725 hashes isn't realistic.
- **Fix:** Turn on signature verification with `trusted-key` entries for the main publishers (JetBrains, Google/AndroidX, Cash App, Zetetic, JNA, willena). Keep checksums for the unsigned ones.
- **Effort / confidence:** M. **confirmed.**

**F-M11. Process: the feature freeze was broken.**
- **Where:** `docs/PLAN.md` ("No new product features… waits for hardware results").
- **What is wrong:** Automation triggers (ADR 0014) and conditions (ADR 0017) landed on 11-17 and 11-18 anyway. Each new feature adds untested real-world surface before the first device has been tried. (Process finding; confirmed from PLAN and git history.)

### Low / Nit

| # | Finding | Where | Confidence |
|---|---|---|---|
| L1 | The StrongBox flag is misreported. `key` (which may set `strongBox = false`) is declared before `strongBox`'s initialiser, so the initialiser resets it to `true`. On later starts it reflects hardware capability, not the key. An analogue test printed `android-keystore-strongbox` after the fallback | `AndroidStorage.kt:27-29` | confirmed |
| L2 | The Android lock fails open on *any* `canAuthenticate != SUCCESS`, not only "no screen lock". Unlock only when `BIOMETRIC_ERROR_NONE_ENROLLED` **and** `!KeyguardManager.isDeviceSecure` | `AndroidLock.kt:37-40` | suspected impact |
| L3 | The Windows PIN back-off lives in memory, so restarting the app resets it (4-digit PIN at about 0.3 s per attempt). Persist `failures`/`blockedUntil` | `AppLock.kt:28-30` | confirmed |
| L4 | Damaged settings are dropped silently and then overwritten on the next save, with no log (the automations, scenes and prefs `load()` functions). `Conditions.fromStored` drops a damaged window, which makes the automation run **unconditionally** (fail-open) | `Automations.kt:43`, `Conditions.kt:51-58` | confirmed |
| L5 | A WebSocket read timeout mid-frame desyncs the stream: the next `receiveText` starts inside a frame | `WebSocketClient.kt:47-80` | suspected |
| L6 | The SRP client exponent is 16 bytes (RFC 5054 recommends ≥ 256 bits) and there is no `u == 0` check. Little impact, because pair-verify uses fresh X25519. Use 32 bytes | `HapClient.kt:278`, `HapCodec.kt` | confirmed |
| L7 | The curves use non-constant-time `BigInteger`, including Ed25519 *signing* with the long-term key. Acceptable under the threat model, and Wycheproof passes. Plan to move to platform XDH/EdDSA when minSdk reaches 33 | `Curve25519.kt` | n/a |
| L8 | Restore is not transactional, and pairings and settings missing from the backup are left in place | `Backup.kt:178-186` | confirmed |
| L9 | "LAN" includes 10/8 and 172.16/12, which on a VPN route off-site. `localBroadcastAddresses` broadcasts discovery into VPN interfaces. Exclude interfaces that are point-to-point or not Wi-Fi/Ethernet | `NetGuard.kt:166-174` | confirmed |
| L10 | The accept-any client trust manager (`ClientCertCapture`) ships in `core` main, though only simulators use it. Move it to a test-fixtures source set | `net/tls/ClientCertCapture.kt` | confirmed |
| L11 | 116 hard-coded `dp/sp` values in `ui/screens` (28 in `ThermostatPanel.kt`) and gradients in `LightPanel.kt:221-245`. The pickers are defensible, but CLAUDE.md says "no gradients" with no exception. Add the exception to DESIGN_SPEC, or tokens | `ui/.../screens/*` | confirmed |
| L12 | Docs drift: hardware-test IDs **A1.x, B1.x and D1.x each mean two different things**; E1.7 still says "Windows has no in-app video yet"; the PLAN backlog lists Tuya 3.4/3.5, which is already done (`PLAN.md:65`); CLAUDE.md gives a different Bootstrap order from the code; A0.2 is stale | `docs/HARDWARE_TEST.md`, `docs/PLAN.md` | confirmed |
| L13 | The Windows jlink runtime (`modules("java.sql","jdk.unsupported")`) and DPAPI wrap/unwrap are never executed in CI: the desktop smoke job runs on Linux and the Windows job only packages. Run the portable exe headless on `windows-latest` and unwrap a key | `.github/workflows/ci.yml:101-160` | confirmed |
| L14 | "Mutation-tested parsers" are random-byte corruption of 1-2 seeds with a no-throw oracle. Most Tuya and HAP mutations die at the CRC or tag, so the layers behind them are barely exercised, and the socket *read loops* (Cast, MQTT, WS, HAP, Tuya streams) are not fuzzed at all. Real mutation testing (PIT) or Jazzer would show what the oracle misses | `*Test.kt` mutation tests | confirmed |
| L15 | `Automations.load` writes "dropped N automations" only for unknown triggers. Other decode failures write nothing | `Automations.kt:43-46` | confirmed |

---

## 3. What is genuinely good

- **The typed choke point.** `LanAddress` has an internal constructor, there is no hostname API, `InetAddress.getByAddress` only, `Proxy.NO_PROXY`, inbound datagrams from non-LAN sources are dropped, and IPv4-mapped/NAT64/6to4 are handled (`AddressPolicy.kt`). I found no internet path in shipped code.
- **TLS pinning without trust-store dependence** (`PinnedTls.kt`): leaf SPKI, constant-time compare, TLS 1.2+, no hostname bypass hacks.
- **HAP implementation quality:** strict TLV, bounded HTTP/chunked parsing, a correct nonce and counter scheme, an all-zero X25519 check, an Ed25519 verify that rejects non-canonical `s`/`y`, Wycheproof (728 vectors) plus pairing against HAP-python. Hand-writing it was a reasonable call given minSdk 31.
- **Backup crypto** (`Backup.kt`): PBKDF2 600 k, an authenticated header, iteration bounds against hostile files, validate-before-write.
- **Bounded parsers everywhere:** WS 256 KiB, MQTT 512 KiB, HAP 1 MiB with 32 headers, Kasa and Tuya 4 KiB, Cast and Android TV with length checks.
- **Real interop runs** that found real bugs (Cast reply addressing, webOS id typing, H.265 refusal, ffmpeg probe latency). This is the strongest evidence in the repo.
- **Diagnostics built for the hardware pass:** masked ids and addresses, name scrubbing, a secrets test per adapter.
- **CI hygiene:** actions pinned by SHA, wrapper sha256, an APK permission allowlist, an SBOM, WiX checksum-verified.

---

## 4. Top 10 most likely to break on real hardware, and how to check each

1. **Cast trust alert after 1-4 days** (F-H1). Pair a Chromecast and note the fingerprint. Check again after 48 h and after a reboot. A different fingerprint confirms.
2. **Android screen-off automations don't run** (F-H3). An automation 20 min ahead, phone locked and unplugged, watching on. Check Activity afterwards, and repeat with the phone on a charger.
3. **HomeKit on Android** (F-M3). Pair the ecobee from the *phone* first. If pairing fails or the app crashes, copy diagnostics or logcat for `NoSuchAlgorithmException`.
4. **Tuya: one connection at a time, phone and laptop both polling.** Run Windows and Android at the same time against a Lepro bulb. Look for flapping "No reply" or refused connections, and test with the Smart Life app open. Re-pairing a bulb in Smart Life changes its local key, so expect U1.3 symptoms.
5. **mDNS legacy-unicast discovery.** Do the Chromecast, Google TV and ecobee (`_googlecast._tcp`, `_hap._tcp`) answer queries from an ephemeral port? Scan, then compare the census with `dns-sd -B` or `avahi-browse` on the same LAN. Missing devices confirm.
6. **webOS unsigned manifest.** Patchbay registers without LG's `signed`/`signatures` block (`Ssap.kt:42-55`). Check that each control (volume, input, power off) works. A `401 insufficient permissions` reply in diagnostics confirms.
7. **Bambu H2D:**
   - Developer Mode required, and the access code changes when it is toggled.
   - A partial AMS report may replace whole arrays, because `BambuReport.merge` replaces arrays: watch AMS swatches during a print for slots disappearing.
   - Printer cert stability across a firmware update.
8. **eufy RTSP:**
   - Only some models and only with RTSP enabled.
   - The camera may allow only one RTSP client, so if a NAS/NVR is recording, Patchbay's 15 s DESCRIBE polls or stream may be refused or break the recording. Check the NAS recording while Patchbay is open.
   - The path `/live0` and the Digest/Basic choice (E1.4 covers the path).
9. **Kasa / Tuya payload limits** (F-M5). HS300 strips and vacuums can exceed 4 KiB or 256-character DPs. Pair a strip or the vacuum and look for "protocol violation" in diagnostics.
10. **Android lifecycle** (F-H6). Turn on watching, lock the phone, force-stop from adb (`am kill dev.patchbay`) or wait for an OOM kill, then unlock and open the app. "Encrypted storage unavailable" confirms.

---

## 5. Gaps in HARDWARE_TEST.md and the test suite

**Hardware test additions:**

| ID | Check |
|---|---|
| C1.9 | Re-check the Cast fingerprint after 48 h and after a device reboot |
| N2.9 | Process kill while locked (the adb steps in item 10 above) |
| N2.10 | An automation due 20 min ahead with the phone idle on battery, and again on a charger |
| R1.10 | Windows sleep across an automation time; report "late" vs "skipped" |
| B1.6b | Restore with tray mode on, close to tray, change a scene, reopen. Restored data must survive (F-H5) |
| T1.9 | Every webOS command's reply status recorded in diagnostics |
| U1.9 | Two Patchbay instances (phone + PC) on one Tuya bulb for 10 min |
| E1.10 | The camera with a NAS recording it at the same time |
| X1 | Every Android JCA algorithm used (ChaCha20-Poly1305, PBKDF2WithHmacSHA256, PKCS12, SHA256withRSA, AES/GCM) as a startup self-check whose result appears in diagnostics. Run on the phone |
| G1.14 | Phone on a VPN (or the laptop with a VPN up): scan, and confirm with the VPN provider's or the router's view that no discovery left the LAN |
| — | Renumber the duplicated A1/B1/D1 IDs, fix E1.7, and add "expected key-change" guidance so the owner doesn't learn to accept alerts by reflex |

**Test-suite additions:**
- Bytecode network-reference test (F-H2).
- Throwing-store and throwing-adapter tests for loop survival (F-H4).
- Catch-up and DST tests for `Automations.tick` (F-H3).
- Restore-then-mutate test (F-H5; the reproduction above can be adopted as-is).
- Per-adapter sticky trust-latch test (F-M1).
- Redactor cases (F-M2).
- `assumeTrue` for the interop tests plus a CI interop job (F-M7).
- Stream-level fuzzing of the socket read loops (feed random bytes through `SecureStreams`, `WebSocketClient`, `MqttClient` and the Cast/TV framing via piped streams).
- PIT mutation testing on `core` parsers and `Automations`/`Conditions` once (a CI job, non-blocking).

---

## 6. Prioritised action plan

**Now (before anything else, about 2-3 days):**
1. F-H4 exception boundaries and restartable loops.
2. F-H5 restore "restart required" lock and transactional writes.
3. F-M3 JCA name fallback plus a startup self-check.
4. F-M2 redactor rules.
5. F-M1 sticky trust latch.
6. F-M4 unpair failure surfaced.
7. F-M7 `assumeTrue`.
8. F-H2 short-term script fixes (`KT_MAIN`, the new patterns, self-test plants).

**Before the hardware pass (about 1 week):**
1. F-H3 catch-up scheduling (and decide the Android alarm question in an ADR).
2. F-H6 deferred storage open.
3. F-H1: at least decide the Cast trust model, ideally implement device-auth pinning.
4. F-M5 per-DP tolerance.
5. F-M9 auth-scheme pinning.
6. F-M8 ffmpeg hash pin.
7. Update HARDWARE_TEST per §5.
8. Doc corrections (L12; remove "proven", "stop all contact" and "mutation-tested" until true).

**After the hardware pass:**
1. Bytecode policy check (F-H2 proper).
2. Signature-based dependency verification (F-M10).
3. A CI interop job.
4. Windows runtime smoke test (L13).
5. Fix whatever the devices show. Expect webOS, Bambu AMS, Tuya and mDNS issues.

**Drop or defer:**
- New features: more automation kinds, peer sync, hub, Wear, ESPHome, the vacuum controls.
- Most screenshot and UI polish.
- Re-check that each existing feature is worth maintaining once real usage data exists.

**Home Assistant vs continue: hybrid, decided by evidence, with a default lean.**
- **Keep custom:** NetGuard, storage and backup, app lock, the UI and the "local-only, provably" story. This is the differentiator, and HA can't offer it on a phone without a server.
- **Device layer:** it is where this project is weakest and HA (with thousands of users finding firmware quirks) is strongest: Tuya, Cast, Bambu, webOS. If the owner is willing to run one always-on box anyway (which F-H3 and F-H6 effectively demand for reliable automations), run HA in LAN-only mode on it (no Nabu Casa, firewall it off the WAN). Make Patchbay a privacy-hardened client of HA's local WebSocket API through a pinned NetGuard connection, and keep 2-3 native adapters (Govee, Kasa, HomeKit) for "works without the box".
- **If no always-on box:** continue custom, but cut scope to the devices actually in the house, and treat each adapter's hardware pass as the gate for keeping it.

Run the W5 experiment (HA's integrations for the top 4 devices against these exact checks) before investing in F-H1 and F-H3 beyond the minimum.

Sources: [Open Screen cast_auth_util.cc](https://chromium.googlesource.com/openscreen/+/refs/heads/main/cast/sender/channel/cast_auth_util.cc), [Chromium cast_auth_util.cc](https://chromium.googlesource.com/chromium/src/+/dc1b807d/components/cast_channel/cast_auth_util.cc), [Chromium code review 1011133005](https://codereview.chromium.org/1011133005/patch/1/10001).
