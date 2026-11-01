#!/bin/bash
# Adds the iPhone parts of Prism to a NuvioMobile checkout that mobile/apply.sh has already turned into Prism.
# usage: ios/apply.sh <path to NuvioMobile checkout>
# Needs GNU sed/grep (on a Mac: brew install gnu-sed grep, with their gnubin first on PATH).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
cd "$1"
grep -q 'prism.PrismOverlay()' composeApp/src/commonMain/kotlin/com/nuvio/app/App.kt || { echo "Run mobile/apply.sh first." >&2; exit 1; }
cp -R "$here/overlay/." .

# Prism's iPhone parts (PrismIos.kt) start with the first screen.
mvc=composeApp/src/iosMain/kotlin/com/nuvio/app/MainViewController.kt
perl -0pi -e 's/(\): UIViewController = ComposeUIViewController\(\n\s*configure = \{)/$1 com.nuvio.app.prism.PrismIos.install();/' "$mvc"
grep -q 'PrismIos.install();' "$mvc"

# The app's own folder shows in the Files app (On My iPhone > app name): that's the Prism folder on an iPhone.
plist=iosApp/iosApp/Info.plist
for key in UIFileSharingEnabled LSSupportsOpeningDocumentsInPlace; do
  grep -q "<key>$key</key>" "$plist" || perl -0pi -e "s#<dict>\n#<dict>\n\t<key>$key</key>\n\t<true/>\n#" "$plist"
  grep -q "<key>$key</key>" "$plist"
done

# The TV and PC copy from the iPhone over the home Wi-Fi (PrismIosShare): iOS asks for local network access once.
grep -q '<key>NSLocalNetworkUsageDescription</key>' "$plist" || perl -0pi -e 's#<dict>\n#<dict>\n\t<key>NSLocalNetworkUsageDescription</key>\n\t<string>So your TV and PC can copy what you set up here, over your home Wi-Fi.</string>\n#' "$plist"
grep -q '<key>NSLocalNetworkUsageDescription</key>' "$plist"

echo "Prism applied (iPhone)."
