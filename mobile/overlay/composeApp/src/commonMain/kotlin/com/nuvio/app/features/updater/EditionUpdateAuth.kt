package com.nuvio.app.features.updater

// Prism: releases live in a private repo, so the updater reads them with a read-only key
// (filled in at build time from the UPDATE_TOKEN secret).
private const val EDITION_UPDATE_TOKEN = "@UPDATE_TOKEN@"

internal fun editionAuthHeaders(): Map<String, String> =
    if (EDITION_UPDATE_TOKEN.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $EDITION_UPDATE_TOKEN")

/** Private assets download through the API, which needs the key and asks for the raw file. */
internal fun editionDownloadHeaders(url: String): Map<String, String> =
    if (url.startsWith("https://api.github.com/")) editionAuthHeaders() + ("Accept" to "application/octet-stream") else emptyMap()

/** One release carries both apps, so this app's version comes from its own APK's name. */
internal fun GitHubReleaseDto.editionTagged(): GitHubReleaseDto {
    val apk = assets.firstOrNull {
        it.name.startsWith(RELEASE_ASSET_PREFIX, ignoreCase = true) && it.name.endsWith(".apk", ignoreCase = true)
    } ?: return this
    return copy(tagName = apk.name.substring(RELEASE_ASSET_PREFIX.length).removeSuffix(".apk"))
}
