# Building, linting, and testing motd

Enter the repository Nix shell first so the JDK and Android SDK match CI:

```sh
nix develop
```

direnv loads the same shell automatically via `.envrc` if you use it. All
Gradle commands below assume you are already inside this shell.

## Prerequisites

- Before rebuilding the bundled libbox AAR, initialize submodules recursively:

```sh
git submodule update --init --recursive
```

## Build

```sh
./gradlew :irc:test                   # protocol tests (pure JVM)
./gradlew :app:testDebugUnitTest  # app unit tests (Robolectric)
./gradlew :app:assembleDebug      # Google-free arm64 debug APK
```

The debug APK lands under `app/build/outputs/apk/debug/`. Install it with
`adb install`. The debug build carries the `.debug` application-id suffix, so
it can coexist with a release install.

The embedded VLESS + REALITY transport uses bundled libbox, which is
arm64-v8a-only. APKs built from this source tree must not be installed on
32-bit ARM or x86 devices. Other ABI support needs a separately pinned and
verified libbox artifact.

## Verification

Use the authoritative local command matrix in
[`.agents/testing.md`](../.agents/testing.md). Routine development runs the
nearest test class, not a whole module:

```sh
./gradlew :app:testDebugUnitTest \
  --tests '<fully-qualified-test-class>' --stacktrace
```

Run `:app:assembleDebug` when an APK or packaging check is needed. Full module
suites, release variants, lint, and E2E run in Required CI; use
`:app:lintDebug` locally only for an explicit pre-push lint check.

## Device and E2E testing

Do not run the headless emulator suite during routine local development; it
materially slows the maintainer's workstation. Local verification stops at the
nearest unit/integration tests and assembly only when needed.

For the local stack, physical-device, and emulator harnesses, follow
[`../test/e2e/README.md`](../test/e2e/README.md). The agent-facing selection
matrix in [`../.agents/testing.md`](../.agents/testing.md) describes which
suite fits which task. Those harnesses have their own shell requirements
documented alongside them.

## Architecture

For data flow, connection ownership, and module boundaries, see
[`../ARCHITECTURE.md`](../ARCHITECTURE.md).
