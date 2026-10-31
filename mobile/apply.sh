#!/bin/bash
# Turns a NuvioMobile (Ethereal) checkout into Prism, the app you customise yourself (Settings > Appearance > Customise).
# usage: mobile/apply.sh <path to NuvioMobile checkout>
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
cd "$1"
cp -R "$here/overlay/." .
grep -rlZ 'name="app_name"' composeApp/src/androidMain androidApp/src --include='*.xml' | xargs -0 sed -i 's#<string name="app_name">[^<]*</string>#<string name="app_name">Prism</string>#'
sed -i 's#[A-Za-z ]* · built on Nuvio#Prism · built on Nuvio#' composeApp/src/commonMain/composeResources/values/strings.xml
sed -i 's#<color name="app_icon_background">[^<]*</color>#<color name="app_icon_background">\#080612</color>#' composeApp/src/androidMain/res/values/colors.xml
sed -i 's#<color name="edition_splash_background">[^<]*</color>#<color name="edition_splash_background">\#080612</color>#' composeApp/src/androidMain/res/values/colors.xml
sed -i 's#"com.nuviodebug.com"#"com.prism.app"#' androidApp/build.gradle.kts
grep -q 'com.prism.app' androidApp/build.gradle.kts

# In-app updates come from the private repo (read with the UPDATE_TOKEN key); only Prism's phone APK counts.
upd=composeApp/src/commonMain/kotlin/com/nuvio/app/features/updater/AppUpdaterRepository.kt
sed -i 's#RELEASE_ASSET_PREFIX = "Ethereal-"#RELEASE_ASSET_PREFIX = "Prism-Android-"#' "$upd"
sed -i 's#startsWith(RELEASE_ASSET_PREFIX, ignoreCase = true) }.ifEmpty { apks }#startsWith(RELEASE_ASSET_PREFIX, ignoreCase = true) }#' "$upd"
sed -i 's#api.github.com/repos/whiteak/NuvioMobile/#api.github.com/repos/whiteak/prism/#' "$upd"
perl -0pi -e 's/("User-Agent" to "NuvioMobile",\n(\s*))\),/$1) + editionAuthHeaders(),/' "$upd"
perl -0pi -e 's/(\@SerialName\("browser_download_url"\) val browserDownloadUrl: String,\n)/$1    \@SerialName("url") val apiUrl: String? = null,\n/' "$upd"
perl -0pi -e 's/assetUrl = asset\.browserDownloadUrl,/assetUrl = asset.apiUrl ?: asset.browserDownloadUrl,/' "$upd"
perl -0pi -e 's/ReleaseSelector\.eligibleReleases\(releases, channel\)/ReleaseSelector.eligibleReleases(releases.map { it.editionTagged() }, channel)/' "$upd"
# Prism releases are never marked "latest" (that's Supernatural's), so Stable also reads the release list.
sed -i 's#UpdateChannel.STABLE -> "releases/latest"#UpdateChannel.STABLE -> "releases?per_page=100"#' "$upd"
sed -i 's#UpdateChannel.STABLE -> listOf(json.decodeFromString<GitHubReleaseDto>(responseBody))#UpdateChannel.STABLE -> json.decodeFromString<List<GitHubReleaseDto>>(responseBody)#' "$upd"
grep -q 'STABLE -> "releases?per_page=100"' "$upd"; grep -q 'STABLE -> json.decodeFromString<List<GitHubReleaseDto>>' "$upd"
dl=composeApp/src/androidFull/kotlin/com/nuvio/app/features/updater/AndroidAppUpdaterPlatform.kt
perl -0pi -e 's/\.url\(assetUrl\)\n(\s*)\.build\(\)/.url(assetUrl)\n$1.apply { editionDownloadHeaders(assetUrl).forEach { (k, v) -> header(k, v) } }\n$1.build()/' "$dl"
sed -i "s#@UPDATE_TOKEN@#${UPDATE_TOKEN:-}#" composeApp/src/commonMain/kotlin/com/nuvio/app/features/updater/EditionUpdateAuth.kt
grep -q '"Prism-Android-"' "$upd"; ! grep -q 'ifEmpty { apks }' "$upd"
grep -q 'whiteak/prism/' "$upd"; grep -q 'editionAuthHeaders()' "$upd"; grep -q 'apiUrl ?: asset' "$upd"; grep -q 'editionTagged()' "$upd"; grep -q 'editionDownloadHeaders(assetUrl)' "$dl"

# Prism's phone parts (files, picker, folder, sounds) start with the app.
main=composeApp/src/androidMain/kotlin/com/nuvio/app/MainActivity.kt
perl -0pi -e 's/(\n(\s*)super\.onCreate\(savedInstanceState\)\n)/$1$2com.nuvio.app.prism.PrismAndroid.install(this)\n/' "$main"
grep -q 'PrismAndroid.install(this)' "$main"

# Customise opens over the whole app, from Settings > Appearance.
app=composeApp/src/commonMain/kotlin/com/nuvio/app/App.kt
perl -0pi -e 's/\n(\s*)EditionIntroOverlay\(\)\n/\n$1EditionIntroOverlay()\n$1com.nuvio.app.prism.PrismOverlay()\n/' "$app"
grep -q 'prism.PrismOverlay()' "$app"
appearance=composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/AppearanceSettingsPage.kt
perl -0pi -e 's/\n(    item \{\n        var showLanguageSheet)/\n    prismAppearanceItem(isTablet)\n$1/' "$appearance"
grep -q 'prismAppearanceItem(isTablet)' "$appearance"
# More app icons to choose from (Settings > Appearance > App icon), listed in icons.txt: key|ClassName|Name. Each is
# set up like Nuvio's Copper icon (its pictures come from the overlay, made by art/from_picture.py + mkoverlay.py).
res=composeApp/src/androidMain/res
activities=androidApp/src/main/kotlin/com/nuvio/app/launcher/AppIconActivities.kt
manifest=androidApp/src/main/AndroidManifest.xml
option=composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/AppIconOption.kt
iconres=composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/AppIconResources.kt
strings=composeApp/src/commonMain/composeResources/values/strings.xml
copper_activity=$(perl -0ne 'print $1 if /(\n\s*<activity\n\s*android:name="com\.nuvio\.app\.launcher\.AppIconCopper".*?<\/activity>\n)/s' "$manifest")
[ -n "$copper_activity" ]
while IFS='|' read -r key cls label; do
  [ -n "$key" ] || continue
  upper=$(echo "$key" | tr '[:lower:]' '[:upper:]')
  sed "s/copper/$key/g" "$res/mipmap-anydpi-v26/ic_launcher_copper.xml" > "$res/mipmap-anydpi-v26/ic_launcher_$key.xml"
  printf '\nclass AppIcon%s : MainActivity()\n' "$cls" >> "$activities"
  block=$(printf '%s' "$copper_activity" | sed "s/AppIconCopper/AppIcon$cls/; s/ic_launcher_copper/ic_launcher_$key/; s/Splash\.Copper/Splash.$cls/")
  BLOCK="$block" perl -0pi -e 's/(\n\s*<\/application>)/$ENV{BLOCK}$1/' "$manifest"
  sed -i "s#\(    <style name=\"Theme.Nuvio.Splash.Copper\" parent=\"@style/Theme.Nuvio.Splash\" />\)#\1\n    <style name=\"Theme.Nuvio.Splash.$cls\" parent=\"@style/Theme.Nuvio.Splash\" />#" "$res/values/themes.xml"
  perl -0pi -e "s/(\n\s*<\/resources>)/\n    <style name=\"Theme.Nuvio.Splash.$cls\" parent=\"\@style\/Theme.Nuvio.Splash\">\n        <item name=\"windowSplashScreenAnimatedIcon\">\@drawable\/ic_splash_logo_$key<\/item>\n    <\/style>\$1/" "$res/values-v31/themes.xml"
  perl -0pi -e "s/(\n\s*<\/resources>)/\n    <string name=\"settings_appearance_app_icon_$key\">$label<\/string>\$1/" "$strings"
  perl -0pi -e "s/(\n    ;\n)/\n    $upper(\n        key = \"$key\",\n        platformName = \"AppIcon$cls\",\n    ),\$1/" "$option"
  perl -0pi -e "s/(\n\s*AppIconOption\.COPPER -> Res\.string\.settings_appearance_app_icon_copper\n)/\$1        AppIconOption.$upper -> Res.string.settings_appearance_app_icon_$key\n/; s/(\n\s*AppIconOption\.COPPER -> Res\.drawable\.app_icon_copper\n)/\$1        AppIconOption.$upper -> Res.drawable.app_icon_$key\n/; s/(\n\s*AppIconOption\.COPPER -> Res\.drawable\.app_logo_wordmark_copper\n)/\$1        AppIconOption.$upper -> Res.drawable.app_logo_wordmark_original\n/" "$iconres"
  grep -q "AppIcon$cls\"" "$manifest"; grep -q "Splash.$cls\"" "$res/values/themes.xml"; grep -q "ic_splash_logo_$key" "$res/values-v31/themes.xml"
  grep -q "platformName = \"AppIcon$cls\"" "$option"; [ "$(grep -c "AppIconOption.$upper ->" "$iconres")" = 3 ]
  for f in "$res/drawable-nodpi/ic_splash_logo_$key.webp" composeApp/src/commonMain/composeResources/drawable/app_icon_$key.png "$res/mipmap-xxxhdpi/ic_launcher_${key}_foreground.webp"; do [ -f "$f" ]; done
done < "$here/icons.txt"

# Every catalog's "see all" page: sort chips under the title, and the posters in that order.
catalog=composeApp/src/commonMain/kotlin/com/nuvio/app/features/catalog/CatalogScreen.kt
perl -0pi -e 's/items = uiState\.items\.withDuplicateSafeLazyKeys/items = com.nuvio.app.prism.PrismCatalogSort.apply(target, uiState.items).withDuplicateSafeLazyKeys/' "$catalog"
grep -q 'PrismCatalogSort.apply(target, uiState.items)' "$catalog"
perl -0pi -e 's/(\n(\s*)var headerHeightPx by remember)/\n$2com.nuvio.app.prism.PrismCatalogSortEffect(target, gridState)$1/' "$catalog"
grep -q 'PrismCatalogSortEffect(target, gridState)' "$catalog"
perl -0pi -e 's/(\n(\s*)color = MaterialTheme\.colorScheme\.onSurfaceVariant,\n\s*\)\n\s*\}\n)(    \}\n\}\n\n\@Composable\nprivate fun CatalogPosterTile)/$1        com.nuvio.app.prism.PrismCatalogSortRow()\n$3/' "$catalog"
grep -q 'prism.PrismCatalogSortRow()' "$catalog"

# "Still watching?" over the player, as on the TV (Customise > 12. Sleep).
player=composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreen.kt
perl -0pi -e 's/\n    PlayerScreenContent\(\n/\n    com.nuvio.app.prism.PrismStillWatching(onStop = onBack) {\n    PlayerScreenContent(\n/; s/\n    \)\n\}\n\z/\n    )\n    }\n}\n/' "$player"
grep -q 'PrismStillWatching(onStop = onBack)' "$player"; tail -3 "$player" | grep -q '^    }$'

# Opening: the look's logo instead of the wordmark, and its quote where the spinner was (EditionLoadingArt.kt).
# No spinning circle over the opening picture, here or on the first loading screen.
shell=composeApp/src/commonMain/kotlin/com/nuvio/app/AppShellComponents.kt
perl -0pi -e 's/(\n\s*)AppBrandWordmark\((\n\s*contentDescription = stringResource\(Res\.string\.app_brand_name\),)/$1PrismWordmark($2/' "$shell"
perl -0pi -e 's/\n(\s*)NuvioLoadingIndicator\(\)\n(\s*\}\n\s*\}\n\}\n)/\n$1EditionLoadingExtras()\n$2/' "$shell"
grep -q 'PrismWordmark(' "$shell"
grep -q 'EditionLoadingExtras()' "$shell"
perl -0ne '/fun AppLoadingContent\((.*?)\n\}\n/s or exit 1; exit($1 =~ /NuvioLoadingIndicator/ ? 1 : 0)' "$shell"
gate=composeApp/src/commonMain/kotlin/com/nuvio/app/AppGate.kt
perl -0pi -e 's/(\n\s*EditionLoadingArt\(\))\n\s*NuvioLoadingIndicator\(\)\n/$1\n/' "$gate"
grep -q 'EditionLoadingArt()' "$gate"
perl -0ne 'exit(/EditionLoadingArt\(\)\n\s*NuvioLoadingIndicator/ ? 1 : 0)' "$gate"

# The look's picture behind Home.
components=composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/Components.kt
perl -0pi -e 's/(\n(\s*)\.background\(tokens\.colors\.background\),\n(\s*)contentPadding = PaddingValues\()/\n$2.background(tokens.colors.background)\n$2.then(com.nuvio.app.prism.prismBackdropModifier()),\n$3contentPadding = PaddingValues(/' "$components"
grep -q 'prismBackdropModifier()' "$components"
home=composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/HomeScreen.kt
perl -0pi -e 's/\n(\s*)NuvioScreen\(\n(\s*)modifier = Modifier\.fillMaxSize\(\)\.then\(heroStretchModifier\),/\n$1com.nuvio.app.prism.PrismHomeScreen(\n$2modifier = Modifier.fillMaxSize().then(heroStretchModifier),/' "$home"
grep -q 'prism.PrismHomeScreen(' "$home"

# The favourite show card at the top of Home, and the look's own rows above the addon rows.
perl -0pi -e 's/(\n(\s*)when \{\n\s*isInitialHomeContentLoading -> \{)/\n$2prismHomeHero()\n$2prismHomeShowCard(continueWatchingItems, onPosterClick, onContinueWatchingClick)\n$1/' "$home"
perl -0pi -e 's/\n([ \t]*)(keyedEnabledHomeItems\.forEach \{ keyedSettingsItem ->)/\n$1prismHomeRows(homeSectionPadding, watchedUiState.watchedKeys, fullyWatchedSeriesKeys, onPosterClick, onPosterLongClick)\n\n$1$2/' "$home"
[ "$(grep -c 'prismHomeShowCard(continueWatchingItems' "$home")" = 1 ]
[ "$(grep -c 'prismHomeRows(homeSectionPadding' "$home")" = 1 ]
grep -q 'prismHomeHero()' "$home"

# Profile photos chosen in Customise win over the account's avatar.
models=composeApp/src/commonMain/kotlin/com/nuvio/app/features/profiles/ProfileModels.kt
perl -0pi -e 's/(fun profileAvatarImageUrl\(profile: NuvioProfile, avatar: AvatarCatalogItem\?\): String\? =\n(\s*))normalizedAvatarUrl\(profile\.avatarUrl\)/$1com.nuvio.app.prism.prismAvatarUrl(profile)\n$2    ?: normalizedAvatarUrl(profile.avatarUrl)/' "$models"
grep -q 'prism.prismAvatarUrl(profile)' "$models"

# Sounds for moments: a profile picked, a search with no results, a title with nothing to play.
gate=composeApp/src/commonMain/kotlin/com/nuvio/app/AppGate.kt
perl -0pi -e 's/(ProfileSelectionScreen\(\n\s*onProfileSelected = \{ profile ->\n)/$1                        com.nuvio.app.prism.PrismSounds.play(com.nuvio.app.prism.PrismMoment.Profile)\n/' "$gate"
grep -q 'PrismMoment.Profile' "$gate"
search=composeApp/src/commonMain/kotlin/com/nuvio/app/features/search/SearchScreen.kt
perl -0pi -e 's/(SearchEmptyStateReason\.NoResults, null -> \{\n)(\s*)/$1$2androidx.compose.runtime.LaunchedEffect(Unit) { com.nuvio.app.prism.PrismSounds.play(com.nuvio.app.prism.PrismMoment.NoResults) }\n$2/' "$search"
grep -q 'PrismMoment.NoResults' "$search"
streams=composeApp/src/commonMain/kotlin/com/nuvio/app/features/streams/StreamsScreen.kt
perl -0pi -e 's/(private fun EmptyStateBlock\(\n\s*reason: StreamsEmptyStateReason\?,\n\s*modifier: Modifier = Modifier,\n\) \{\n)/$1    androidx.compose.runtime.LaunchedEffect(reason) {\n        if (reason == StreamsEmptyStateReason.StreamFetchFailed || reason == StreamsEmptyStateReason.NoStreamsFound) com.nuvio.app.prism.PrismSounds.play(com.nuvio.app.prism.PrismMoment.StreamFailed)\n    }\n/' "$streams"
grep -q 'PrismMoment.StreamFailed' "$streams"

# Trakt sign-in with PKCE (same as the Supernatural edition): new Trakt apps only issue a Client ID.
tauth=composeApp/src/commonMain/kotlin/com/nuvio/app/features/trakt/TraktAuthRepository.kt
tmodels=composeApp/src/commonMain/kotlin/com/nuvio/app/features/trakt/TraktModels.kt
perl -0pi -e 's/(    val pendingAuthorizationStartedAtMillis: Long\? = null,\n)/$1    val pendingCodeVerifier: String? = null,\n/' "$tmodels"
perl -0pi -e 's/TraktConfig\.CLIENT_ID\.isNotBlank\(\) && TraktConfig\.CLIENT_SECRET\.isNotBlank\(\)/TraktConfig.CLIENT_ID.isNotBlank()/' "$tauth"
perl -0pi -e 's/(        encodeDefaults = true\n)/$1        explicitNulls = false\n/' "$tauth"
perl -0pi -e 's/val oauthState = generateOauthState\(\)\n(\s*)authState = authState\.copy\(\n(\s*)pendingAuthorizationState = oauthState,/val pkce = com.nuvio.app.features.simkl.generateSimklPkceMaterial()\n$1val oauthState = pkce.state\n$1authState = authState.copy(\n$2pendingAuthorizationState = oauthState,\n$2pendingCodeVerifier = pkce.verifier,/' "$tauth"
perl -0pi -e 's/(\n(\s*)pendingAuthorizationStartedAtMillis = null,\n(\s*)\)\n(\s*)\}\n\n    private fun publish\()/\n$2pendingAuthorizationStartedAtMillis = null,\n$2pendingCodeVerifier = null,\n$3)\n$4}\n\n    private fun publish(/' "$tauth"
perl -0pi -e 's/(\n(\s*)pendingAuthorizationStartedAtMillis = null,\n)(\s*\)\n\s*persist\(profileId\)\n\s*refreshUserSettings)/$1$2pendingCodeVerifier = null,\n$3/' "$tauth"
perl -0pi -e 's/clientSecret = TraktConfig\.CLIENT_SECRET,/clientSecret = TraktConfig.CLIENT_SECRET.ifBlank { null },/g' "$tauth"
perl -0pi -e 's/(clientSecret = TraktConfig\.CLIENT_SECRET\.ifBlank \{ null \},\n(\s*)redirectUri = TraktConfig\.REDIRECT_URI,\n(\s*)\),\n(\s*)\)\n\n(\s*)val response = runCatching \{\n(\s*)httpPostJsonWithHeaders)/clientSecret = TraktConfig.CLIENT_SECRET.ifBlank { null },\n$2redirectUri = TraktConfig.REDIRECT_URI,\n$2codeVerifier = authState.pendingCodeVerifier,\n$3),\n$4)\n\n$5val response = runCatching {\n$6httpPostJsonWithHeaders/' "$tauth"
perl -0pi -e 's/\@SerialName\("client_secret"\) val clientSecret: String,/\@SerialName("client_secret") val clientSecret: String? = null,/g' "$tauth"
perl -0pi -e 's/(\@SerialName\("redirect_uri"\) val redirectUri: String,\n)(    \@SerialName\("grant_type"\) val grantType: String = "authorization_code",)/$1    \@SerialName("code_verifier") val codeVerifier: String? = null,\n$2/' "$tauth"
perl -0pi -e 's/(&state=\$encodedState)"/$1" + editionTraktChallengeParams(authState.pendingCodeVerifier)/' "$tauth"
perl -0pi -e 's/url == TraktConfig\.REDIRECT_URI \|\| url\.startsWith\("\$\{TraktConfig\.REDIRECT_URI\}\?"\)/url == TraktConfig.REDIRECT_URI || url.startsWith("\${TraktConfig.REDIRECT_URI}?") || url.startsWith("nuvio:\/\/auth\/trakt?", ignoreCase = true)/' "$tauth"
perl -0pi -e 's/(!callbackUrl\.equals\(TraktConfig\.REDIRECT_URI, ignoreCase = true\))/$1 \&\&\n            !callbackUrl.startsWith("nuvio:\/\/auth\/trakt?", ignoreCase = true)/' "$tauth"
grep -q 'pendingCodeVerifier: String? = null' "$tmodels"
grep -q 'pendingCodeVerifier = pkce.verifier' "$tauth"
grep -q 'codeVerifier = authState.pendingCodeVerifier' "$tauth"
grep -q 'editionTraktChallengeParams(authState.pendingCodeVerifier)' "$tauth"
[ "$(grep -c 'startsWith("nuvio://auth/trakt?", ignoreCase = true)' "$tauth")" = 2 ]
! grep -q 'clientSecret: String,' "$tauth"
# Library sent from a TV as QR codes (see EditionLibraryImport.kt); the return page passes it on as nuvio://auth/trakt?library=...
bridge=composeApp/src/commonMain/kotlin/com/nuvio/app/core/deeplink/AppUrlBridge.kt
perl -0pi -e 's/(\n([ \t]*)ensureTrackingProvidersRegistered\(\)\n(\s*)TrackingProviderRegistry\.handleAuthCallback\(normalizedUrl\))/\n$2if (com.nuvio.app.EditionLibraryImport.handle(normalizedUrl)) return\n$1/' "$bridge"
grep -q 'EditionLibraryImport.handle(normalizedUrl)' "$bridge"
# Long-press on a poster also offers "Remove from <row>" for your own Home rows.
main=composeApp/src/commonMain/kotlin/com/nuvio/app/MainAppContent.kt
perl -0pi -e 's/(\n\s*\),)\n(\s*)\),\n(\s*hazeState = posterOverlayHazeState,)/$1\n$2) + com.nuvio.app.prism.prismPosterActions(preview),\n$3/' "$main"
[ "$(grep -c 'prismPosterActions(preview)' "$main")" = 1 ]
# The look's fonts (headings and everyday text) and text size, for the whole app.
theme=composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/Theme.kt
perl -0pi -e 's/(LocalDensity provides Density\(\n\s*density = density\.density,\n\s*)fontScale = 1f,/${1}fontScale = com.nuvio.app.prism.PrismFonts.scale(),/; s/LocalNuvioTypeScale provides NuvioTypeTokens,/LocalNuvioTypeScale provides com.nuvio.app.prism.PrismFonts.typeScale(NuvioTypeTokens),/; s/typography = NuvioTypography,/typography = com.nuvio.app.prism.PrismFonts.typography(NuvioTypography),/' "$theme"
grep -q 'PrismFonts.scale()' "$theme"; grep -q 'PrismFonts.typeScale(NuvioTypeTokens)' "$theme"; grep -q 'PrismFonts.typography(NuvioTypography)' "$theme"
# Shapes: the look's roundness for buttons, cards, panels and sheets.
perl -0pi -e 's/val tokens = defaultNuvioThemeTokens\(palette, amoled = amoled, colorScheme = colorScheme\)/val tokens = com.nuvio.app.prism.PrismShapes.apply(defaultNuvioThemeTokens(palette, amoled = amoled, colorScheme = colorScheme))/' "$theme"
grep -q 'PrismShapes.apply(defaultNuvioThemeTokens' "$theme"
# More sound moments: added to / removed from Library, play pressed, finished watching.
lib=composeApp/src/commonMain/kotlin/com/nuvio/app/features/library/LibraryRepository.kt
perl -0pi -e 's/(
(\s*)pushToServer\(result\.snapshot\)\n\s*return TrackingMembershipApplyResult\(\)\n)/\n$2com.nuvio.app.prism.PrismSounds.play(if (result.isSaved) com.nuvio.app.prism.PrismMoment.AddedToLibrary else com.nuvio.app.prism.PrismMoment.Removed)$1/' "$lib"
perl -0pi -e 's/(fun remove\(id: String\) \{\n\s*ensureLoaded\(\)\n\s*val result = localState\.removeById\(id\)\n(\s*)if \(result\.affectedCount > 0\) \{\n)/$1$2    com.nuvio.app.prism.PrismSounds.play(com.nuvio.app.prism.PrismMoment.Removed)\n/' "$lib"
grep -q 'PrismMoment.AddedToLibrary else' "$lib"; [ "$(grep -c 'PrismMoment.Removed' "$lib")" = 2 ]
player=composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreen.kt
perl -0pi -e 's/(\nfun PlayerScreen\((?:.*\n)*?\) \{\n)/$1    androidx.compose.runtime.LaunchedEffect(Unit) { com.nuvio.app.prism.PrismSounds.play(com.nuvio.app.prism.PrismMoment.PlayStarting) }\n/' "$player"
grep -q 'PrismMoment.PlayStarting' "$player"
progress=composeApp/src/commonMain/kotlin/com/nuvio/app/features/watchprogress/WatchProgressRepository.kt
perl -0pi -e 's/(\n(\s*)val isCompleted = isWatchProgressComplete\(\n\s*positionMs = positionMs,\n\s*durationMs = durationMs,\n\s*isEnded = snapshot\.isEnded,\n\s*\)\n)/$1$2if (isCompleted) com.nuvio.app.prism.PrismSounds.finished(session.videoId)\n/' "$progress"
grep -q 'PrismSounds.finished(session.videoId)' "$progress"
# No accounts in Prism (everything stays on this device): skip the sign-in screen and go straight in.
perl -0pi -e 's/(AppGateScreen\.Auth\.name -> \{\n)(\s*)AuthScreen\(\n\s*modifier = (Modifier\n(?:.*\n)*?\s*\.padding\(LocalVerticalBarPadding\.current\)),\n\s*\)\n/$1$2androidx.compose.runtime.LaunchedEffect(Unit) { com.nuvio.app.core.auth.AuthRepository.signInAnonymously() }\n$2Box(modifier = $3)\n/' "$gate"
grep -q 'AuthRepository.signInAnonymously() }' "$gate"
# The clip maker (PrismClipMaker.kt): Media3 Transformer cuts videos into silent H.265 clips or sound bites on the phone.
# Same Media3 version as the app's own player libraries (those come from composeApp/libs).
build=composeApp/build.gradle.kts
perl -0pi -e 's/(\n(\s*)implementation\(libs\.androidx\.media3\.extractor\)\n)/$1$2implementation("androidx.media3:media3-transformer:1.8.0")\n$2implementation("androidx.media3:media3-effect:1.8.0")\n$2implementation("androidx.media3:media3-muxer:1.8.0")\n/' "$build"
[ "$(grep -c 'media3-transformer:1.8.0' "$build")" = 1 ]
manifest=composeApp/src/androidMain/AndroidManifest.xml
perl -0pi -e 's#(\n\s*</application>)#\n        <activity\n            android:name="com.nuvio.app.prism.PrismClipActivity"\n            android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden"\n            android:exported="false" />$1#' "$manifest"
grep -q 'prism.PrismClipActivity' "$manifest"
# Account (Nuvio sign-in) and Watch party need Nuvio's own servers, so they don't work in Prism: both come out of
# Settings, on the phone and the PC. Switch profile and Tracking stay.
root=composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsRootPage.kt
perl -0pi -e 's/\n\s*SettingsNavigationRow\(\n\s*title = stringResource\(Res\.string\.compose_settings_page_account\),.*?\n(\s*)SettingsGroupDivider\(isTablet = isTablet\)\n(\s*SettingsNavigationRow\(\n\s*title = stringResource\(Res\.string\.compose_settings_page_tracking\))/\n$2/s' "$root"
! grep -q 'compose_settings_page_account),' "$root"; ! grep -q 'Res.string.party_title' "$root"; grep -q 'compose_settings_page_tracking' "$root"
echo "Prism applied (phone)."
