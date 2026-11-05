# Installing and signing builds

CI (`ci.yml`) produces, per run:

- `patchbay-android` artifact:
  - `app-android-debug.apk` — debug-signed, for quick testing.
  - `app-android-release-unsigned.apk` — R8-minified release, **unsigned**.
  - R8 mapping file and Lint report.
- `patchbay-windows-msi` artifact: `Patchbay-0.1.0.msi` (per-user, no auto-updater).
- `sbom` artifact: CycloneDX JSON.
- `design-screens` artifact: screenshot renders.

## Sign the Android release with your own key (once)

Keys never go into the repo or CI in Phase 0. On your own machine:

```bash
keytool -genkeypair -v -keystore ~/patchbay-release.jks -alias patchbay -keyalg EC -groupname secp256r1 -validity 10000
```

```bash
zipalign -p -f 4 app-android-release-unsigned.apk app-android-release-aligned.apk
```

```bash
apksigner sign --ks ~/patchbay-release.jks --ks-key-alias patchbay --out patchbay.apk app-android-release-aligned.apk
```

```bash
apksigner verify --print-certs patchbay.apk
```

Install with `adb install patchbay.apk`. On GrapheneOS you can also allow installs from your file manager.
Keep the keystore safe: Android refuses updates signed with a different key.

## Windows

Run the MSI. It installs per-user (no admin prompt) to `%LOCALAPPDATA%\Patchbay`. There's no updater; install
new versions over the top (the stable `upgradeUuid` replaces the old install).

The MSI isn't code-signed in Phase 0, so SmartScreen will warn. Signing with your own certificate is
optional, and a Phase 1 question.

Optional defence in depth on top of NetGuard (elevated PowerShell). Block rules override Allow rules in
Windows Firewall, so use two Block rules rather than an Allow + Block pair. The first stops Patchbay
reaching the internet (the `Internet` keyword means every address outside your local subnet and
intranet):

```powershell
New-NetFirewallRule -DisplayName "Patchbay: no internet" -Direction Outbound -Program "$env:LOCALAPPDATA\Patchbay\Patchbay.exe" -RemoteAddress Internet -Action Block
```

The second blocks Patchbay entirely on networks marked Public (cafés, hotels):

```powershell
New-NetFirewallRule -DisplayName "Patchbay: nothing on public networks" -Direction Outbound -Program "$env:LOCALAPPDATA\Patchbay\Patchbay.exe" -Profile Public -Action Block
```

These haven't been tested against a real install yet (no hardware in Phase 0). Phase 1 `HARDWARE_TEST.md`
includes a check, and the bundled JRE's executable path may need adjusting.
