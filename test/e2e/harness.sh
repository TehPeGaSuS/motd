#!/usr/bin/env bash
# Shared lifecycle and failure-artifact helpers for connected and managed-device E2E runners.

E2E_HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
E2E_HERMETIC_COMPOSE="$E2E_HARNESS_DIR/hermetic/docker-compose.yml"

e2e_adb() {
  if [ -n "${SERIAL:-}" ]; then adb -s "$SERIAL" "$@"; else adb "$@"; fi
}

e2e_capture_device_artifacts() {
  local output_dir="$1"
  mkdir -p "$output_dir"
  e2e_adb exec-out screencap -p >"$output_dir/screenshot.png" 2>/dev/null || true
  e2e_adb logcat -d -v threadtime >"$output_dir/logcat.txt" 2>/dev/null || true
}

e2e_capture_hermetic_artifacts() {
  local output_dir="$1"
  mkdir -p "$output_dir"
  docker compose -f "$E2E_HERMETIC_COMPOSE" logs --no-color \
    >"$output_dir/stack-services.log" 2>&1 || true
  docker compose -f "$E2E_HERMETIC_COMPOSE" ps --all \
    >"$output_dir/stack-status.txt" 2>&1 || true
  docker compose -f "$E2E_HERMETIC_COMPOSE" config --images \
    >"$output_dir/stack-images.txt" 2>&1 || true
}

e2e_capture_native_stack_artifacts() {
  local output_dir="$1" stack_dir="$2"
  mkdir -p "$output_dir"
  cp "$stack_dir/soju.log" "$output_dir/soju.log" 2>/dev/null || true
  cp "$stack_dir/ergo.log" "$output_dir/ergo.log" 2>/dev/null || true
  {
    if command -v soju >/dev/null 2>&1; then soju -version 2>&1 || true; fi
    if command -v ergo >/dev/null 2>&1; then ergo version 2>&1 || true; fi
  } >"$output_dir/stack-versions.txt"
}

# Does a required-e2e directory exist on the device under [parent]?
#
# Asked through an echoed token rather than the remote exit status, because `adb exec-out`'s
# propagation of it varies by adb/platform version and a probe that silently answers "no" would
# silently stop collecting.
e2e_required_e2e_present() {
  local run_as="$1" parent="$2" probe
  if [ -n "$run_as" ]; then
    probe="$(e2e_adb exec-out run-as "$run_as" sh -c "[ -d '$parent/required-e2e' ] && echo present" 2>/dev/null)"
  else
    probe="$(e2e_adb exec-out sh -c "[ -d '$parent/required-e2e' ] && echo present" 2>/dev/null)"
  fi
  [ "$(printf '%s' "$probe" | tr -d '\r')" = present ]
}

e2e_pull_required_e2e_artifacts() {
  local output_dir="$1" media_root
  mkdir -p "$output_dir"
  # The SOURCE side of each pipe reports its errors. Silencing it made "the device directory is
  # gone" indistinguishable from "there was nothing to collect", which is how a red CI build came
  # to upload a bundle with no per-test diagnostics in it and no trace of why.
  #
  # Which is why the existence probe comes FIRST. A green run legitimately has nothing to pull from
  # one or both of these roots, and letting tar say so itself printed `tar: required-e2e: Cannot
  # stat` on every passing run — once per method in direct mode — re-merging exactly the two cases
  # the un-silencing exists to separate. Probing first means the tar below only ever speaks when a
  # directory that EXISTS could not be read, which is always worth a line. `|| true` stays so a
  # collection failure never masquerades as the run's own exit status; only the extracting tar stays
  # quiet, since its complaint about an empty stream carries no information the source did not give.
  #
  # Older runners may use the instrumentation package's internal files directory.
  if e2e_required_e2e_present "$FAST_E2E_TEST_PACKAGE" files; then
    e2e_adb exec-out run-as "$FAST_E2E_TEST_PACKAGE" tar -C files -cf - required-e2e \
      | tar -C "$output_dir" -xf - 2>/dev/null || true
  fi
  # AndroidX PlatformTestStorage writes direct-instrumentation output under the target package's
  # app-specific media directory. Pull it explicitly so post-start failures are classified once
  # and pass through the same privacy audit as managed/connected Gradle output.
  media_root="/sdcard/Android/media/$FAST_E2E_TARGET_PACKAGE/additionalTestOutputDir"
  if e2e_required_e2e_present "" "$media_root"; then
    e2e_adb exec-out tar -C "$media_root" -cf - required-e2e \
      | tar -C "$output_dir" -xf - 2>/dev/null || true
  fi
}

e2e_collect_gradle_required_e2e_artifacts() {
  local output_dir="$1" source_file relative
  while IFS= read -r -d '' source_file; do
    relative="${source_file#*required-e2e/}"
    mkdir -p "$output_dir/required-e2e/$(dirname "$relative")"
    cp "$source_file" "$output_dir/required-e2e/$relative"
  done < <(find "$E2E_HARNESS_DIR/../../app/build/outputs" -type f \
    -path '*additional*' -path '*/required-e2e/*' -print0 2>/dev/null)
}
