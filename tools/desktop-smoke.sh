#!/usr/bin/env bash
# Starts the real desktop app (the Windows app's entry point, on Linux with its file-based key fallback) under a virtual display and checks that it comes up:
# Bootstrap finishes and logs "ready", a window appears and draws something, nothing logs an error, and the process is still alive after a few seconds. It then stops the app
# and checks that it shut down cleanly. Writes the session log, the Gradle output and a screenshot to the output folder.
#
# Run it inside a virtual display:  xvfb-run -a -s "-screen 0 1280x800x24" tools/desktop-smoke.sh [output folder]
# Needs: Xvfb, ImageMagick (import, identify) and x11-utils (xwininfo). The first run compiles the app, which takes a few minutes.
set -u
OUT="${1:-desktop-smoke-out}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
DATA="$(mktemp -d)"
export LOCALAPPDATA="$DATA"            # the app keeps its data in %LOCALAPPDATA%\Patchbay; a fresh folder means a first start (the JVM would ignore HOME)
failures=0
fail() { echo "FAIL: $*"; failures=$((failures + 1)); }
pass() { echo "PASS: $*"; }

./gradlew --console=plain :app-windows:run > "$OUT/gradle.log" 2>&1 &
GRADLE_PID=$!

log_file() { find "$DATA" -name 'patchbay_windows_*.log' 2>/dev/null | head -1; }

# 1. Bootstrap finishes (the build and the start-up together may take several minutes on a cold runner).
ready=0
for _ in $(seq 1 480); do
  f="$(log_file)"
  if [ -n "$f" ] && grep -q " boot *: ready:" "$f" 2>/dev/null; then ready=1; break; fi
  if ! kill -0 "$GRADLE_PID" 2>/dev/null; then break; fi
  sleep 2
done
if [ "$ready" = 1 ]; then pass "bootstrap finished ($(grep -h ' boot *: ready:' "$(log_file)" | head -1 | sed 's/^[^ ]* *//'))"; else fail "bootstrap did not finish"; fi

# 2. A window appears.
window=0
for _ in $(seq 1 30); do
  if xwininfo -root -tree 2>/dev/null | grep -qi "patchbay"; then window=1; break; fi
  sleep 1
done
xwininfo -root -tree > "$OUT/windows.txt" 2>&1 || true
if [ "$window" = 1 ]; then pass "a Patchbay window exists"; else fail "no Patchbay window appeared (see windows.txt)"; fi

# 3. Let it draw and run for a while, then look at it.
sleep 12
import -window root "$OUT/screen.png" 2>> "$OUT/gradle.log" || fail "could not take a screenshot"
if [ -s "$OUT/screen.png" ]; then
  spread="$(identify -format '%[fx:standard_deviation*1000]' "$OUT/screen.png" 2>/dev/null | cut -d. -f1)"
  if [ "${spread:-0}" -ge 5 ]; then pass "the screen shows something (contrast $spread)"; else fail "the screen is blank (contrast ${spread:-0})"; fi
fi

# 4. Still alive, and the log has no errors.
if pgrep -f "dev.patchbay.windows.MainKt" > /dev/null; then pass "the app is still running"; else fail "the app is not running"; fi
f="$(log_file)"
[ -n "$f" ] && cp "$f" "$OUT/session.log"
if [ -n "$f" ] && grep -E "^[0-9TZ:.+-]+ +(ERROR|WARN) " "$f" > "$OUT/problems.txt" 2>/dev/null && [ -s "$OUT/problems.txt" ]; then
  if grep -q " ERROR " "$OUT/problems.txt"; then fail "the log has errors:"; else echo "NOTE: the log has warnings:"; fi
  sed 's/^/    /' "$OUT/problems.txt" | head -20
else
  pass "no errors or warnings in the log"
fi

# 5. Stop it and see that it shuts down by itself.
pkill -TERM -f "dev.patchbay.windows.MainKt" 2>/dev/null
for _ in $(seq 1 20); do pgrep -f "dev.patchbay.windows.MainKt" > /dev/null || break; sleep 1; done
if pgrep -f "dev.patchbay.windows.MainKt" > /dev/null; then fail "the app did not stop when asked"; pkill -KILL -f "dev.patchbay.windows.MainKt"; else pass "the app stopped when asked"; fi
kill "$GRADLE_PID" 2>/dev/null
f="$(log_file)"
[ -n "$f" ] && cp "$f" "$OUT/session.log"
[ -n "$f" ] && grep -q " boot *: shutdown" "$f" && pass "the shutdown was logged" || echo "NOTE: no shutdown line in the log"

echo "---- session log ----"
[ -f "$OUT/session.log" ] && sed 's/^/    /' "$OUT/session.log" | head -60
echo "---- gradle output (tail) ----"
tail -70 "$OUT/gradle.log" | sed 's/^/    /'
if [ "$failures" -gt 0 ]; then echo "desktop smoke test: $failures failure(s)"; exit 1; fi
echo "desktop smoke test: OK"
