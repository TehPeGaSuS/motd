# Testing and verification

Run all Gradle commands through the repository Nix shell. Run the nearest useful
check in each changed boundary; do not expand into full local suites. Hosted CI
owns broad verification. Run `nix develop -c ./gradlew ktlintCheck` before handoff;
use `ktlintFormat` to apply the enforced Kotlin style.

## Command matrix

| Changed surface | Required local checks |
| --- | --- |
| Documentation only | `git diff --check`; verify links, commands, and referenced paths |
| Shell harness/config | `bash -n test/e2e/*.sh test/e2e/fixtures/*.sh test/e2e/hermetic/*/*.sh` plus the relevant dry run |
| IRC parser/client/transport | Nearest `:irc` test class with `--tests` |
| Android repositories, services, preferences, or ViewModels | Nearest `:app` test class with `--tests` |
| Compose/resources/manifest | Nearest test when behavior changed, then `:app:assembleDebug` |
| Ordinary app user journey | Relevant unit/integration test; assemble only when an APK is needed |
| Cross-module or release-sensitive work | Nearest tests in each affected module; rely on Required CI for full release parity |

Target one test class during local development:

```sh
nix develop -c ./gradlew :app:testDebugUnitTest \
  --tests '<fully-qualified-test-class>' --stacktrace
```

Use `:irc:test` instead for IRC tests. Run `:app:assembleDebug` when compilation,
resources, manifest, packaging, or an installable APK must be checked. Full
module suites, release variants, lint, and E2E belong to Required CI. Run
`:app:lintDebug` locally only for an explicit pre-push lint check.

## Deterministic generated tests

Generated tests default locally to checked-in regressions plus one generated
case per target. Required CI explicitly selects the PR workload and replaces the
seed with the candidate commit; `.github/workflows/fuzz.yml` selects the larger
nightly profile.

The nightly workflow runs three disjoint case-index shards for each module. An
IRC shard covers 200,000 parser cases and 75,000 mapper cases. An app shard
covers 75,000 presentation cases, 1,500 canonical-timeline cases with 128
operations each, and 500 EventProcessor cases. Job summaries report the
effective counts, index ranges, and any manual overrides.

- `MOTD_FUZZ_SEED=<text>` selects an exact seed.
- `MOTD_FUZZ_CASE=<index>` replays one independently seeded case.
- `MOTD_FUZZ_PROFILE=pr|nightly` selects a hosted workload; unset uses the local workload.
- `MOTD_FUZZ_CASES=<count>` and `MOTD_FUZZ_STEPS=<count>` override campaign size.
  Only positive values apply (`0` falls back to the selected profile).
- `MOTD_FUZZ_SHARD=<zero-based index>` offsets generated case indices by one
  configured case-count, allowing parallel jobs to cover disjoint cases under
  the same reproducible seed. Exact `MOTD_FUZZ_CASE` replay ignores the shard.

Failures print an exact Nix/Gradle replay command and write the generated
operation trace below the module's `build/fuzz-failures/` directory. Minimize a
real failure into a named JUnit regression and retain its target, generator
version, seed, case, and fixture in that module's
`src/test/resources/fuzz/regressions.tsv` file.

## Device and E2E selection

- Do not run the headless emulator suite during routine local development. It
  materially slows the maintainer's workstation. Local verification stops at
  nearest unit/integration tests and assembly only when the matrix requires it.
- `.github/workflows/ci.yml` owns the complete required gate. Its `headless` job runs exactly
  four isolated `@FastHeadlessE2e` methods on API34 Pixel 6 AOSP, while the parallel
  `component-ui` job runs all 111 hermetic component instrumentation cases and excludes the
  real-stack annotation. That count is the number `test/e2e/component-suite.sh` enforces
  (`EXPECTED_CASES`); keep the two in sync when component tests are added or removed.
  Push the candidate commit and require the complete CI gate to pass before
  tagging a release.
- Use a physical device for hardware- or OS-integration evidence: input latency,
  scrolling performance, wallpaper/rendering quality, background lifecycle,
  notifications and UnifiedPush, system pickers, certificates outside the
  fixture trust flow, and a real release installation. Only do this when the
  maintainer explicitly asks for device validation.
- Only when lower-level checks cannot validate behavior, reproduce the focused
  CI suite with `./test/e2e/headless.sh fast`.
- `test/e2e/component-suite.sh` is the canonical managed-device launcher for the hermetic
  Compose/component instrumentation tier. It enforces the expected case count so new tests cannot
  silently disappear from CI.
- `test/e2e/fast-suite.sh` is the canonical fast-suite launcher and fixture
  argument source for local direct instrumentation, connected CI, and the
  managed-device smoke workflow. Do not duplicate its annotation or fixture
  arguments in workflow YAML.
- Use `test/e2e/runbook.sh` for multi-screen interaction and crash sweeps. The
  local headless `full` command runs A-H/J/V/R before teardown phase I on the isolated emulator;
  the hermetic Docker stack is used by the scheduled/manual CI workflow.
- Use `:app:assembleE2e` only for x86_64 emulator testing. It deliberately
  excludes the arm64-only embedded libbox core and is not representative of
  obfuscation support.

When explicitly debugging CI E2E, follow
[`../test/e2e/README.md`](../test/e2e/README.md) for setup and teardown.
Never point the destructive E2E reset flow at the release application id.
