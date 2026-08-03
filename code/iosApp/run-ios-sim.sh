#!/usr/bin/env bash
#
# Build the Evenly iOS app and run it on an iOS Simulator.
#
# This drives `xcodebuild`, whose "Run Script" build phase invokes Gradle to compile the shared
# Kotlin/Native framework first — so this single command builds BOTH the shared module and the
# Swift host, then installs + launches the app on a booted simulator.
#
# Used by the Android Studio "iOS App (Simulator)" run configuration, and runnable on its own:
#     ./run-ios-sim.sh                       # preferred simulator (see SIM_NAME)
#     SIM_NAME="iPhone 15 Pro" ./run-ios-sim.sh
#
set -euo pipefail

PROJECT="iosApp.xcodeproj"
SCHEME="iosApp"
CONFIG="Debug"
BUNDLE_ID="app.splitevenly"   # PRODUCT_BUNDLE_IDENTIFIER (TEAM_ID empty → no suffix)
SIM_NAME="${SIM_NAME:-iPhone 16}"            # override via env var
DERIVED="build/dd"

cd "$(dirname "$0")"

echo "▸ Selecting simulator…"
# Prefer an already-booted simulator; otherwise the named one; otherwise any available iPhone.
UDID="$(xcrun simctl list devices booted | grep -oE '[0-9A-Fa-f-]{36}' | head -n1 || true)"
if [ -z "$UDID" ]; then
  UDID="$(xcrun simctl list devices available | grep "$SIM_NAME (" | grep -oE '[0-9A-Fa-f-]{36}' | head -n1 || true)"
fi
if [ -z "$UDID" ]; then
  echo "  '$SIM_NAME' not found — falling back to the first available iPhone."
  UDID="$(xcrun simctl list devices available | grep -E '^ +iPhone' | grep -oE '[0-9A-Fa-f-]{36}' | head -n1 || true)"
fi
[ -n "$UDID" ] || { echo "✗ No available iOS simulator. Add one in Xcode › Settings › Platforms."; exit 1; }
echo "  Using $UDID"

xcrun simctl boot "$UDID" 2>/dev/null || true   # no-op if already booted
open -a Simulator

echo "▸ Building $SCHEME ($CONFIG) — also compiles the shared Kotlin framework…"
xcrun xcodebuild \
  -project "$PROJECT" \
  -scheme "$SCHEME" \
  -configuration "$CONFIG" \
  -sdk iphonesimulator \
  -destination "id=$UDID" \
  -derivedDataPath "$DERIVED" \
  -quiet \
  build

APP="$(/usr/bin/find "$DERIVED/Build/Products/$CONFIG-iphonesimulator" -maxdepth 1 -name '*.app' | head -n1)"
[ -n "$APP" ] || { echo "✗ Built .app not found under $DERIVED"; exit 1; }

echo "▸ Installing $(basename "$APP")"
xcrun simctl install "$UDID" "$APP"

echo "▸ Launching $BUNDLE_ID"
# Capture the app's stdout/stderr. A plain `simctl launch` throws both away, and Kotlin/Native prints
# "Uncaught Kotlin exception: …" to stderr before it aborts — without this, a crash leaves only an .ips
# whose backtrace stops at Compose's SurfaceMetalRedrawer.draw, with the actual exception nowhere on disk.
# --console-pty stays attached for the life of the app, so it runs detached and the shell returns.
CONSOLE_LOG="${CONSOLE_LOG:-${TMPDIR:-/tmp}/evenly-console.log}"
: > "$CONSOLE_LOG"
nohup xcrun simctl launch --console-pty "$UDID" "$BUNDLE_ID" >"$CONSOLE_LOG" 2>&1 &
# Wait for the app to register with launchd before claiming success. Poll rather than sleep-once: under
# --console-pty it routinely takes >2s to appear, and a single early sample reports a false failure.
UP=""
for _ in $(seq 1 15); do
  if xcrun simctl spawn "$UDID" launchctl list 2>/dev/null | grep -q "UIKitApplication:$BUNDLE_ID"; then
    UP=1; break
  fi
  sleep 1
done
if [ -z "$UP" ]; then
  echo "✗ App did not stay up. Console output:"; cat "$CONSOLE_LOG"; exit 1
fi
echo "✓ Running on simulator $UDID"
echo "  Console (Kotlin exceptions land here): $CONSOLE_LOG"
