# Agent report — TV: poster thumbnails and a nicer Show cards screen

Branch `agent/tv-show-card-posters` (off `main`), one commit: `9f9422e TV: poster picker and poster thumbnails
for show cards`. Nothing merged, nothing pushed (no network here — the board pushes this branch from the host).

## What I did

All inside `tv/overlay/` (TV only). No `apply.sh` change was needed: `cp -R overlay/. <checkout>` already copies
the new file, and no fork anchor was touched.

**New `tv/overlay/app/src/main/java/com/nuvio/tv/ui/screens/settings/PrismTvPosterPicker.kt`** (215 lines)

- `PrismTvPosterPicker(title, items, onPick, onDismiss)` — an `androidx.compose.ui.window.Dialog` with
  `usePlatformDefaultWidth = false`, full screen, `Background` at 96 % alpha, 48/28.dp padding. Heading 28.sp
  `TextPrimary`, `"N found · Back to cancel"` 14.sp `TextSecondary`, then
  `LazyVerticalGrid(GridCells.Adaptive(150.dp))` with 20.dp gaps (plus 12.dp vertical content padding so a focused
  card is not clipped by the grid). Pure Compose — no `AlertDialog`. Back goes through `onDismissRequest`.
- `PrismTvPosterCard` — 2:3 poster rounded 10.dp, name 16.sp (2 lines, ellipsis), then `2005 · Show` / `Movie`
  13.sp `TextSecondary` (the year is left out when it is unknown). Focused: 1.08 scale (`tween(150)`), 3.dp
  `FocusRing` border, 16.dp shadow, `zIndex` so it draws over its neighbours; unfocused: no border. OK picks, and
  the first card is focused on open (`FocusRequester` + `LaunchedEffect`, using the fork's
  `requestFocusAfterFrames()`).
- `PrismTvPosterThumb` — the shared poster box: Coil 3 `AsyncImage` (`ContentScale.Crop`, crossfade) over a
  `SurfaceVariant` box holding the name's first letter (48.sp in the picker, 22.sp in the row), so there is never
  an empty box for an old card without a poster, while one loads, or after a failed load.
- `PrismTvCardRow` — a saved show card as the other Customise rows look (same tv.material3 `Card` with
  `settingsRowColors()` / `settingsRowBorder()` / `settingsRowShape()` from `SettingsDesignSystem.kt`, so the
  FocusRing focus look matches `SettingsActionRow` in every settings style), with the 54×81.dp poster (rounded
  6.dp) at the head, name 18.sp and `2005  ·  Plays the Play random episode sound` under it.

**`PrismTvCustomise.kt`**

- `prismFindTitle(...)` now ends with `show(query, found)` instead of building text lines for `prismChoose`; the
  "Nothing found for …" toast is unchanged.
- `PrismTvRowPage` and `PrismTvCardsPage` hold `var found: Pair<String, List<PrismTvRowItem>>?` and render the
  picker when it is set. The JSON written on a pick is byte-for-byte the same as before (same keys,
  `poster`/`year` still `JSONObject.NULL` when absent); only where the pick comes from changed.
- The per-card `SettingsActionRow` in the Show cards page became `PrismTvCardRow` with the same
  Move earlier / Move later / Remove / Its own sound menu. "Add a show", "Sort…", `prismChoose` and the row page's
  title rows are untouched.

**`PrismTvRows.kt`** — `search()` cap 10 → 20 per type (and its KDoc says so).

No new dependencies: `coil-compose` is already `implementation(libs.coil.compose)` in the fork's
`app/build.gradle.kts`, with memory + disk cache set up in `NuvioApplication.kt`.

## What I verified

From inside `prism/`, twice (once mid-work, once after the final edit), each time against a **fresh** copy:

```
N=$(date +%s); cp -R ../ntv-base /tmp/ntv-final-$N && bash tv/apply.sh /tmp/ntv-final-$N
Prism applied (TV).        # exit 0, every grep assertion passed
```

In the patched copy:

- every `import` in the new file is already used by the fork's own Kotlin (checked one by one) — grid,
  `zIndex`, `shadow`, `graphicsLayer`, `Icons.AutoMirrored.Filled.KeyboardArrowRight`, `coil3.compose.AsyncImage`,
  `coil3.request.ImageRequest`/`crossfade`, `com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames`;
- colours exist in `ui/theme/Color.kt`: `Background`, `SurfaceVariant`, `FocusRing`, `TextPrimary`,
  `TextSecondary`, `TextTertiary`; spacing tokens `xxs`/`md`/`lg` exist; `settingsRowColors`,
  `settingsRowBorder`, `settingsRowShape` are `internal` in the same package (so callable from the new file);
- `PrismTvRowItem` (Prism's own) is copied in by the patch, and the patch put the new file in place;
- braces/parens/brackets balance in all three files, all three are LF-only, and the diff touches only
  `tv/overlay/.../ui/screens/settings/` and `.../ui/components/PrismTvRows.kt`;
- `ntv-base` was never modified (`git -C ../ntv-base status --short` empty) and never patched; temp copies
  removed.

**Not verified: it does not compile-check here.** Per CLAUDE.md I ran no Gradle, so the Compose compiler never
saw this. The real check is Actions > **Check Prism compiles** with `tv`, then a look at the screen on the
phone/TV — I cannot see the picker on a real TV from here.

## Unfinished / uncertain

- Focus after the picker closes lands where the parent list keeps it; if a card is added and the list grows, the
  user may need to press down once. Not tested on a device.
- The poster box for the small rows uses 22.sp for the fallback letter (the job fixed 48.sp only for the picker).
- Cards saved before posters existed fall back to the letter placeholder here, as asked. Note Home's card row
  (`PrismTvRows.inject`) still fills a missing poster from metahub by IMDb id, so Home and this list can differ
  for an old card — left as it was, outside this job's scope.
- Phone/PC: the phone already has posters, and I touched no `mobile/`, `desktop/`, `clipper/`, `masters/`, `art/`
  or workflow, so TV simply catches up. `prism.yml` untouched — no release was built or published; the branch is
  ready to push and build whenever Jason runs it.

## Build output

None produced (no Gradle by design), so there is no APK path to report. `PLAN.md` and this report are the only
non-code files, in a separate commit so the code commit stays clean — drop them if you don't want them in the
branch.
