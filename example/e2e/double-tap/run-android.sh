#!/usr/bin/env bash
# enableDoubleTapZoom on Android. Maestro's double tap leaves ~17 ms between
# the taps, below GestureDetector's 40 ms DOUBLE_TAP_MIN_TIME, so adb sends
# the double taps (120 ms apart) and Maestro checks the result.
#   example/e2e/double-tap/run-android.sh <device-id>
set -euo pipefail
cd "$(dirname "$0")"
device="$1"
out="${MAESTRO_OUT:-/tmp/better-pdf-e2e}"
double_tap() {
  local size width height
  size=$(adb -s "$device" shell wm size | awk '/Physical/ {print $3}')
  width=${size%x*}
  height=${size#*x}
  local x=$((width / 2)) y=$((height * 55 / 100))
  adb -s "$device" shell "input tap $x $y; sleep 0.12; input tap $x $y"
}
step() {
  if ! maestro --device "$device" test "$1" --test-output-dir "$out"; then
    echo "FAILED at $1"
    exit 1
  fi
}
step open.yaml
double_tap # zoom on (default): zooms
step assert-zoomed.yaml
double_tap # zoom off: two page taps, no zoom
step assert-not-zoomed.yaml
step open-min-scale.yaml
double_tap # minScale={0.5} at 1×: zooms in
step assert-min-scale-zoomed-in.yaml
echo "PASS  double-tap (android)"
