#!/usr/bin/env bash
# Samples the example app's thread count about every 200 ms until killed:
# all threads, unnamed ones (`Thread-N`, e.g. Nitro's `Promise.parallel`)
# and the viewer's read threads (`BetterPdf-read`).
#   benchmarks/sample-android-threads.sh [package] > threads.csv
pkg="${1:-software.gmi.betterpdf.example}"
echo "epoch_ms,threads,unnamed_threads,read_threads"
while true; do
  pid=$(adb shell pidof "$pkg" 2>/dev/null | tr -d '\r')
  if [ -n "$pid" ]; then
    # Thread names, cut to 15 characters by the kernel.
    names=$(adb shell ps -T -o CMD -p "$pid" 2>/dev/null | tr -d '\r' | tail -n +2)
    total=$(echo "$names" | grep -c .)
    unnamed=$(echo "$names" | grep -c '^Thread-')
    read=$(echo "$names" | grep -c '^BetterPdf-read')
    # macOS `date` has no milliseconds.
    now_ms=$(perl -MTime::HiRes=time -e 'printf "%d", time * 1000')
    echo "$now_ms,$total,$unnamed,$read"
  fi
  sleep 0.2
done
