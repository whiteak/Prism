#!/bin/bash
# Requiem for Android phones: the full Prism phone app (whiteak/prism mobile/apply.sh already applied to <checkout>),
# named Requiem, with Requiem's angel pictures, icons and black-and-silver look. It keeps Requiem's app ID
# (com.requiem.app) and signing key, and updates from Requiem-Android-*.apk on this repo's releases, so the
# Requiem already on a phone updates to it in the app.
# usage: requiem/mobile/android-on-prism.sh <path to the Prism-applied NuvioMobile checkout>
set -euo pipefail
src="$(cd "$(dirname "$0")" && pwd)"
cd "$1"
grep -q 'PrismAndroid.install(this)' composeApp/src/androidMain/kotlin/com/nuvio/app/MainActivity.kt || { echo "Apply Prism (mobile) first." >&2; exit 1; }

# Requiem's pictures win over Prism's: app icons (launcher + in-app list), wordmarks and the opening picture.
cp -R "$src/overlay/composeApp/." composeApp/
res=composeApp/src/commonMain/composeResources/drawable
cp "$res/edition_loading_art.webp" "$res/edition_loading_art_tall.webp"

# Name and colours behind the icon and the Android splash.
grep -rlZ 'name="app_name"' composeApp/src/androidMain androidApp/src --include='*.xml' | xargs -0 sed -i 's#<string name="app_name">[^<]*</string>#<string name="app_name">Requiem</string>#'
sed -i 's#[A-Za-z ]* · built on Nuvio#Requiem · built on Nuvio#' composeApp/src/commonMain/composeResources/values/strings.xml
sed -i 's#<color name="app_icon_background">[^<]*</color>#<color name="app_icon_background">\#F2F2F4</color>#' composeApp/src/androidMain/res/values/colors.xml
sed -i 's#<color name="edition_splash_background">[^<]*</color>#<color name="edition_splash_background">\#0B0B0C</color>#' composeApp/src/androidMain/res/values/colors.xml
# "Prism" in Customise's words becomes "Requiem" (only in text, never in code names).
for f in composeApp/src/commonMain/kotlin/com/nuvio/app/prism/*.kt composeApp/src/androidMain/kotlin/com/nuvio/app/prism/*.kt \
         composeApp/src/commonMain/kotlin/com/nuvio/app/EditionLoadingArt.kt; do
  [ -f "$f" ] || continue
  perl -pi -e 's/(?<=["\s(])Prism(?=[\s"\x27,)?!:;])/Requiem/g if /"/' "$f"
done

# Same app ID as the Requiem already installed, so it updates in place.
sed -i 's#"com.prism.app"#"com.requiem.app"#' androidApp/build.gradle.kts
grep -q '"com.requiem.app"' androidApp/build.gradle.kts
! grep -q '"com.prism.app"' androidApp/build.gradle.kts

# In-app updates: Requiem's phone APK on Requiem's own releases (whiteak/Requiem), never Prism's or Ethereal's.
# The release's version is read from the APK's name (Requiem-Android-<version>.apk).
upd=composeApp/src/commonMain/kotlin/com/nuvio/app/features/updater/AppUpdaterRepository.kt
sed -i 's#RELEASE_ASSET_PREFIX = "Prism-Android-"#RELEASE_ASSET_PREFIX = "Requiem-Android-"#' "$upd"
sed -i 's#api.github.com/repos/whiteak/prism/#api.github.com/repos/whiteak/Requiem/#' "$upd"
grep -q '"Requiem-Android-"' "$upd"; grep -q 'whiteak/Requiem/' "$upd"; ! grep -q 'whiteak/prism/' "$upd"

# Black and silver by default (a theme picked in Settings still wins); no Ethereal-only MN avatar.
sed -i "s#val EDITION_DEFAULT_THEME = AppTheme\.[A-Z_]*#val EDITION_DEFAULT_THEME = AppTheme.WHITE#" composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/AppTheme.kt
grep -q "EDITION_DEFAULT_THEME = AppTheme.WHITE" composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/AppTheme.kt
sed -i 's#const val EDITION_AVATARS_ENABLED = true#const val EDITION_AVATARS_ENABLED = false#' composeApp/src/commonMain/kotlin/com/nuvio/app/features/profiles/EditionAvatars.kt
grep -q 'EDITION_AVATARS_ENABLED = false' composeApp/src/commonMain/kotlin/com/nuvio/app/features/profiles/EditionAvatars.kt
echo "Requiem (Android phone, on Prism) applied."
