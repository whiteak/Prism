# Prism — CLAUDE.md

Prism is a **customisation layer, not a full app**. This repo holds only Prism's changes. Each build checks out a
fresh copy of a Nuvio fork and runs an `apply.sh` here over it. The forks' code is NOT in this repo: don't search
for Nuvio files here. Clone the fork into a scratch dir if you need to read one.

**Jason's rule: every app is its own** (Prism, Requiem, Ethereal, Supernatural, Clipper). Change only the app he
names. Never carry a change into another app, its branch or its base unless he asks for that.
This repo's `main` branch is Prism (and Clipper). The `requiem` branch is **Requiem, a separate app** (see below).

| Target  | Fork (branch)                                    | Script(s), run in this order          | Output                      |
|---------|--------------------------------------------------|---------------------------------------|-----------------------------|
| Phone   | whiteak/NuvioMobile `base/prism`                 | `mobile/apply.sh`                     | `Prism-Android-<v>.apk`     |
| Windows | whiteak/NuvioMobile `base/prism`                 | `mobile/apply.sh` then `desktop/apply.sh` | `Prism-Windows-<v>.msi`  |
| TV      | whiteak/NuvioTV `base/prism`                     | `tv/apply.sh`                         | `Prism-TV-<v>.apk`          |
| Clipper | none (standalone app in `clipper/`)              | none                                  | `Clipper-1.0.<n>.apk`       |

**`base/prism` is Prism's own fixed base** in each fork (moved on 14 Nov 2026, at Jason's request, to upstream NuvioMobile 0.6.12 `cmp-rewrite` and NuvioTV 1.2.0-beta.1 `dev`; the PC's desktop code was updated to match).
It does NOT follow Ethereal (`cmp-rewrite` / `dev`) or upstream Nuvio. Move it forward, or change fork code under
Prism, only when Jason asks, and only on `base/prism`. The branch names are set in `env:` in `prism.yml` and `check.yml`.

**Prism's iPhone work (`ios/`) is NOT on `main`.** It lives on branch `claude/project-thread-rs7nwb` (6 ahead of and
6 behind `main` on 2 Nov 2026). Requiem's branch has its own copy. Don't merge or delete it without asking Jason.

## Layout

```
art/            Python (Pillow) scripts that make the artwork
  make_masters.py   draws the original crystal artwork into masters/ (now mostly superseded)
  from_picture.py   makes the icon, opening pictures and wordmark from art/source/jason-*.png into masters/
  mkoverlay.py      masters/ -> mobile/overlay/ images  (usage: mkoverlay.py <NuvioMobile checkout>)
  mktvoverlay.py    masters/ -> tv/overlay/ images      (usage: mktvoverlay.py <NuvioTV checkout>)
  source/           Jason's own source pictures (inputs; never overwrite)
masters/        GENERATED master images (icon + colour variants, fg, banner, wordmark, opening pictures)
mobile/
  apply.sh      turns a NuvioMobile checkout into Prism (phone + shared code for the PC)
  icons.txt     extra launcher icons: key|ClassName|Label (one per line)
  overlay/      files copied over the fork, mirroring its paths
    composeApp/src/commonMain/kotlin/com/nuvio/app/prism/   shared Kotlin (Prism*.kt)
    composeApp/src/androidMain/.../prism/PrismAndroid.kt     phone-only hooks
desktop/
  apply.sh      Windows-only changes; needs mobile/apply.sh run first
  icon.ico/png  window + installer icon
  overlay/.../desktopMain/.../prism/PrismDesktop*.kt
tv/
  apply.sh      turns a NuvioTV checkout into Prism for TV
  overlay/app/src/main/java/com/nuvio/tv/ui/components/PrismTv*.kt  and .../screens/settings/PrismTv*.kt
clipper/        standalone Android app (package com.prism.clipper), own Gradle build
.github/workflows/
  prism.yml     builds phone/TV/PC and publishes the release (manual dispatch only)
  check.yml     compile-only check of one app: tv | phone | pc (manual dispatch)
  clipper.yml   builds Clipper (dispatch, or a push with [clipper] in the commit message)
```

## How the patch scripts work

Every `apply.sh` follows the same pattern. Keep to it:

1. `cp -R overlay/. <checkout>` copies new files and replaced resources over the fork.
2. **Anchored in-place edits** with `sed -i` / `perl -0pi -e` against exact text in the fork's files
   (e.g. insert `com.nuvio.app.prism.PrismOverlay()` after `EditionIntroOverlay()` in `App.kt`).
3. **An assertion after every edit**: `grep -q '<new text>' "$file"` (or `! grep -q` for removals). Together with
   `set -euo pipefail`, a patch whose anchor has drifted fails the build loudly instead of being skipped.

Rules for patches:
- Every new `sed`/`perl` edit gets a `grep -q` assertion right after it. No exceptions.
- Hook into the fork with a **single call** to Prism code (`com.nuvio.app.prism.X(...)`), and keep the logic in
  `overlay/` Kotlin. Patches should stay small and boring.
- Use fully qualified names in injected calls so you don't have to patch imports.
- Anchors match **LF line endings**. The Windows job sets `core.autocrlf false`. Never commit CRLF.
- If an anchor stops matching after an upstream sync, read the fork file at the pinned branch and update the
  anchor. Don't loosen the regex until it matches anything.
- The updater sends no key: Prism's releases repo is public, so `editionAuthHeaders()` in `Edition*UpdateAuth.kt`
  returns an empty map and `prism.yml` passes no token. Don't add a token.

### Checking a patch
**Jason builds only through GitHub Actions. Don't run Gradle or set up a local Android/Windows build.**
To check that a patch applies, run the scripts on fresh clones (no Gradle needed):
```bash
git clone --depth 1 -b base/prism https://github.com/whiteak/NuvioMobile /tmp/nm
bash mobile/apply.sh /tmp/nm                     # must finish with no error
git clone --depth 1 -b base/prism https://github.com/whiteak/NuvioTV /tmp/ntv
bash tv/apply.sh /tmp/ntv
```
Always re-clone fresh, because the scripts are not idempotent. To compile without a release, Jason runs Actions >
**Check Prism compiles** (`check.yml`, choosing tv | phone | pc).

## Build & release (`prism.yml`)

- Manual only: Actions > Build Prism > Run workflow. Inputs: `apps` = all | phone | phone-tv, `force`, `keep_previous` = 2 | 1.
- The check job resolves the fork SHAs and skips the release if the last `prism-N` has the same "Built from" stamp
  (unless `force`). It then creates a **draft** release `prism-N` (N = run number + 100).
- Phone and TV are built on ubuntu (JDK 17) as `assembleFullDebug`, signed with `SIGNING_KEYSTORE`, alias `ethereal`.
  The PC is built on windows (JDK 21) with `packageMsi`, bundles libVLC 3.0.21, and has a fixed upgrade UUID.
  **Never change the upgrade UUID**, or installs stop upgrading in place.
- Versions: phone uses `<fork MARKETING_VERSION>.<N>`, TV uses `<fork versionName>.<N>`, PC uses `1.0.<N>`.
- The publish job un-drafts the release, keeps the new release plus the previous `keep_previous` ones (input, default 2, so two
  builds to roll back to) plus the newest one with a PC build, and deletes Actions artifacts.
- Releases are **never marked latest**. The in-app updaters read the release list and only take assets prefixed
  `Prism-Android-` / `Prism-TV-`. Don't rename the assets.
- Secrets: `SIGNING_KEYSTORE`, `SIGNING_KEY_PASSWORD`, `TRAKT_CLIENT_ID`, `TRAKT_CLIENT_SECRET`.
- App IDs: `com.prism.app` (phone), `com.prism.tv` (TV). Don't change them.

Clipper: `clipper.yml` uses Gradle 8.13 with `assembleRelease` and publishes `clipper-N`, keeping the newest 2. It's
independent of the forks. Don't touch `prism.yml` for Clipper work.

## Rules

- **Never hand-edit generated files**: `masters/*` and the images under `mobile/overlay/` and `tv/overlay/`.
  Change the script in `art/` (or the source picture), then regenerate:
  `python3 art/from_picture.py` (or `make_masters.py`), then `mkoverlay.py <NuvioMobile>`, then
  `mktvoverlay.py <NuvioTV>`. The Kotlin files under `overlay/` are hand-written source and fine to edit.
- **Keep phone, TV and PC consistent.** A Customise feature exists on all three, with the same section numbers
  (e.g. "Customise > 12. Sleep"). Shared phone/PC code lives in `mobile/overlay/.../prism/PrismX.kt`, and its TV
  twin is usually `tv/overlay/.../PrismTvX.kt`. When you change one, check the other and say so if the TV
  can't match.
- Looks are made on the phone and loaded on the TV/PC from a `.prism` file or over Wi-Fi. The format is defined in
  `mobile/overlay/.../prism/PrismStore.kt` (`exportLook` / `importLook`), and the TV reads it in `PrismTv.kt`.
  Keep it backward compatible, because older looks must still load.
- A new launcher icon goes in `mobile/icons.txt` and needs its images in the overlay (see the loop in
  `mobile/apply.sh`, which asserts that they exist).
- Nothing personal goes in this repo (pictures, sounds, quotes and saved looks stay on devices).
- Licence is GPLv3 (inherited from Nuvio). Keep the "built on Nuvio" credit.

## Requiem: a separate app on the `requiem` branch

Requiem started as Prism with Requiem's name and pictures. It now has **its own copy** of the Prism layer on this
repo's `requiem` branch (plus `requiem/`), its own fork bases (`base/requiem`) and its own releases
(whiteak/Requiem). Changes on `main` never reach Requiem, and Requiem changes never touch `main`, unless Jason asks
for a change to be copied over. Copying is then a cherry-pick onto `requiem`, checked with its scripts.
- **Build Requiem** (`.github/workflows/requiem.yml`) is on `main` only because GitHub lists manual workflows from the
  default branch. It builds from the `requiem` branch. Editing it is Requiem work, not Prism work.
- For Requiem work, check out `requiem` and follow the CLAUDE.md there.

Supernatural (a separate repo) has similar patches (Trakt PKCE, update auth, library share). Don't port fixes to it
unless Jason asks.

## Working efficiently

- Read the relevant `apply.sh` section first. Comments above each block name the feature.
- Large binary folders (`masters/` ~96 MB, `art/source/`, overlay images): don't list, read or grep them.
- Commit straight to `main`. `prism.yml` only runs on manual dispatch, so pushes don't trigger a release.
- `prism.yml`'s check step still has dead `push`/`schedule` cases and `[release]`/`[phone]` commit tags (the
  workflow is dispatch-only). Remove them the next time you're editing `prism.yml` anyway.
