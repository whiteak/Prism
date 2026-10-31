# Prism

Prism is a streaming app for phone, TV and Windows PC that you customise yourself, inside the app, under
Settings > Appearance > Customise (on the TV, Settings > Appearance > Customise this TV). Each device keeps its own
set-up, and the TV and PC can also copy the phone's over the home Wi-Fi.

Prism is built on [Nuvio](https://github.com/NuvioMedia/NuvioMobile) (phone and PC) and NuvioTV (TV), through the
forks [whiteak/NuvioMobile](https://github.com/whiteak/NuvioMobile) and [whiteak/NuvioTV](https://github.com/whiteak/NuvioTV).
Like Nuvio, it is free software under the GNU General Public License v3 (see `LICENSE`). Thanks to the Nuvio developers.
Prism doesn't include or add any streaming sources.

## How it's made

This repo holds only Prism's changes; each build applies them to a fresh copy of the Nuvio forks. Nothing personal is kept here: pictures, sounds, quotes, rows and
saved looks stay on each device, and a look can be exported as a `.prism` file to load on another one.

- `art/make_masters.py` draws Prism's own artwork (the faceted crystal mark, wordmark, opening pictures) into `masters/`.
- `art/mkoverlay.py <NuvioMobile checkout>` redraws every edition image from the masters into `mobile/overlay/`.
- `mobile/apply.sh`, `desktop/apply.sh` turn a NuvioMobile checkout (desktop branch) into Prism for the phone and Windows.
- The Kotlin lives in `mobile/overlay/.../com/nuvio/app/prism/` (shared), `PrismAndroid.kt` (phone) and `desktop/overlay/.../PrismDesktop.kt` (PC).
- `.github/workflows/prism.yml` builds both and publishes `prism-N` releases (Prism-Android-*.apk, Prism-Windows-*.msi).

App IDs: `com.prism.app` (phone); the PC installer has its own upgrade code, so it installs alongside other Nuvio-based apps.
