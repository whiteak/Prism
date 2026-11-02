#!/bin/bash
# Requiem for iPhone: the full Prism phone app (whiteak/prism mobile/ + ios/ already applied to <checkout>),
# named Requiem, with Requiem's angel pictures, icons and black-and-silver look.
# usage: requiem/mobile/ios-on-prism.sh <path to the Prism-applied NuvioMobile checkout>
set -euo pipefail
src="$(cd "$(dirname "$0")" && pwd)"
cd "$1"
grep -q 'PrismIos.install();' composeApp/src/iosMain/kotlin/com/nuvio/app/MainViewController.kt || { echo "Apply Prism (mobile + ios) first." >&2; exit 1; }

# Requiem's pictures win over Prism's: app icons (iPhone + in-app list), wordmarks and the opening picture.
cp -R "$src/overlay/iosApp/." iosApp/
cp -R "$src/overlay/composeApp/src/commonMain/." composeApp/src/commonMain/
res=composeApp/src/commonMain/composeResources/drawable
cp "$res/edition_loading_art.webp" "$res/edition_loading_art_tall.webp"

# Name.
perl -0pi -e 's#(<key>CFBundleDisplayName</key>\s*<string>)[^<]*(</string>)#${1}Requiem$2#' iosApp/iosApp/Info.plist
grep -A1 '<key>CFBundleDisplayName</key>' iosApp/iosApp/Info.plist | grep -q '<string>Requiem</string>'
sed -i 's#[A-Za-z ]* · built on Nuvio#Requiem · built on Nuvio#' composeApp/src/commonMain/composeResources/values/strings.xml
# "Prism" in Customise's words becomes "Requiem" (only in text, never in code names).
for f in composeApp/src/commonMain/kotlin/com/nuvio/app/prism/*.kt composeApp/src/commonMain/kotlin/com/nuvio/app/EditionLoadingArt.kt; do
  perl -pi -e 's/(?<=["\s(])Prism(?=[\s"\x27,)?!:;])/Requiem/g if /"/' "$f"
done

# Own bundle ID, so it installs next to anything else (the widget's ID must start with the app's).
sed -i -E '/PRODUCT_BUNDLE_IDENTIFIER/ s#com\.nuvio\.(media|app)#com.requiem.app#' iosApp/iosApp.xcodeproj/project.pbxproj
if grep -q 'PRODUCT_BUNDLE_IDENTIFIER = com.nuvio' iosApp/iosApp.xcodeproj/project.pbxproj; then echo 'iOS bundle ID not changed' >&2; exit 1; fi

# Black and silver by default (a theme picked in Settings still wins); no Ethereal-only MN avatar.
sed -i "s#val EDITION_DEFAULT_THEME = AppTheme\.[A-Z_]*#val EDITION_DEFAULT_THEME = AppTheme.WHITE#" composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/AppTheme.kt
grep -q "EDITION_DEFAULT_THEME = AppTheme.WHITE" composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/AppTheme.kt
sed -i 's#const val EDITION_AVATARS_ENABLED = true#const val EDITION_AVATARS_ENABLED = false#' composeApp/src/commonMain/kotlin/com/nuvio/app/features/profiles/EditionAvatars.kt
grep -q 'EDITION_AVATARS_ENABLED = false' composeApp/src/commonMain/kotlin/com/nuvio/app/features/profiles/EditionAvatars.kt
echo "Requiem (iPhone, on Prism) applied."
