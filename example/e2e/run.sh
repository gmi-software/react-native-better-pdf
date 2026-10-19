#!/usr/bin/env bash
# Runs Maestro flows against a booted simulator/emulator and reports pass/fail.
#   example/e2e/run.sh <device-id> [flow ...]
# Prerequisites: app installed, Metro running (debug) and
# `node benchmarks/serve-fixtures.mjs` running (Android: adb reverse tcp:8090 tcp:8090).
# The default flows need Android's newer renderer (Android 15, or 12–14 with
# SDK extension 13); without it, pass android-legacy/* and viewer-links.
set -uo pipefail
cd "$(dirname "$0")"
device="$1"; shift
flows=("$@")
if [ ${#flows[@]} -eq 0 ]; then
  flows=(viewer-bundled viewer-scroll-zoom viewer-errors viewer-password viewer-layout-modes viewer-fit-policy viewer-single-page viewer-rtl viewer-outline viewer-scroll-enabled viewer-links viewer-double-tap viewer-scale-change viewer-initial viewer-text-selection viewer-accessibility viewer-search viewer-viewport viewer-page-sizes viewer-page-image viewer-offline viewer-short-document viewer-print viewer-spreads viewer-dark viewer-overlays viewer-forms viewer-inset viewer-inset-last-page viewer-base64 viewer-sources pdf-document)
fi
failed=0
is_android=0
adb -s "$device" get-state > /dev/null 2>&1 && is_android=1
# Android 14+ keyboards open a "Try out your stylus" sheet on the first text
# input, which swallows Maestro's typing; the example needs no handwriting.
[ "$is_android" = 1 ] && adb -s "$device" shell settings put secure stylus_handwriting_enabled 0 > /dev/null 2>&1
for flow in "${flows[@]}"; do
  # Flows in subdirectories (android-legacy/…) log beside the others.
  log="/tmp/better-pdf-e2e-${flow//\//-}.log"
  # Maestro cannot produce an Android double tap; see double-tap/run-android.sh.
  if [ "$flow" = viewer-double-tap ] && [ "$is_android" = 1 ]; then
    if ./double-tap/run-android.sh "$device" > "$log" 2>&1; then
      echo "PASS  $flow (adb)"
    else
      echo "FAIL  $flow (adb)  (log: $log)"
      failed=1
    fi
    continue
  fi
  if maestro --device "$device" test "$flow.yaml" --test-output-dir "${MAESTRO_OUT:-/tmp/better-pdf-e2e}" > "$log" 2>&1; then
    echo "PASS  $flow"
  else
    echo "FAIL  $flow  (log: $log)"
    grep -E "FAILED|not found|Assertion" "$log" | head -3 | sed 's/^/      /'
    failed=1
  fi
done
exit $failed
