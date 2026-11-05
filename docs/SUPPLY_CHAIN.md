# Supply chain

## What protects the build

1. **Pinned versions.** `gradle/libs.versions.toml` only; no dynamic versions or ranges.
2. **Content-filtered repositories** (`settings.gradle.kts`). Google's repository may only serve
   `androidx.*`, `com.android.*` and `com.google.*`. Everything else comes from Maven Central (and the
   Gradle Plugin Portal for plugins). Project-level repositories fail the build.
3. **Dependency verification.** `gradle/verification-metadata.xml` holds SHA-256 checksums for every
   artifact and POM. Gradle verifies them strictly on every build, and a mismatch or unknown artifact
   fails the build.
4. **Wrapper.** `gradle-wrapper.properties` pins the distribution SHA-256, and CI runs
   `gradle/actions/wrapper-validation` on the wrapper jar.
5. **CI actions pinned by commit SHA**, with read-only `permissions`.
6. **WiX** is fetched by CI with a pinned SHA-256 (the Compose plugin's own download is disabled).
7. **SBOM.** CycloneDX JSON for the runtime classpaths, uploaded by the `sbom` job.
8. **Reproducibility.** Archive tasks use fixed timestamps and ordering. The APK excludes AGP's encrypted
   dependency-info block. Phase 1 adds a "build twice, compare" CI job.

## Phase 0 status: verification metadata is partial

The Phase 0 sandbox couldn't reach Google Maven (`dl.google.com`, `maven.google.com`). The committed
metadata therefore covers only the JVM-only graph: `core`, `simulators`, `hub`, all Gradle plugins except
AGP, and CycloneDX. The `android`, `screens` and `windows` CI jobs **will fail verification** until it's
bootstrapped:

1. GitHub → Actions → **verification-bootstrap** → Run workflow.
2. Download the `verification-metadata` artifact.
3. Review the diff. Look for unexpected groups, and only `androidx.*`/`com.android.*`/`com.google.*` from
   Google. Then commit it.

The same procedure applies whenever a dependency changes:

```bash
./gradlew --write-verification-metadata sha256 help <the tasks that resolve the new dependency>
git diff gradle/verification-metadata.xml   # review before committing
```

## Adding a dependency (checklist)

- [ ] Is it really needed? Prefer the platform, or ~50 lines of our own code.
- [ ] Maintained (recent release, responsive issues), licence compatible.
- [ ] Inspect for network behaviour (telemetry, update checks, analytics). Must be "No" for runtime deps.
- [ ] Entry in `docs/DEPENDENCIES.md`.
- [ ] Version in the catalog; metadata regenerated and reviewed.
- [ ] `tools/check-policy.sh` still passes (banned-SDK rules).
