# Updating the F-Droid version

motd is merged into fdroiddata, so a routine version bump needs no manual
metadata work. F-Droid's `checkupdates` bot proposes the new build entry itself.
This runbook covers what the bot does, what to check, and the cases where a
human still has to edit the recipe.

The build recipe, native libbox source build, and signing model are in
[`fdroid.md`](fdroid.md).

## 1. Ship the GitHub release

Cut the release with [`human-releasing.md`](human-releasing.md). The published
release must carry the upstream-signed APK (`motd-vX.Y.Z-foss.apk`),
`SHA256SUMS`, and the complete libbox source bundle, because F-Droid compares
its own rebuild against that APK.

Before tagging, make sure `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`
exists. F-Droid reads the "What's New" text from this repository, and a missing
file publishes an empty changelog.

## 2. Let the bot open the merge request

No action required. On its next run, F-Droid's `checkupdates` bot:

1. matches the new `vX.Y.Z` tag against `UpdateCheckMode: Tags`,
2. reads `motdVersionCode` and `motdVersionName` from `gradle.properties` at
   that tag and sets `CurrentVersion` / `CurrentVersionCode`,
3. deep-copies the last `Builds:` entry, rewrites `versionName` and
   `versionCode`, sets `commit:` to the tag and resolves it to the full
   40-character SHA,
4. pushes to the `io.github.trevarj.motd` branch on fdroiddata and opens or
   updates a single merge request titled `Update motd to <versionCode>`.

The bot keeps one open merge request per app, and it refreshes that branch only
while every commit on it is its own. A human commit on the branch makes the bot
leave it alone.

## 3. Check the bot's proposal

Find it under
[fdroiddata merge requests](https://gitlab.com/fdroid/fdroiddata/-/merge_requests?scope=all&state=opened&search=motd),
then confirm:

```sh
git rev-parse vX.Y.Z^{}     # must equal the entry's commit:
```

- `versionName` / `versionCode` match `gradle.properties` at that tag.
- The copied recipe fields still describe the source tree at that commit. This
  is the part the bot cannot know; see step 4.
- `Binaries:` and `AllowedAPKSigningKeys` are untouched.

## 4. Intervene when the recipe itself changed

The bot copies the previous entry verbatim, so any of these needs a hand-written
entry:

- Gradle flavor, task, or output-path changes.
- A different NDK, Go version, JDK, SDK platform, or build-tools version.
- A new libbox version, so a new `motdLibboxManifest` filename.
- A new file that must be removed before the source scan, or one of the existing
  `rm:` paths disappearing.
- A new ABI.

**Outstanding:** the flavor collapse. The first release containing
"build: remove Firebase/FCM and collapse the distribution flavor" needs
`gradle: yes` in place of `gradle: [foss]`, and the `app/src/google` and
`firebase` entries dropped from `rm:`. Details in [`fdroid.md`](fdroid.md)
"Recipe change pending". v0.13.0 predates that commit and is fine as a plain
copy.

To make the edit, work from the fork and open a merge request against
`fdroid/fdroiddata:master`:

```sh
git clone https://gitlab.com/trevarj/fdroiddata.git
cd fdroiddata
git remote add upstream https://gitlab.com/fdroid/fdroiddata.git
git fetch upstream
git checkout -B motd-update-vX.Y.Z upstream/master
$EDITOR metadata/io.github.trevarj.motd.yml
```

Append the new entry under `Builds:`, copying the previous one and changing only
`versionName`, `versionCode`, `commit`, and the fields the source change forces.
If the bot already opened a merge request for the same version, say so on it so
the two do not land twice.

```sh
nix shell nixpkgs#fdroidserver -c fdroid readmeta io.github.trevarj.motd
nix shell nixpkgs#fdroidserver -c fdroid lint io.github.trevarj.motd

git commit -S -m "io.github.trevarj.motd: update to X.Y.Z" -- metadata/io.github.trevarj.motd.yml
git push -u origin motd-update-vX.Y.Z
```

`fdroid build --test --verbose io.github.trevarj.motd:<versionCode>` is expected
to run on an F-Droid buildserver, not locally.

## 5. Merge, build, publish

An F-Droid maintainer merges. The buildserver then rebuilds from source and
compares the result against the GitHub APK; F-Droid publishes with the upstream
signature only when they match. That happens on F-Droid's timeline, not
immediately on merge. Watch the merge request for reviewer requests, typically
around srclibs pinning, metadata lint, and reproducibility.

Once published, the SHA-256 of the F-Droid APK must equal the SHA-256 of
`motd-vX.Y.Z-foss.apk` from the GitHub release.

## Reminders

- Version-code formula: `major * 1000000 + minor * 1000 + patch`.
- The package is arm64-v8a-only; adding another ABI needs a new source build,
  artifact verification, and an explicit metadata update (see
  [`fdroid.md`](fdroid.md) "FOSS boundary").
- Keep the release keystore and its backups safe: changing the key would break
  updates on both the GitHub and F-Droid channels and invalidate the
  `AllowedAPKSigningKeys` pin.
