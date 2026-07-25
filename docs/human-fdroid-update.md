# Updating the F-Droid version

Per-release runbook for bumping the F-Droid metadata after a GitHub release.
The F-Droid build recipe, native libbox source build, and signing model are
documented in [`fdroid.md`](fdroid.md); this file is the step-by-step for
shipping a new version.

The worked example below updates from `0.10.7` (pinned by the open fdroiddata
merge request !43407) to `0.10.8` (the current `gradle.properties` default).
Substitute your target version throughout.

## 1. Confirm the GitHub release exists

A GitHub release for `v0.10.8` must already be published: the upstream-signed
FOSS APK, `SHA256SUMS`, and the complete libbox source bundle. If not, cut it
first via [`human-releasing.md`](human-releasing.md).

## 2. Get the full upstream commit SHA

F-Droid metadata must pin the source with a full 40-character commit SHA.
Never use a branch name or abbreviated SHA.

```sh
git rev-parse v0.10.8^{}
```

Use the commit the release tag points to. The release workflow embeds that SHA
as source provenance, so the F-Droid `commit:` field must match it exactly.

## 3. Clone the fdroiddata fork

<!-- TODO: confirm the fork URL and branch name below. -->

```sh
git clone https://gitlab.com/trevarj/fdroiddata.git   # confirm fork URL
cd fdroiddata
git checkout -b motd-update-v0.10.8                   # confirm branch name
```

The application metadata lives at
`metadata/io.github.trevarj.motd.yml`.

## 4. Add the new build entry

Append a new entry under `Builds:` in `metadata/io.github.trevarj.motd.yml`,
copying the recipe block from the previous (`0.10.7`) entry. The canonical
field set is in [`fdroid.md`](fdroid.md) "Native source build". Update only the
version-specific fields:

```yaml
Builds:
  - versionName: 0.10.8
    versionCode: 10008
    commit: <full 40-character upstream commit SHA>
    subdir: app
    submodules: true
    # ... rm, ndk, srclibs, build, gradle, gradleprops unchanged from 0.10.7
```

The `Binaries:` URL is templated with `v%v`, so it needs no per-version edit;
confirm this against the existing metadata before relying on it.
`AllowedAPKSigningKeys` stays the pinned release certificate and must not
change between versions.

The `AutoUpdateMode: Version` and `UpdateCheckData` recipe reads
`gradle.properties`, so once the metadata is in, future tags flow through
automatically; this manual step is only needed to add the explicit `Builds:`
entry for the version being submitted.

## 5. Run the local fdroid CLI checks

`fdroidserver` is not in the project flake, so invoke it with `nix shell`
without editing `flake.nix`. Run these from the fdroiddata checkout root:

```sh
nix shell nixpkgs#fdroidserver -c fdroid readmeta io.github.trevarj.motd
nix shell nixpkgs#fdroidserver -c fdroid lint io.github.trevarj.motd
```

`fdroid build --test --verbose io.github.trevarj.motd:10008` exercises the
provisioned Go, JDK, NDK, SDK, and offline module cache, so it is expected to
run on an F-Droid buildserver, not locally.

## 6. Verify reproducibility

F-Droid publishes the upstream signature only after its unsigned rebuild
matches the GitHub release APK. Once F-Droid publishes, compare the SHA-256 of
the GitHub `motd-v0.10.8-foss.apk` against the F-Droid-published APK; they must
match. See [`fdroid.md`](fdroid.md) "Reproducible signing" for the certificate
pin and the matching contract.

## 7. Push and update the merge request

```sh
git add metadata/io.github.trevarj.motd.yml
git commit -m "io.github.trevarj.motd: update to 0.10.8"
git push origin motd-update-v0.10.8
```

The existing MR !43407 pins `0.10.7`; pushing to its branch updates it in
place. Confirm whether to reuse that branch or open a fresh MR for the new
version.

## 8. Mind the merge request

Watch the MR for reviewer requests — typically srclibs pinning, metadata lint,
and reproducibility — and respond on the MR. F-Droid publishes after the MR
merges, the buildserver rebuilds, and the verified match succeeds, on F-Droid's
timeline, not immediately on merge.

## Reminders

- Version-code formula: `major * 1000000 + minor * 1000 + patch`.
- The package is arm64-v8a-only; adding another ABI needs a new source build,
  artifact verification, and an explicit metadata update (see
  [`fdroid.md`](fdroid.md) "FOSS boundary").
- Keep the release keystore and its backups safe: changing the key would break
  updates on both the GitHub and F-Droid channels.