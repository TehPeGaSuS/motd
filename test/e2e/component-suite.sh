#!/usr/bin/env bash
# Managed-device launcher for every hermetic instrumentation test outside the real-stack suite.
set -euo pipefail

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$E2E_DIR/../.." && pwd)"
REAL_STACK_ANNOTATION=io.github.trevarj.motd.FastHeadlessE2e
# ponytail: one source @Test equals one case; use runner discovery if parameterized tests arrive.
expected_cases="$(
  grep -Rhc --include='*.kt' --exclude='RequiredHeadlessE2eTest.kt' \
    '^[[:space:]]*@Test\b' "$REPO/app/src/androidTest" |
    awk '{ total += $1 } END { print total + 0 }'
)"

cd "$REPO"
# Daemon on; --max-workers=2 because the managed device boots its own emulator.
./gradlew headlessApi34E2eAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.notAnnotation=$REAL_STACK_ANNOTATION" \
  -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect \
  --stacktrace --max-workers=2

count="$(find "$REPO/app/build/outputs/androidTest-results" -type f -name '*.xml' -mmin -15 -print0 2>/dev/null |
  xargs -0 -r grep -ho '<testcase ' | wc -l | tr -d ' ')"
[ "$count" = "$expected_cases" ] || {
  echo "component suite must report exactly $expected_cases source cases; got ${count:-0}" >&2
  exit 1
}
