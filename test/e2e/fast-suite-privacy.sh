#!/usr/bin/env bash
# Privacy contract for artifacts uploaded by the required fast suite.

e2e_audit_required_artifacts() {
  local output_dir="$1" file base
  while IFS= read -r file; do
    base="${file##*/}"
    case "$base" in
      # diagnostics.log is the app's own journal, exported on failure. It is admissible here
      # because DiagnosticLogger records classification, ids, counts and timestamps only, omits
      # known sensitive field names, and the forbidden-field grep below still applies to it.
      summary.json|fixture.jsonl|pretest.json|started.jsonl|failure.json|route.json|semantics.json|lazy-state.json|connections.json|milestones.jsonl|timeline.json|diagnostics.log) ;;
      *) echo "privacy audit rejected unexpected artifact: $base" >&2; return 1 ;;
    esac
  done < <(find "$output_dir" -type f -print)
  ! grep -R -E '"(text|editableText|contentDescription|password|host|nick|channel|fingerprint|message)"' "$output_dir" >/dev/null 2>&1 || {
    echo "privacy audit rejected forbidden diagnostic field" >&2; return 1;
  }
}

# Whether a per-test directory claims to hold a FAILURE capture, as opposed to only green-path
# snapshots.
#
# A per-test directory alone proves nothing about the outcome: the journeys snapshot their timeline
# on the passing path too (see the "pass" outcome in ChatRobots), and that lands under
# `<test>/<label>/pass/timeline.json` — a directory with no top-level file in it at all. Only
# E2eFailureArtifactRule.capture() writes files directly into `<test>/`, and it writes ALL of them
# unconditionally, so any one of them is a truthful claim that a capture was attempted here. Match
# on presence rather than on non-emptiness: a truncated or zero-byte file still claims the capture,
# and the completeness check below is what must then reject it.
e2e_required_capture_claimed() {
  local dir="$1" marker
  for marker in failure.json route.json semantics.json lazy-state.json connections.json \
    milestones.jsonl diagnostics.log; do
    [ -e "$dir/$marker" ] && return 0
  done
  return 1
}

# The completeness contract, deliberately separate from the privacy one above.
#
# The audit only rejects UNEXPECTED files; it never requires expected ones. Combined with collection
# that cannot fail loudly, that made a total collection loss byte-identical to success: the bundle
# uploaded, the audit passed, and the summary reported a real failure class beside an empty
# directory — so a red build cost a cycle just to discover the evidence was missing. Every capture
# that exists here therefore owes the files that make the failure readable.
#
# The scope is the narrow part. A red run is overwhelmingly the NORMAL red run — one journey fails
# and the rest pass — and the passing ones leave green-baseline snapshot directories beside the
# capture. Demanding failure files from those would fire on essentially every red run and overwrite
# the real classification with ARTIFACTS_MISSING, i.e. destroy exactly the signal this check exists
# to protect. So judge only the directories that claim a capture, and count only those toward
# "shipped nothing at all".
#
# Two conditions have to hold before "shipped nothing at all" is a defect rather than a fact.
#
# [attempt_failed] is whether the instrumentation attempt itself failed, as opposed to a post-run
# check like the case-count assertion. [started] is whether any test actually began. A run that died
# BEFORE instrumentation — the APK would not install, the emulator went away, direct mode returned
# early because it found no runner or discovered other than four cases — owes no capture, because no
# test ever ran to write one. Reporting those as ARTIFACTS_MISSING replaces a precise, actionable
# failure class with the one that says "look for evidence that was never owed", which is strictly
# worse than the silence this check was added to fix.
#
# The per-capture completeness loop is NOT gated on either flag: a directory that claims a capture
# has to be readable whatever the launcher thinks happened.
e2e_assert_required_artifacts_collected() {
  local output_dir="$1" attempt_failed="$2" started="$3" test_dir required captures=0 missing=0
  while IFS= read -r test_dir; do
    e2e_required_capture_claimed "$test_dir" || continue
    captures=$((captures + 1))
    for required in diagnostics.log milestones.jsonl failure.json; do
      [ -s "$test_dir/$required" ] && continue
      echo "required artifact missing: ${test_dir##*/}/$required" >&2
      missing=1
    done
  done < <(find "$output_dir/required-e2e" -mindepth 1 -maxdepth 1 -type d 2>/dev/null)
  if [ "$attempt_failed" = true ] && [ "$started" = true ] && [ "$captures" -eq 0 ]; then
    echo "required artifacts missing: the failing run shipped no per-test capture at all" >&2
    missing=1
  fi
  [ "$missing" -eq 0 ]
}
