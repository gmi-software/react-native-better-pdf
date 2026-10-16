#!/usr/bin/env bash
# Search highlight cost of the iOS viewer on the simulator; see README.md.
#   benchmarks/ios-search-highlights/run.sh <simulator-udid> [query ...]
#   REV=<commit> benchmarks/ios-search-highlights/run.sh …   # PdfSearchController.swift as of <commit>
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
sim="${1:?usage: run.sh <simulator-udid> [query ...]}"; shift
queries=("$@")
[ $# -eq 0 ] && queries=(or e)

fixture="$root/benchmarks/fixtures/outline-large.pdf"
if [ ! -f "$fixture" ]; then
  echo "Missing $fixture; generate it with: node benchmarks/generate-fixtures.mjs outline-large" >&2
  exit 1
fi
# simctl-spawned processes hang opening files under ~/Desktop.
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cp "$fixture" "$work/"

controller="$root/package/ios/PdfSearchController.swift"
if [ -n "${REV:-}" ]; then
  controller="$work/PdfSearchController.swift"
  git -C "$root" show "$REV:package/ios/PdfSearchController.swift" > "$controller" || exit 1
fi
# Controllers before #29 have no viewport hook.
hook=""
grep -q "func viewportDidChange" "$controller" && hook="-D VIEWPORT_HOOK"
if ! xcrun --sdk iphonesimulator swiftc -O $hook -target "$(uname -m)-apple-ios15.1-simulator" \
  "$controller" "$root/package/ios/UIView+firstDescendantScrollView.swift" "$here/main.swift" \
  -o "$work/bench" > "$work/build.log" 2>&1; then
  cat "$work/build.log" >&2
  exit 1
fi
xcrun simctl bootstatus "$sim" -b > /dev/null || exit 1

mkdir -p "$root/benchmarks/results"
out="$root/benchmarks/results/ios-search-highlights-$(date +%Y%m%dT%H%M%S).txt"
echo "controller: ${REV:-working tree}" > "$out"
echo "| Query | Matches | Colour change (ms) | Next / previous (ms) | Longest stall while searching (ms) | Stalls over 16 ms | Search (s) | Drag mean / longest step (ms) | Jump (ms) | Highlights |"
echo "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |"
failed=0
for query in "${queries[@]}"; do
  echo "== $query" >> "$out"
  xcrun simctl spawn "$sim" "$work/bench" "$work/outline-large.pdf" "$query" > "$work/run.txt" 2>&1 &
  pid=$!
  # A stuck spawn never ends.
  for _ in $(seq 1 900); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid"
    echo "| $query | timed out after 15 min |"
    failed=1
    continue
  fi
  wait "$pid"
  status=$?
  cat "$work/run.txt" >> "$out"
  grep '^| ' "$work/run.txt" || echo "| $query | no result, exit $status |"
  [ "$status" -eq 0 ] || failed=1
done
echo "Details: ${out#"$root/"}"
exit $failed
