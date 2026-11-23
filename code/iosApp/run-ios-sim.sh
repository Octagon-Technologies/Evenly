#!/usr/bin/env bash
#
# Build the Evenly iOS app and run it on this checkout's own iOS Simulator.
#
# This drives `xcodebuild`, whose "Run Script" build phase invokes Gradle to compile the shared
# Kotlin/Native framework first — so this single command builds BOTH the shared module and the
# Swift host, then installs + launches the app.
#
# Used by the Android Studio "iOS App (Simulator)" run configuration, and runnable on its own:
#     ./run-ios-sim.sh                       # build + run on this checkout's simulator
#     ./run-ios-sim.sh shutdown              # shut this checkout's simulator down (frees ~1-2 GB)
#     SIM_UDID=<udid> ./run-ios-sim.sh       # use a specific existing simulator instead
#     SIM_NAME="iPhone 16" ./run-ios-sim.sh  # device model used when this checkout's sim is first created
#     CONFIG=Release ./run-ios-sim.sh        # Release build instead of Debug
#     EVENLY_BUILD_SLOTS=1 ./run-ios-sim.sh  # how many of these builds may run at once machine-wide
#
# One simulator per checkout: several agent sessions run in separate git worktrees, and when they all
# used "whichever simulator is booted" they installed over each other's app, and a launch from one tore
# the Metal surface out from under another mid-draw (a fake SurfaceMetalRedrawer crash). Each checkout
# now gets a simulator named "Evenly-<checkout folder>", created on first use and reused after.
#
# Build slots: each build runs the Kotlin/Native compiler in a multi-GB Gradle daemon. On a 16 GB Mac,
# more than two at once pushes the machine into swap and every build slows down, so builds beyond
# EVENLY_BUILD_SLOTS wait for a free slot. Install/launch run outside the slot.
#
set -euo pipefail

PROJECT="iosApp.xcodeproj"
SCHEME="iosApp"
CONFIG="${CONFIG:-Debug}"
BUNDLE_ID="app.splitevenly"   # PRODUCT_BUNDLE_IDENTIFIER (TEAM_ID empty → no suffix)
SIM_NAME="${SIM_NAME:-iPhone 17 Pro}"
DERIVED="build/dd"
BUILD_SLOTS="${EVENLY_BUILD_SLOTS:-2}"
SLOTS_DIR="$HOME/Library/Caches/app.splitevenly/build-slots"

cd "$(dirname "$0")"

CHECKOUT="$(basename "$(git rev-parse --show-toplevel 2>/dev/null || (cd .. && pwd))")"
SIM_LABEL="Evenly-$CHECKOUT"

# Prints the UDID of the available simulator whose name is exactly $1, or nothing.
udid_for_name() {
  xcrun simctl list devices available | awk -v n="$1 (" '
    { line = $0; sub(/^ +/, "", line)
      if (index(line, n) == 1) { match(line, /[0-9A-Fa-f-]{36}/); print substr(line, RSTART, RLENGTH); exit } }'
}

if [ "${1:-}" = "shutdown" ]; then
  UDID="$(udid_for_name "$SIM_LABEL")"
  [ -n "$UDID" ] || { echo "No simulator named $SIM_LABEL."; exit 0; }
  xcrun simctl shutdown "$UDID" 2>/dev/null || true
  echo "✓ Shut down $SIM_LABEL ($UDID)"
  exit 0
fi

echo "▸ Selecting simulator…"
if [ -n "${SIM_UDID:-}" ]; then
  UDID="$SIM_UDID"
else
  UDID="$(udid_for_name "$SIM_LABEL")"
  if [ -z "$UDID" ]; then
    echo "  Creating $SIM_LABEL ($SIM_NAME, newest installed iOS runtime)"
    UDID="$(xcrun simctl create "$SIM_LABEL" "$SIM_NAME")" \
      || { echo "✗ Could not create a '$SIM_NAME' simulator. Set SIM_NAME to a model from 'xcrun simctl list devicetypes'."; exit 1; }
  fi
fi
echo "  Using $SIM_LABEL ($UDID)"

xcrun simctl boot "$UDID" 2>/dev/null || true   # no-op if already booted
open -a Simulator || true   # headless hosts have no Simulator.app on the path

HELD=""
release_locks() { for d in $HELD; do rm -rf "$d"; done; HELD=""; }
trap release_locks EXIT
# On a signal, stop the build and exit (which runs the EXIT trap). Without the explicit exit, bash resumes
# after the handler; without killing BUILD_PID, an orphaned xcodebuild keeps holding Xcode's build.db lock.
BUILD_PID=""
trap '[ -n "$BUILD_PID" ] && kill "$BUILD_PID" 2>/dev/null; exit 130' INT TERM

# Takes a directory lock (mkdir is atomic). A lock whose owner process is gone is reclaimed first, so a
# killed build never wedges the queue.
take_lock() {
  local pid; pid="$(cat "$1/pid" 2>/dev/null || true)"
  if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then rm -rf "$1"; fi
  mkdir "$1" 2>/dev/null || return 1
  echo $$ > "$1/pid"; echo "$CHECKOUT (pid $$)" > "$1/owner"; HELD="$HELD $1"
}

# Blocks until one of the given lock dirs is taken; $1 is the message shown while waiting.
wait_for_lock() {
  local msg="$1"; shift
  local announced=""
  while :; do
    for d in "$@"; do take_lock "$d" && return; done
    if [ -z "$announced" ]; then
      local owners=""; for d in "$@"; do owners="$owners$(cat "$d/owner" 2>/dev/null || true); "; done
      echo "  $msg (held by: ${owners%; }); waiting…"
      announced=1
    fi
    sleep 5
  done
}

# Two builds in one checkout share build/dd and fail on Xcode's build.db lock, so serialize those first,
# then take one of the machine-wide slots.
mkdir -p "$SLOTS_DIR"
wait_for_lock "Another build is running in this checkout" "$SLOTS_DIR/checkout-$CHECKOUT"
SLOT_DIRS=""; for i in $(seq 1 "$BUILD_SLOTS"); do SLOT_DIRS="$SLOT_DIRS $SLOTS_DIR/slot$i"; done
# shellcheck disable=SC2086  # word-splitting SLOT_DIRS is intended; paths contain no spaces
wait_for_lock "All $BUILD_SLOTS build slots are busy" $SLOT_DIRS
echo "▸ Building $SCHEME ($CONFIG) — also compiles the shared Kotlin framework…"
xcrun xcodebuild \
  -project "$PROJECT" \
  -scheme "$SCHEME" \
  -configuration "$CONFIG" \
  -sdk iphonesimulator \
  -destination "id=$UDID" \
  -derivedDataPath "$DERIVED" \
  -quiet \
  build &
BUILD_PID=$!
wait "$BUILD_PID"   # backgrounded + wait so a signal is handled now, not after the build finishes
BUILD_PID=""
release_locks

APP="$(/usr/bin/find "$DERIVED/Build/Products/$CONFIG-iphonesimulator" -maxdepth 1 -name '*.app' | head -n1)"
[ -n "$APP" ] || { echo "✗ Built .app not found under $DERIVED"; exit 1; }

echo "▸ Installing $(basename "$APP")"
xcrun simctl install "$UDID" "$APP"

echo "▸ Launching $BUNDLE_ID"
# Capture the app's stdout/stderr. A plain `simctl launch` throws both away, and Kotlin/Native prints
# "Uncaught Kotlin exception: …" to stderr before it aborts — without this, a crash leaves only an .ips
# whose backtrace stops at Compose's SurfaceMetalRedrawer.draw, with the actual exception nowhere on disk.
# --console-pty stays attached for the life of the app, so it runs detached and the shell returns.
# Per-checkout file so parallel sessions don't truncate each other's log.
CONSOLE_LOG="${CONSOLE_LOG:-${TMPDIR:-/tmp}/evenly-console-$CHECKOUT.log}"
: > "$CONSOLE_LOG"
nohup xcrun simctl launch --console-pty --terminate-running-process "$UDID" "$BUNDLE_ID" >"$CONSOLE_LOG" 2>&1 &
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
echo "✓ Running on $SIM_LABEL ($UDID)"
echo "  Console (Kotlin exceptions land here): $CONSOLE_LOG"
echo "  Done with it? ./run-ios-sim.sh shutdown frees its memory."
