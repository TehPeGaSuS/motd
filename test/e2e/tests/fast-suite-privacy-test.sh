#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
. "$ROOT/test/e2e/fast-suite-privacy.sh"

scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
mkdir -p "$scratch/required-e2e"
printf '{"phase":"launcher_started"}\n' >"$scratch/fixture.jsonl"
printf '{"test":"RequiredHeadlessE2eTest_send"}\n' >"$scratch/required-e2e/started.jsonl"
e2e_audit_required_artifacts "$scratch"
# Journey-owned timeline snapshots live under per-label/per-outcome directories, so the audit has
# to accept the allowlisted name at any depth and still judge its contents.
snapshot="$scratch/required-e2e/RequiredHeadlessE2eTest_send/newest_row/timeout"
mkdir -p "$snapshot"
printf '{"schema":"timeline-newest-row/1","target":{"tag":"chat_message_abc","pagingKey":119}}\n' \
  >"$snapshot/timeline.json"
e2e_audit_required_artifacts "$scratch"
printf '{"message":"sentinel"}\n' >"$snapshot/timeline.json"
if e2e_audit_required_artifacts "$scratch" >/dev/null 2>&1; then
  echo "privacy audit accepted message sentinel inside a timeline snapshot" >&2
  exit 1
fi
printf '{"schema":"timeline-newest-row/1"}\n' >"$snapshot/timeline.json"
printf '{"message":"sentinel"}\n' >"$scratch/required-e2e/semantics.json"
if e2e_audit_required_artifacts "$scratch" >/dev/null 2>&1; then
  echo "privacy audit accepted message sentinel" >&2
  exit 1
fi

# --- completeness contract -------------------------------------------------------------------
#
# Its whole value is that it fires ONLY on a bundle a human cannot read. A check that also fires on
# the ordinary red run — one journey failing beside three passing ones — would replace the real
# failure class with ARTIFACTS_MISSING every time, so the pass-snapshot cases below are load-bearing
# rather than decoration.

# Writes the files E2eFailureArtifactRule.capture() writes, minus the named omissions.
seed_capture() {
  local dir="$1" omit="${2:-}" name
  mkdir -p "$dir"
  for name in failure.json route.json semantics.json lazy-state.json connections.json \
    milestones.jsonl diagnostics.log; do
    [ "$name" = "$omit" ] && continue
    printf '{"test":"seed"}\n' >"$dir/$name"
  done
}

# Writes the green-path timeline snapshot a PASSING journey leaves behind: subdirectories only.
seed_pass_snapshot() {
  mkdir -p "$1/newest_row/pass"
  printf '{"schema":"timeline-newest-row/1"}\n' >"$1/newest_row/pass/timeline.json"
}

complete="$(mktemp -d)"
trap 'rm -rf "$scratch" "$complete"' EXIT
seed_capture "$complete/required-e2e/RequiredHeadlessE2eTest_unread"
seed_pass_snapshot "$complete/required-e2e/RequiredHeadlessE2eTest_unread"
seed_pass_snapshot "$complete/required-e2e/RequiredHeadlessE2eTest_send"
e2e_assert_required_artifacts_collected "$complete" true true

# A capture that lost the app journal is unreadable, and that is the case worth a distinct class.
rm "$complete/required-e2e/RequiredHeadlessE2eTest_unread/diagnostics.log"
if e2e_assert_required_artifacts_collected "$complete" true true >/dev/null 2>&1; then
  echo "completeness check accepted a capture with no diagnostics.log" >&2
  exit 1
fi
# Present but empty is the same loss: the pull produced a file and no content.
: >"$complete/required-e2e/RequiredHeadlessE2eTest_unread/diagnostics.log"
if e2e_assert_required_artifacts_collected "$complete" true true >/dev/null 2>&1; then
  echo "completeness check accepted an empty diagnostics.log" >&2
  exit 1
fi

# A failing attempt whose only surviving directories are green snapshots shipped no capture at all —
# the exact total-collection-loss shape this contract exists to name.
snapshots_only="$(mktemp -d)"
trap 'rm -rf "$scratch" "$complete" "$snapshots_only"' EXIT
seed_pass_snapshot "$snapshots_only/required-e2e/RequiredHeadlessE2eTest_unread"
seed_pass_snapshot "$snapshots_only/required-e2e/RequiredHeadlessE2eTest_send"
if e2e_assert_required_artifacts_collected "$snapshots_only" true true >/dev/null 2>&1; then
  echo "completeness check accepted a failing run with no per-test capture" >&2
  exit 1
fi
# The same tree is the NORMAL shape of a green run, where nothing is owed.
e2e_assert_required_artifacts_collected "$snapshots_only" false true

# A run that never started instrumentation owes NOTHING, and this is the case the check got wrong:
# the APK would not install, the emulator went away, or direct mode returned early on a missing
# runner or a case count other than four. The attempt failed and there is no capture, but no test
# ever ran to write one, so calling it ARTIFACTS_MISSING would overwrite a precise failure class
# with a hunt for evidence that was never owed.
never_started="$(mktemp -d)"
trap 'rm -rf "$scratch" "$complete" "$snapshots_only" "$never_started"' EXIT
mkdir -p "$never_started/required-e2e"
e2e_assert_required_artifacts_collected "$never_started" true false
# ...and the launcher never even gets a required-e2e directory in that case.
rmdir "$never_started/required-e2e"
e2e_assert_required_artifacts_collected "$never_started" true false
# Not started is not a blanket pass, though: a capture that DID land still has to be readable, so
# the per-capture contract is deliberately not gated on either flag.
seed_capture "$never_started/required-e2e/RequiredHeadlessE2eTest_unread" diagnostics.log
if e2e_assert_required_artifacts_collected "$never_started" true false >/dev/null 2>&1; then
  echo "completeness check accepted an unreadable capture from a run that never started" >&2
  exit 1
fi
