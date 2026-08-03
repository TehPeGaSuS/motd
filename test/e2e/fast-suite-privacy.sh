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
