#!/usr/bin/env bash
# Main-thread cost of the iOS viewer's form work on the simulator; see README.md.
#   benchmarks/ios-forms/run.sh <simulator-udid> [fixture ...]
#   REV=<commit> benchmarks/ios-forms/run.sh …   # the form sources as of <commit>
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
sim="${1:?usage: run.sh <simulator-udid> [fixture ...]}"; shift
fixtures=("$@")
[ $# -eq 0 ] && fixtures=(forms-long forms forms-encrypted forms-compressed forms-boxes)

# Fixture: password (- for none), the field edited first, the one edited last.
spec() {
  case "$1" in
    forms-long) echo "- name p1000.line4" ;;
    forms | forms-compressed) echo "- name date" ;;
    forms-encrypted) echo "secret name date" ;;
    forms-boxes) echo "- cropped rotated" ;;
    *) echo "Unknown fixture $1" >&2; return 1 ;;
  esac
}

# simctl-spawned processes hang opening files under ~/Desktop.
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
for fixture in "${fixtures[@]}"; do
  spec "$fixture" > /dev/null || exit 1
  if [ ! -f "$root/benchmarks/fixtures/$fixture.pdf" ]; then
    echo "Missing benchmarks/fixtures/$fixture.pdf; generate it with: node benchmarks/generate-fixtures.mjs $fixture" >&2
    exit 1
  fi
  cp "$root/benchmarks/fixtures/$fixture.pdf" "$work/"
done

sources=(
  DocumentForm.swift DocumentForm+setValues.swift FormSnapshot.swift FormWidgetAnnotation.swift
  FormWidgetAnnotation+formField.swift ColorSchemeDocument.swift ColorSchemePage.swift
  ColorSchemeAnnotation.swift PDFPage+topLeftPoint.swift PDFDocument+protectedWriteOptions.swift
)
mkdir "$work/src"
for file in "${sources[@]}"; do
  if [ -n "${REV:-}" ]; then
    git -C "$root" show "$REV:package/ios/$file" > "$work/src/$file" || exit 1
  else
    cp "$root/package/ios/$file" "$work/src/$file"
  fi
  # The Nitro types are stand-ins in main.swift.
  sed -i '' '/^import NitroModules$/d' "$work/src/$file"
done
# Before #28 every widget was copied and the form had no record of changes.
flags=()
grep -q "init?(changesOf" "$work/src/FormSnapshot.swift" && flags+=(-D CHANGES_SNAPSHOT)
if ! xcrun --sdk iphonesimulator swiftc -O ${flags[@]+"${flags[@]}"} -target "$(uname -m)-apple-ios15.1-simulator" \
  "$work"/src/*.swift "$here/main.swift" -o "$work/bench" > "$work/build.log" 2>&1; then
  cat "$work/build.log" >&2
  exit 1
fi
xcrun simctl bootstatus "$sim" -b > /dev/null || exit 1

mkdir -p "$root/benchmarks/results"
out="$root/benchmarks/results/ios-forms-$(date +%Y%m%dT%H%M%S).txt"
echo "form sources: ${REV:-working tree}" | tee "$out"
failed=0
for fixture in "${fixtures[@]}"; do
  echo "== $fixture" | tee -a "$out"
  # shellcheck disable=SC2046
  xcrun simctl spawn "$sim" "$work/bench" "$work/$fixture.pdf" $(spec "$fixture") > "$work/run.txt" 2>&1 &
  pid=$!
  # A stuck spawn never ends.
  for _ in $(seq 1 600); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid"
    echo "timed out after 10 min" | tee -a "$out"
    failed=1
    continue
  fi
  wait "$pid"
  status=$?
  tee -a "$out" < "$work/run.txt"
  [ "$status" -eq 0 ] || failed=1
done
echo "Details: ${out#"$root/"}"
exit $failed
