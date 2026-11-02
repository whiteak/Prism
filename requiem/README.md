# Requiem

A black and silver Angelcore app with everything from Prism inside, for phone, iPhone and TV. Requiem is its own app.
This branch of the Prism repo holds its code, it builds on its own fixed bases (`base/requiem` in whiteak/NuvioMobile
and whiteak/NuvioTV), and it publishes to its own releases at [whiteak/Requiem](https://github.com/whiteak/Requiem).

- `mobile/`: phone and iPhone. `android-on-prism.sh` and `ios-on-prism.sh` turn a Prism-applied NuvioMobile into
  Requiem (name, app ID `com.requiem.app`, pictures, white theme, updater). `ios-old-sdk.sh` covers older Xcode SDKs.
- `tv/`: `tv-on-prism.sh` turns a Prism-applied NuvioTV into Requiem (app ID `com.requiem.tv`).
- `overlay/` images are made from `masters/` by `mkoverlay.sh`.

Built by **Actions > Build Requiem** in the Prism repo.
