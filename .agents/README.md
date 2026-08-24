# MOTD agent handbook

`AGENTS.md` contains mandatory repository policy. This directory contains the
task-oriented guidance needed to apply that policy without turning the root file
into a long runbook.

## Repository orientation

- `app/` — Android application, Compose UI, Room persistence, preferences,
  uploads, push, connection lifecycle, and Android transport integration.
- `irc/` — pure-JVM IRC parser, serializer, client state machine, extensions,
  and socket transport.
- `test/e2e/` — fast isolated headless, physical-device, and exhaustive emulator
  harnesses plus the local ergo/soju bouncer stack.
- `.github/workflows/` — current CI, smoke, exhaustive E2E, and release behavior.

Read [`../ARCHITECTURE.md`](../ARCHITECTURE.md) before changing data flow,
connection ownership, or module boundaries.

## Working on a feature or fix

1. Inspect `git status`, existing diffs, nearby implementation, tests, and
   callers. Reproduce a bug before changing it when practical.
2. Identify the narrowest authoritative boundary: `:irc` for protocol behavior;
   repositories and `EventProcessor` for IRC-derived persistence;
   `ConnectionManager` for connection actions; ViewModels for screen state.
3. Implement the smallest coherent change. Keep Android types out of `:irc`,
   avoid whole-file buffering for uploads, and preserve cancellation/lifecycle
   behavior in long-running work.
4. Add or update tests at the same boundary. For UI changes, include semantics
   or stable tags when the interaction belongs in the device harness.
5. While iterating, run only the nearest local checks from
   [`testing.md`](testing.md). After the final commit, run `./tools/prepush.sh`
   once on the clean tree, inspect the diff, and report any verification that
   could not be performed. Leave emulator E2E to hosted CI unless the matrix or
   maintainer explicitly requires it locally.

## Task guides

- [`testing.md`](testing.md) — targeted local checks and hosted verification gates.
- [`releases.md`](releases.md) — signed tags, release artifacts, and failure
  recovery.
- [`../test/e2e/README.md`](../test/e2e/README.md) — local stack, physical
  device, hermetic emulator, phases, selectors, and diagnostics.
- [`../docs/obfuscation.md`](../docs/obfuscation.md) — SOCKS5, Tor, and VLESS +
  REALITY behavior and validation.
- [`../docs/ntfy-push.md`](../docs/ntfy-push.md) — delivery backends.
