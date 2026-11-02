#!/usr/bin/env bash
# The app's iPhone code uses a few iOS 27.1 extras (fold hinge, side bar edge) that the runner's
# Xcode SDK does not have yet. If the SDK is older, take those extras out (iPhones don't need them).
set -euo pipefail
app="$1"
f="$app/iosApp/iosApp/ContentView.swift"
sdk=$(xcrun --sdk iphoneos --show-sdk-version 2>/dev/null || echo 0)
if [ "$(printf '%s\n27.1\n' "$sdk" | sort -V | head -n1)" = "27.1" ]; then
  echo "SDK $sdk has iOS 27.1, nothing to change"; exit 0
fi
perl -0pi -e '
  s/\n\s*if #available\(iOS 27\.1, \*\) \{\n\s*registerForTraitChanges.*?\n        \}\n/\n/s or die "block 1";
  s/guard #available\(iOS 27\.1, \*\), isHalfOpen,.*?hinge\.update\(top: Double\(fold\.frame\.minY\), bottom: Double\(fold\.frame\.maxY\)\)/hinge.clear()/s or die "block 2";
  s/guard #available\(iOS 27\.1, \*\) else \{ return \.zero \}.*?default:\n\s*return \.zero\n\s*\}/return .zero/s or die "block 3";
  s/if #available\(iOS 27\.1, \*\), disabled \{\n\s*toolbarVerticalBehavior\(\.disabled\)\n\s*\} else \{\n\s*(self)\n\s*\}/$1/s or die "block 4";
' "$f"
if grep -q '27\.1' "$f"; then echo "iOS 27.1 code still left in ContentView.swift" >&2; exit 1; fi
echo "Took the iOS 27.1 extras out (SDK $sdk)"
