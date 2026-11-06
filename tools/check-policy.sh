#!/usr/bin/env bash
# Patchbay repository policy checks (privacy invariants 1, 2, 5, 8, 9). Run by CI and `./gradlew check`.
#
#   tools/check-policy.sh              check the working tree
#   tools/check-policy.sh --self-test  prove every rule fires on a planted violation
#
# Rules are deliberately simple greps. False positives are fixed by moving code into the
# allowed location (e.g. NetGuard), not by weakening a pattern. Changing a rule needs an ADR.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Code allowed to touch raw networking: NetGuard itself, and the simulators (loopback-only fakes).
# ADR 0008: one Android file may hand the player a NetGuard-backed SocketFactory; it opens no sockets itself.
NET_ALLOWED='^(core/src/[^/]+/kotlin/dev/patchbay/core/net/|simulators/src/|app-android/src/main/kotlin/dev/patchbay/android/GuardedPlayer\.kt$)'
# The only place a custom X509TrustManager may exist: the pinned-TOFU implementation.
TLS_ALLOWED='^core/src/[^/]+/kotlin/dev/patchbay/core/net/tls/'

failures=0
fail() { echo "POLICY VIOLATION [$1]: $2" >&2; failures=$((failures + 1)); }

list_files() { # $1 = root dir; prints repo-relative paths of tracked + untracked-not-ignored files
  (cd "$1" && git ls-files -co --exclude-standard 2>/dev/null || find . -type f | sed 's#^\./##')
}

scan() { # $1 rule id, $2 description, $3 ERE pattern, $4 file filter ERE, $5 allowed-path ERE (may be empty)
  local id="$1" desc="$2" pattern="$3" filter="$4" allowed="$5"
  while IFS= read -r f; do
    [[ "$f" =~ $filter ]] || continue
    [[ -n "$allowed" && "$f" =~ $allowed ]] && continue
    [[ -f "$BASE/$f" ]] || continue
    # Ignore comment-only lines so docs can mention the patterns.
    if grep -nE "$pattern" "$BASE/$f" | grep -vE '^[0-9]+:\s*(//|\*|/\*|#)' >/tmp/pb_policy_hits.$$ 2>/dev/null; then
      while IFS= read -r hit; do fail "$id" "$desc — $f:$hit"; done </tmp/pb_policy_hits.$$
    fi
  done < <(list_files "$BASE")
  rm -f /tmp/pb_policy_hits.$$
}

run_rules() {
  BASE="$1"
  local KT_MAIN='^[^/]+/src/[A-Za-z]*[Mm]ain/.*\.kt$'
  local KT_ANY='\.(kt|java)$'
  local BUILD='(\.gradle\.kts|\.gradle|libs\.versions\.toml)$'

  # 1. NetGuard is the only door to the network.
  scan NET001 "raw socket/HTTP outside NetGuard" \
    '\b(Socket|ServerSocket|DatagramSocket|MulticastSocket|SocketChannel|DatagramChannel|AsynchronousSocketChannel|SSLSocket|SSLServerSocket)\s*[\.(]|java\.net\.http|HttpURLConnection|HttpsURLConnection|\.openConnection\(|\.openStream\(|okhttp3|io\.ktor\.client|io\.ktor\.network|SocketFactory|InetAddress\.(getByName|getAllByName)|NsdManager|WebView' \
    "$KT_ANY" "$NET_ALLOWED"

  # 2. No trust-all TLS, no hostname-verification bypass.
  scan TLS001 "trust-all / hostname bypass pattern" \
    'TrustAll|trustAll|InsecureTrustManager|ALLOW_ALL_HOSTNAME_VERIFIER|NoopHostnameVerifier|AllowAllHostnameVerifier|HostnameVerifier\s*\{\s*_\s*,\s*_\s*->\s*true|hostnameVerifier\s*\{\s*_\s*,\s*_\s*->\s*true|setDefaultHostnameVerifier|setDefaultSSLSocketFactory' \
    "$KT_ANY" ""
  scan TLS002 "custom X509TrustManager outside the pinned-TOFU package" \
    'X509TrustManager|X509ExtendedTrustManager|TrustManagerFactory' \
    "$KT_ANY" "$TLS_ALLOWED"

  # 3. No data-transmitting SDKs.
  scan SDK001 "banned SDK dependency" \
    'firebase|com\.google\.android\.gms|play-services|com\.google\.android\.play|crashlytics|io\.sentry|sentry-|mixpanel|amplitude|segment\.analytics|appcenter|bugsnag|datadog|newrelic|onesignal|admob|flurry|instabug|google-analytics|app-update' \
    "$BUILD" ""
  scan SDK002 "banned SDK import" \
    'import (com\.google\.firebase|com\.google\.android\.gms|com\.google\.android\.play|io\.sentry|com\.crashlytics)' \
    "$KT_ANY" ""

  # 4. No internet URLs in shipped code (fonts/CDN/update checks). Docs and build files may link.
  scan URL001 "internet URL literal in shipped code" \
    '"https?://' \
    "$KT_MAIN" ""

  # 5. Android manifest hygiene.
  scan MAN001 "forbidden manifest attribute" \
    'android:debuggable="true"|usesCleartextTraffic="true"|allowBackup="true"|cleartextTrafficPermitted="true"' \
    'AndroidManifest\.xml$|network_security_config\.xml$' ""
  scan MAN002 "location or other unexpected permission requested" \
    'uses-permission android:name="android\.permission\.(ACCESS_FINE_LOCATION|ACCESS_COARSE_LOCATION|ACCESS_BACKGROUND_LOCATION|READ_CONTACTS|RECORD_AUDIO|CAMERA|READ_PHONE_STATE|QUERY_ALL_PACKAGES)"\s*/>' \
    'AndroidManifest\.xml$' ""
}

self_test() {
  local tmp; tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  (cd "$tmp" && git init -q .)
  mkdir -p "$tmp/core/src/jvmMain/kotlin/dev/patchbay/core/adapter" "$tmp/app-android/src/main" "$tmp/core/src/jvmMain/kotlin/dev/patchbay/core/net"
  cat >"$tmp/core/src/jvmMain/kotlin/dev/patchbay/core/adapter/Bad.kt" <<'EOF'
val s = java.net.Socket("8.8.8.8", 53)
val u = java.net.URL("https://example.com").openConnection()
val v = HostnameVerifier { _, _ -> true }
object TrustAllCerts : X509TrustManager
EOF
  # Allowed location: NetGuard may use sockets (NET001 must NOT fire here) but not trust-all.
  cat >"$tmp/core/src/jvmMain/kotlin/dev/patchbay/core/net/Ok.kt" <<'EOF'
val s = java.net.Socket()
// A comment mentioning TrustAll is fine.
EOF
  printf 'implementation("com.google.firebase:firebase-analytics:1.0")\n' >"$tmp/build.gradle.kts"
  printf '<application android:allowBackup="true">\n<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />\n' >"$tmp/app-android/src/main/AndroidManifest.xml"

  failures=0
  local out; out="$(run_rules "$tmp" 2>&1 || true)"
  local expect=(NET001 TLS001 TLS002 SDK001 URL001 MAN001 MAN002)
  local ok=1
  for id in "${expect[@]}"; do
    if ! grep -q "\[$id\]" <<<"$out"; then echo "self-test: rule $id did not fire" >&2; ok=0; fi
  done
  if grep -q 'core/net/Ok.kt.*NET001' <<<"$out"; then echo "self-test: NET001 fired inside NetGuard package" >&2; ok=0; fi
  if grep -q 'Ok.kt:2' <<<"$out"; then echo "self-test: comment line was flagged" >&2; ok=0; fi
  failures=0
  if [[ $ok -eq 1 ]]; then echo "policy self-test: all ${#expect[@]} rules fire; allowlists and comment handling hold"; return 0; fi
  return 1
}

if [[ "${1:-}" == "--self-test" ]]; then
  self_test
  exit $?
fi

run_rules "$ROOT"
if [[ $failures -gt 0 ]]; then
  echo "policy check failed with $failures violation(s). See CLAUDE.md privacy invariants." >&2
  exit 1
fi
echo "policy check: OK"
