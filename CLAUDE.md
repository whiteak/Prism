# Requiem (Prism repo, `requiem` branch) — CLAUDE.md

**This branch is Requiem, a separate app.** It is Prism's customisation layer as Requiem uses it, plus `requiem/`
(Requiem's name, pictures, black-and-silver look and updater). Prism itself lives on `main`.
**Jason's rule: each app is its own.** Change only the app he names. Never copy changes between this branch and
`main` (Prism) unless he asks for that, and never merge either way without asking.

| Requiem app | Base (fork, fixed)             | Order of scripts                                                      | App ID / file                  |
|-------------|--------------------------------|-----------------------------------------------------------------------|--------------------------------|
| Phone       | NuvioMobile `base/requiem`     | `mobile/apply.sh` (icons.txt emptied) → `requiem/mobile/android-on-prism.sh` | `com.requiem.app`, `Requiem-Android-<v>.apk` |
| iPhone      | NuvioMobile `base/requiem`     | `mobile/apply.sh` → `ios/apply.sh` → `requiem/mobile/ios-on-prism.sh`  | unsigned `Requiem-iOS-<v>.ipa` |
| TV          | NuvioTV `base/requiem`         | `tv/apply.sh` → `requiem/tv/tv-on-prism.sh`                            | `com.requiem.tv`, `Requiem-TV-<v>.apk` |

The `base/requiem` branches only move forward when Jason asks. Don't build Requiem from `cmp-rewrite`, `dev`,
`lph2qt` or another app's base.

## Build & release

- Workflow: **Build Requiem** (`.github/workflows/requiem.yml` on Prism's `main`, because GitHub only lists manual
  workflows from the default branch). It checks out this branch (`requiem_ref` input), so the code comes from here.
  Inputs: `app` = phone | tv | iphone.
- It publishes to **whiteak/Requiem** (public) with the `REQUIEM_TOKEN` secret. The signing key (alias `ethereal`) and
  Trakt keys are Prism's existing secrets.
- Release tags are `phone-<v>`, `tv-<v>`, `iphone-<v>`. They're deliberately not bare version numbers: each app reads
  its version from its own APK's name (`editionTagged`), so phone and TV releases never get mixed up. Keep that.
- Numbering continues from before the move (phone 0.6.4.3, TV 1.1.0-beta.8.3, iPhone 0.6.4.2 onwards).
- The updaters read `api.github.com/repos/whiteak/Requiem/` (phone) and `GITHUB_REPO "Requiem"` (TV). Keep them.

## Rules

- Same patch rules as Prism: anchored `sed`/`perl` edits, each followed by a `grep -q` assertion, LF line endings.
- `requiem/*/on-prism.sh` check for Prism hooks (`PrismAndroid.install(this)`, `PrismIos.install();`,
  `PrismTvOpening()`) and rewrite "Prism" in string literals to "Requiem". Keep user-facing text as a plain "Prism".
- Never hand-edit `requiem/*/overlay/` or `masters/` images. Regenerate them with `requiem/*/mkoverlay.sh <fork checkout>`.
- Jason builds only through GitHub Actions. To check a change, apply the scripts to a fresh
  `git clone --depth 1 -b base/requiem` of the fork (no Gradle).
- Don't list or read image folders (`masters/`, `requiem/*/masters`, `*/overlay` images, `art/source`).
