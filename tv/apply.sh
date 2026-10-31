#!/bin/bash
# Turns a NuvioTV (Ethereal) checkout into Prism for the TV.
# The look is made on the phone and loaded here from a file (PrismTv.kt); see README.md.
# usage: tv/apply.sh <path to NuvioTV checkout>
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
cd "$1"
cp -R "$here/overlay/." .
grep -rlZ 'name="app_name"' app/src --include='*.xml' | xargs -0 sed -i 's#<string name="app_name">[^<]*</string>#<string name="app_name">Prism</string>#'
sed -i 's#<color name="splash_background">[^<]*</color>#<color name="splash_background">\#080612</color>#' app/src/main/res/values/colors.xml
sed -i 's#[A-Za-z ]* · built on Nuvio#Prism · built on Nuvio#' app/src/main/res/values/strings.xml
sed -i 's#"com.nuviodebug.com"#"com.prism.tv"#' app/build.gradle.kts
grep -q 'com.prism.tv' app/build.gradle.kts

# In-app updates come from the private repo (read with the UPDATE_TOKEN key); only Prism's TV APK counts.
sed -i 's#RELEASE_ASSET_PREFIX = "Ethereal-"#RELEASE_ASSET_PREFIX = "Prism-TV-"#' app/src/full/java/com/nuvio/tv/updater/AbiSelector.kt
grep -q '"Prism-TV-"' app/src/full/java/com/nuvio/tv/updater/AbiSelector.kt
sed -i 's#"GITHUB_REPO", "\\"NuvioTV\\""#"GITHUB_REPO", "\\"prism\\""#' app/build.gradle.kts
grep -q '"GITHUB_REPO", "\\"prism\\""' app/build.gradle.kts
perl -0pi -e 's/(\.baseUrl\("https:\/\/api\.github\.com\/"\)\n\s*)\.client\(okHttpClient\)/$1.client(com.nuvio.tv.updater.editionGitHubClient(okHttpClient))/' app/src/main/java/com/nuvio/tv/core/di/NetworkModule.kt
grep -q 'editionGitHubClient(okHttpClient)' app/src/main/java/com/nuvio/tv/core/di/NetworkModule.kt
dto=app/src/main/java/com/nuvio/tv/data/remote/dto/GitHubReleaseDto.kt
perl -0pi -e 's/(\@Json\(name = "browser_download_url"\) val browserDownloadUrl: String,\n)/$1    \@Json(name = "url") val apiUrl: String? = null,\n/' "$dto"
grep -q 'val apiUrl' "$dto"
repo=app/src/full/java/com/nuvio/tv/updater/UpdateRepository.kt
perl -0pi -e 's/\.eligibleReleases\(releases, channel\)/.eligibleReleases(releases.map { it.editionTagged() }, channel)/; s/assetUrl = asset\.browserDownloadUrl,/assetUrl = asset.apiUrl ?: asset.browserDownloadUrl,/' "$repo"
grep -q 'editionTagged()' "$repo"; grep -q 'apiUrl ?: asset' "$repo"
# Prism releases are never marked "latest" (that's Supernatural's), so Stable also reads the release list.
perl -0pi -e 's/val response = gitHubReleaseApi\.getLatestRelease\(owner = owner, repo = repo\)\n(\s*)if \(!response\.isSuccessful\) \{\n(\s*)error\("GitHub API error: \$\{response\.code\(\)\}"\)\n(\s*)\}\n(\s*)listOf\(response\.body\(\) \?: error\("Empty GitHub release response"\)\)/val response = gitHubReleaseApi.getReleases(owner = owner, repo = repo)\n$1if (!response.isSuccessful) {\n$2error("GitHub API error: \${response.code()}")\n$3}\n$4response.body() ?: error("Empty GitHub release response")/' "$repo"
! grep -q 'getLatestRelease(' "$repo"
dl=app/src/full/java/com/nuvio/tv/updater/ApkDownloader.kt
perl -0pi -e 's/\.url\(url\)\n(\s*)\.build\(\)/.url(url)\n$1.apply { editionDownloadHeaders(url).forEach { (k, v) -> header(k, v) } }\n$1.build()/' "$dl"
grep -q 'editionDownloadHeaders(url)' "$dl"
sed -i "s#@UPDATE_TOKEN@#${UPDATE_TOKEN:-}#" app/src/main/java/com/nuvio/tv/updater/EditionUpdateAuth.kt

# The opening: the look's picture (or Prism's art) with glints, its logo and quote, held for its seconds.
f=app/src/main/java/com/nuvio/tv/ui/components/StartupSplashScreen.kt
perl -0pi -e 's/Image\(\n\s*painter = painterResource\(id = R\.drawable\.edition_loading_art\),\n\s*contentDescription = null,\n\s*modifier = Modifier\.fillMaxSize\(\),\n\s*contentScale = ContentScale\.Crop\n(\s*)\)/PrismTvOpening()/' "$f"
perl -0pi -e 's/\bBrandWordmark\((\n\s*modifier = Modifier\.height\(48\.dp\),)/PrismTvWordmark($1/' "$f"
perl -0pi -e 's/LoadingIndicator\(modifier = Modifier\.size\(NuvioTheme\.spacing\.xxxl\)\)/PrismTvLoadingExtras()/' "$f"
grep -q 'PrismTvOpening()' "$f"; grep -q 'PrismTvWordmark(' "$f"; grep -q 'PrismTvLoadingExtras()' "$f"
# No sign-in screen on first start (no accounts; everything comes from the phone over Wi-Fi, PrismTvSync).
onboarding=app/src/main/java/com/nuvio/tv/data/local/AppOnboardingDataStore.kt
sed -i 's/prefs\[hasSeenAuthQrOnFirstLaunchKey\] ?: false/prefs[hasSeenAuthQrOnFirstLaunchKey] ?: true/' "$onboarding"
grep -q 'prefs\[hasSeenAuthQrOnFirstLaunchKey\] ?: true' "$onboarding"
# Settings: no Account or Watch party (both need a Nuvio account, which Prism doesn't use).
catalog=app/src/main/java/com/nuvio/tv/ui/screens/settings/SettingsCatalog.kt
perl -0pi -e 's/SettingsCategory\.ACCOUNT,\n(\s*)SettingsCategory\.PROFILES -> isPrimaryProfile/SettingsCategory.ACCOUNT -> false\n$1SettingsCategory.PROFILES -> isPrimaryProfile/; s/SettingsCategory\.WATCH_PARTY -> isWatchPartyAvailable/SettingsCategory.WATCH_PARTY -> false/' "$catalog"
grep -q 'SettingsCategory.ACCOUNT -> false' "$catalog"; grep -q 'SettingsCategory.WATCH_PARTY -> false' "$catalog"
main=app/src/main/java/com/nuvio/tv/MainActivity.kt
perl -0pi -e 's/(val showSplash = shouldShowStartupSplash\(\n\s*enabled = startupSplashEnabled,\n\s*complete = startupLoadingState\.complete,\n\s*destination = startupDestination\n\s*\))/$1 || com.nuvio.tv.ui.components.PrismTvIntro.holding/' "$main"
perl -0pi -e 's/(override fun dispatchKeyEvent\(event: KeyEvent\): Boolean \{\n)/$1        if (com.nuvio.tv.ui.components.PrismTvIntro.skipOnKey(event)) return true\n/' "$main"
grep -q 'PrismTvIntro.holding' "$main"; grep -q 'PrismTvIntro.skipOnKey(event)' "$main"
player=app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerScreen.kt
perl -0pi -e 's/(\n(\s*)val uiState by viewModel\.uiState\.collectAsState\(\)\n)/$1$2androidx.compose.runtime.LaunchedEffect(Unit) { com.nuvio.tv.ui.components.PrismTvSounds.stop() }\n/' "$player"
grep -q 'PrismTvSounds.stop()' "$player"

# "Play random episode" in the side menu: one of the look's show cards, its sound, then a random episode.
perl -0pi -e 's/(\n(\s*)add\(\n\s*DrawerItem\(\n\s*route = Screen\.Home\.route,\n\s*label = strNavHome,\n\s*icon = Icons\.Default\.Home\n\s*\)\n\s*\)\n)/$1$2add(DrawerItem(route = com.nuvio.tv.ui.components.PRISM_RANDOM_ROUTE, label = "Play random episode", icon = com.nuvio.tv.ui.components.prismRandomIcon))\n/' "$main"
perl -0pi -e 's/(private fun navigateToDrawerRoute\(\n\s*navController: NavHostController,\n\s*currentRoute: String\?,\n\s*targetRoute: String\n\) \{\n)/$1    if (targetRoute == com.nuvio.tv.ui.components.PRISM_RANDOM_ROUTE) {\n        com.nuvio.tv.ui.components.PrismTvRandom.go(navController.context) { navigateToDrawerRoute(navController, null, it) }\n        return\n    }\n/' "$main"
grep -q 'PRISM_RANDOM_ROUTE, label = "Play random episode"' "$main"; grep -q 'PrismTvRandom.go(navController.context)' "$main"
# Sleep: "Still watching?" over playback, and the screensaver (Nuvio's Ambient Mode) given an end so the TV can sleep.
perl -0pi -e 's/(\n(\s*)if \(ambientIdleController\.consumeKeyEvent\(event\)\) return true\n)/\n$2if (com.nuvio.tv.ui.components.PrismTvSleep.onKey(event)) {\n$2    ambientIdleController.dismiss()\n$2    return true\n$2}$1/' "$main"
perl -0pi -e 's/(\n(\s*)AmbientModeHost\(\n(?:.*\n)*?\s*onShown = \{ trailerPlayerPool\.stop\(\) \}\n\s*\)\n)/$1$2com.nuvio.tv.ui.components.PrismTvSleepLayer(\n$2    playing = currentRoute?.startsWith("player\/") == true,\n$2    onStop = {\n$2        navController.navigate(Screen.Home.route) {\n$2            popUpTo(navController.graph.startDestinationId) { saveState = false }\n$2            launchSingleTop = true\n$2        }\n$2    }\n$2)\n/' "$main"
grep -q 'PrismTvSleep.onKey(event)' "$main"; grep -q 'PrismTvSleepLayer(' "$main"
ambient=app/src/main/java/com/nuvio/tv/ui/screens/ambient/AmbientModeHost.kt
perl -0pi -e 's/if \(isShowing\) \{\n\s*val view = LocalView\.current\n\s*DisposableEffect\(view\) \{\n\s*view\.keepScreenOn = true\n\s*onDispose \{ view\.keepScreenOn = false \}\n\s*\}\n(\s*)\}/if (isShowing) com.nuvio.tv.ui.components.PrismTvKeepAwake()/' "$ambient"
perl -0pi -e 's/AmbientSlideshow\(\n\s*slides = slides,\n\s*modifier = (Modifier(?:.|\n)*?\{ controller\.dismiss\(\) \} \})\n\s*\)/com.nuvio.tv.ui.components.PrismTvScreensaver(modifier = $1) { AmbientSlideshow(slides = slides, modifier = it) }/' "$ambient"
grep -q 'PrismTvKeepAwake()' "$ambient"; grep -q 'PrismTvScreensaver(modifier' "$ambient"; ! grep -q 'keepScreenOn = true' "$ambient"

# Settings > Appearance: load a look or library from the phone, and the sounds switch.
theme=app/src/main/java/com/nuvio/tv/ui/screens/settings/ThemeSettingsScreen.kt
perl -0pi -e 's/(ThemeSettingsEvent\.ToggleAmoledSurfacesMode\(!uiState\.amoledSurfacesMode\)\n\s*\)\n\s*\}\n\s*\)\n(\s*)\}\n)/$1$2PrismTvSettings()\n/' "$theme"
grep -q 'PrismTvSettings()' "$theme"

# The look's background behind Home.
home=app/src/main/java/com/nuvio/tv/ui/screens/home
perl -0pi -e 's/Box\(modifier = Modifier\.fillMaxSize\(\)\.background\(NuvioTheme\.colors\.Background\)\)/Box(modifier = Modifier.fillMaxSize().background(NuvioTheme.colors.Background).then(Modifier.prismBackdropHere()))/' "$home/ModernHomeContent.kt" "$home/GridHomeContent.kt"
perl -0pi -e 's/(\n(\s*)\.fillMaxSize\(\)\n\s*\.background\(backgroundColor\)\n)/\n$2.fillMaxSize()\n$2.background(backgroundColor)\n$2.then(Modifier.prismBackdropHere())\n/' "$home/ClassicHomeContent.kt"
for k in Modern Grid Classic; do
  f2="$home/${k}HomeContent.kt"
  sed -i 's/Modifier\.prismBackdropHere()/Modifier.prismBackdrop()/' "$f2"
  sed -i '0,/^import /s//import com.nuvio.tv.ui.components.prismBackdrop\nimport /' "$f2"
  grep -q 'then(Modifier.prismBackdrop())' "$f2"
done
# The look's hero clips, playing silently behind Home (over the background picture, under the rows).
perl -0pi -e 's/(\.then\(Modifier\.prismBackdrop\(\)\)(?:\)|\n\s*\)) \{\n)/$1        com.nuvio.tv.ui.components.PrismTvHeroClips()\n/' "$home/ModernHomeContent.kt" "$home/GridHomeContent.kt" "$home/ClassicHomeContent.kt"
for k in Modern Grid Classic; do grep -q 'PrismTvHeroClips()' "$home/${k}HomeContent.kt"; done

# Sounds: picking a profile, an empty search, nothing to play.
prof=app/src/main/java/com/nuvio/tv/ui/screens/profile/ProfileSelectionScreen.kt
perl -0pi -e 's/(\} else \{\n(\s*))selectProfile\(profile\.id\)/$1com.nuvio.tv.ui.components.PrismTvSounds.play(context, "Profile")\n$2selectProfile(profile.id)/' "$prof"
grep -q 'PrismTvSounds.play(context, "Profile")' "$prof"
search=app/src/main/java/com/nuvio/tv/ui/screens/search/SearchScreen.kt
perl -0pi -e 's/(item \{\n(\s*))(EmptyScreenState\(\n\s*title = stringResource\(R\.string\.search_no_results_title\))/$1run { val prismContext = androidx.compose.ui.platform.LocalContext.current; androidx.compose.runtime.LaunchedEffect(Unit) { com.nuvio.tv.ui.components.PrismTvSounds.play(prismContext, "NoResults") } }\n$2$3/' "$search"
grep -q 'PrismTvSounds.play(prismContext, "NoResults")' "$search"
stream=app/src/main/java/com/nuvio/tv/ui/screens/stream/StreamScreen.kt
perl -0pi -e 's/(private fun ErrorState\(\n\s*message: String,\n\s*onRetry: \(\) -> Unit\n\) \{\n)/$1    val prismContext = androidx.compose.ui.platform.LocalContext.current\n    androidx.compose.runtime.LaunchedEffect(Unit) { com.nuvio.tv.ui.components.PrismTvSounds.play(prismContext, "StreamFailed") }\n/; s/(private fun EmptyState\(\) \{\n)/$1    val prismContext = androidx.compose.ui.platform.LocalContext.current\n    androidx.compose.runtime.LaunchedEffect(Unit) { com.nuvio.tv.ui.components.PrismTvSounds.play(prismContext, "StreamFailed") }\n/' "$stream"
[ "$(grep -c 'PrismTvSounds.play(prismContext, "StreamFailed")' "$stream")" = 2 ]

# Trakt sign-in with PKCE: new Trakt apps only issue a Client ID, so the secret is optional and the code flow carries a verifier.
tsvc=app/src/main/java/com/nuvio/tv/data/repository/TraktAuthService.kt
tdto=app/src/main/java/com/nuvio/tv/data/remote/dto/trakt/TraktAuthDtos.kt
perl -0pi -e 's/BuildConfig\.TRAKT_CLIENT_ID\.isNotBlank\(\) && BuildConfig\.TRAKT_CLIENT_SECRET\.isNotBlank\(\)/BuildConfig.TRAKT_CLIENT_ID.isNotBlank()/' "$tsvc"
perl -0pi -e 's/clientSecret = BuildConfig\.TRAKT_CLIENT_SECRET(,?)\n/clientSecret = BuildConfig.TRAKT_CLIENT_SECRET.ifBlank { null }$1\n/g' "$tsvc"
perl -0pi -e 's/TraktDeviceCodeRequestDto\(clientId = BuildConfig\.TRAKT_CLIENT_ID\)/TraktDeviceCodeRequestDto(clientId = BuildConfig.TRAKT_CLIENT_ID, codeChallenge = EditionTraktPkce.newChallenge(context), codeChallengeMethod = "S256")/' "$tsvc"
perl -0pi -e 's/(clientSecret = BuildConfig\.TRAKT_CLIENT_SECRET\.ifBlank \{ null \})(\n\s*\)\n\s*\)\n\s*\} catch \(e: IOException\) \{\n\s*return TraktTokenPollResult)/$1,\n                    codeVerifier = EditionTraktPkce.verifier(context)$2/' "$tsvc"
perl -0pi -e 's/\@Json\(name = "client_secret"\) val clientSecret: String/\@Json(name = "client_secret") val clientSecret: String? = null/g' "$tdto"
perl -0pi -e 's/(data class TraktDeviceCodeRequestDto\(\n    \@Json\(name = "client_id"\) val clientId: String)\n/$1,\n    \@Json(name = "code_challenge") val codeChallenge: String? = null,\n    \@Json(name = "code_challenge_method") val codeChallengeMethod: String? = null\n/' "$tdto"
perl -0pi -e 's/(data class TraktDeviceTokenRequestDto\(\n(?:.*\n)*?    \@Json\(name = "client_secret"\) val clientSecret: String\? = null)\n/$1,\n    \@Json(name = "code_verifier") val codeVerifier: String? = null\n/' "$tdto"
grep -q 'codeChallenge = EditionTraktPkce.newChallenge(context)' "$tsvc"
grep -q 'codeVerifier = EditionTraktPkce.verifier(context)' "$tsvc"
[ "$(grep -c 'TRAKT_CLIENT_SECRET.ifBlank { null }' "$tsvc")" = 3 ]
grep -q 'val codeVerifier: String? = null' "$tdto"; grep -q 'val codeChallengeMethod: String? = null' "$tdto"

# Avatars stay in the picker even when Nuvio's own avatar list can't be loaded.
avrepo=app/src/main/java/com/nuvio/tv/data/remote/supabase/AvatarRepository.kt
perl -0pi -e 's/val standardCatalog = getStandardAvatarCatalog\(\)/val standardCatalog = try { getStandardAvatarCatalog() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }/; s/val memberCatalog = getMemberAvatarCatalog\(\)/val memberCatalog = try { getMemberAvatarCatalog() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }/' "$avrepo"
grep -q 'try { getStandardAvatarCatalog() }' "$avrepo"

# "Send to phone" in the Library (Nuvio Library only): QR codes the phone app reads, see EditionLibraryShare.kt.
libscreen=app/src/main/java/com/nuvio/tv/ui/screens/library/LibraryScreen.kt
perl -0pi -e 's/\n([ \t]*)(if \(uiState\.listManagement != null && uiState\.isTrackingAuthenticated\) \{)/\n$1if (uiState.sourceMode == com.nuvio.tv.domain.model.LibrarySourceMode.LOCAL && uiState.allItems.isNotEmpty()) {\n$1    item(span = { GridItemSpan(maxLineSpan) }) {\n$1        com.nuvio.tv.ui.components.EditionSendToPhone(uiState.allItems)\n$1    }\n$1}\n\n$1$2/' "$libscreen"
grep -q 'EditionSendToPhone(uiState.allItems)' "$libscreen"
# The look's own Home rows, first on Home in every layout (PrismTvRows.kt).
hvm=app/src/main/java/com/nuvio/tv/ui/screens/home/HomeViewModel.kt
hpipe=app/src/main/java/com/nuvio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt
perl -0pi -e 's/\n    init \{\n/\n    init {\n        com.nuvio.tv.ui.components.PrismTvRows.start(viewModelScope) { scheduleUpdateCatalogRows() }\n/' "$hvm"
perl -0pi -e 's/val \(orderedKeys, catalogSnapshot\) = snapshotCatalogState\(\)/val (orderedKeys, catalogSnapshot) = com.nuvio.tv.ui.components.PrismTvRows.inject(snapshotCatalogState())/' "$hpipe"
[ "$(grep -c 'PrismTvRows.start(viewModelScope)' "$hvm")" = 1 ]
grep -q 'PrismTvRows.inject(snapshotCatalogState())' "$hpipe"
# Sort on every catalog's "see all" page, remembered per catalog, as on the phone.
seeall=app/src/main/java/com/nuvio/tv/ui/screens/CatalogSeeAllScreen.kt
perl -0pi -e 's/(\n\s*val catalogRow = when \{\n\s*serverCatalog != null -> serverRow\?\.value\n\s*isSearchMode -> searchCatalogRow\n\s*else -> homeCatalogRow\n\s*\})/$1.let { com.nuvio.tv.ui.components.PrismTvCatalogSort.apply(catalogKey, it) }/' "$seeall"
perl -0pi -e 's/(\n(\s*)val gridState = rememberLazyGridState\(\)\n)/$1$2com.nuvio.tv.ui.components.PrismTvCatalogSort.Effect(catalogKey, gridState)\n/' "$seeall"
perl -0pi -e 's/(\n(\s*)text = catalogRow\?\.catalogName \?: stringResource\(R\.string\.catalog_see_all_title_fallback\),\n\s*style = MaterialTheme\.typography\.headlineLarge,\n\s*color = NuvioTheme\.colors\.TextPrimary\n\s*\)\n)/$1            Spacer(modifier = Modifier.weight(1f))\n            com.nuvio.tv.ui.components.PrismTvCatalogSort.SortButton(catalogKey)\n/' "$seeall"
grep -q 'PrismTvCatalogSort.apply(catalogKey, it)' "$seeall"; grep -q 'PrismTvCatalogSort.Effect(catalogKey, gridState)' "$seeall"; grep -q 'PrismTvCatalogSort.SortButton(catalogKey)' "$seeall"

# Profile photos chosen in Customise win over the account's avatar (profile picker, side menu, top bar).
psel=app/src/main/java/com/nuvio/tv/ui/screens/profile/ProfileSelectionScreen.kt
perl -0pi -e 's/(\n\s*profile = profile,\n\s*avatarImageUrl = )(profile\.avatarUrl\?\.takeIf \{ it\.isNotBlank\(\) \})/$1com.nuvio.tv.ui.components.PrismTvAvatars.url(profile.id)\n                            ?: $2/' "$psel"
grep -q 'PrismTvAvatars.url(profile.id)' "$psel"
perl -0pi -e 's/val activeProfileAvatarImageUrl = remember\(activeProfile, avatarCatalog\) \{/val activeProfileAvatarImageUrl = com.nuvio.tv.ui.components.PrismTvAvatars.url(activeProfile?.id) ?: remember(activeProfile, avatarCatalog) {/' "$main"
grep -q 'PrismTvAvatars.url(activeProfile?.id)' "$main"

# The look's fonts (headings and everyday text) and text size.
tt=app/src/main/java/com/nuvio/tv/ui/theme/Theme.kt
perl -0pi -e 's/val typography = buildNuvioTypography\(getFontFamily\(appFont\)\)/val typography = com.nuvio.tv.ui.components.prismTvTypography(getFontFamily(appFont)) { buildNuvioTypography(it) }/; s/(typography = typography,\n\s*)content = content/${1}content = { com.nuvio.tv.ui.components.PrismTvTextSize(content) }/' "$tt"
grep -q 'prismTvTypography(getFontFamily(appFont))' "$tt"; grep -q 'PrismTvTextSize(content)' "$tt"
echo "Prism applied (TV)."
