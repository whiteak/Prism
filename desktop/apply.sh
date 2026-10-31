#!/bin/bash
# Turns a NuvioMobile desktop checkout into Prism for Windows. Run mobile/apply.sh on it first.
# usage: desktop/apply.sh <path to NuvioMobile checkout>
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
cd "$1"
cp -R "$here/overlay/." .
# The window icon ships as an app resource; the installer icon is set in Gradle.
mkdir -p composeApp/desktop-resources/common
cp "$here/icon.png" composeApp/desktop-resources/common/icon.png
cp "$here/icon.ico" composeApp/desktop-icon.ico
gradle=composeApp/build.gradle.kts
perl -0pi -e 's/(\n(\s*)perUserInstall = false\n)/$1$2iconFile.set(project.file("desktop-icon.ico"))\n/' "$gradle"
grep -q 'iconFile.set(project.file("desktop-icon.ico"))' "$gradle"
main=composeApp/src/desktopMain/kotlin/com/nuvio/app/main.kt
perl -0pi -e 's/(\n(\s*)title = DesktopPaths\.appName,\n)/$1$2icon = com.nuvio.app.prism.PrismDesktop.windowIcon(),\n/' "$main"
perl -0pi -e 's/\n(\s*)runApp\(\)\n/\n$1com.nuvio.app.prism.PrismDesktop.install()\n$1runApp()\n/' "$main"
grep -q 'PrismDesktop.windowIcon(),' "$main"
grep -q 'PrismDesktop.install()' "$main"
perl -0pi -e 's/onCloseRequest = ::exitApplication,/onCloseRequest = { com.nuvio.app.prism.PrismDesktop.closing(::exitApplication) },/' "$main"
grep -q 'PrismDesktop.closing(::exitApplication)' "$main"
# The screensaver over the whole window (Customise > 12. Sleep), as on the TV.
perl -0pi -e 's/\n(\s*)App\(\)\n(\s*)\}\n\}/\n$1com.nuvio.app.prism.PrismScreensaverHost { App() }\n$2}\n}/' "$main"
grep -q 'PrismScreensaverHost { App() }' "$main"
echo "Prism applied (desktop)."
