# TV: poster thumbnails and a nicer Show cards screen

Branch `agent/tv-show-card-posters`. TV only (`tv/overlay/...`). No Gradle — checks are `tv/apply.sh` on a fresh
copy of `ntv-base` plus a read of the diff. See CLAUDE.md.

- [x] 1. Read `CLAUDE.md`, `PrismTvCustomise.kt` (prismFindTitle / PrismTvRowPage / PrismTvCardsPage), `PrismTvRows.kt`, and the NuvioTV look it must match (`SettingsDesignSystem.kt`, `AvatarPickerGrid.kt`)
- [x] 2. New `tv/overlay/app/src/main/java/com/nuvio/tv/ui/screens/settings/PrismTvPosterPicker.kt`: `PrismTvPosterPicker` (full-screen Dialog, LazyVerticalGrid of posters), shared poster box with Coil + letter placeholder, `PrismTvCardRow` (settings row with a 54x81 poster)
- [x] 3. `prismFindTitle` hands results to a `show(query, items)` callback instead of `prismChoose`; keeps the "Nothing found" toast
- [x] 4. `PrismTvCardsPage` and `PrismTvRowPage` keep results in `found` state and render the picker; the same JSON is written on a pick
- [x] 5. Show cards page uses `PrismTvCardRow` (poster + name + year/subtitle), same click menu as before
- [x] 6. `PrismTvRows.search` cap 10 -> 20 per type
- [x] 7. Check: fresh `cp -R ../ntv-base /tmp/ntv-check-$N && bash tv/apply.sh /tmp/ntv-check-$N` finishes clean
- [x] 8. Check: every import resolves in the patched copy (coil3 in app/build.gradle.kts, FocusRing/SurfaceVariant/TextPrimary/TextSecondary/Background in ui/theme/Color.kt, grid + settings helpers)
- [x] 9. Check: re-read the diff (braces, imports, @Composable calls, nothing outside settings/ + PrismTvRows.kt), LF only
- [x] 10. Commit on `agent/tv-show-card-posters`; write `AGENT_REPORT.md`
