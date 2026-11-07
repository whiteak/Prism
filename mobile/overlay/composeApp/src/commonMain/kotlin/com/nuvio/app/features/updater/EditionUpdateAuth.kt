package com.nuvio.app.features.updater

// Prism's releases are public, so the updater sends no key.
internal fun editionAuthHeaders(): Map<String, String> = emptyMap()

/** Assets download through the API, which asks for the raw file. */
internal fun editionDownloadHeaders(url: String): Map<String, String> =
    if (url.startsWith("https://api.github.com/")) editionAuthHeaders() + ("Accept" to "application/octet-stream") else emptyMap()

/** One release carries both apps, so this app's version comes from its own APK's name. */
internal fun GitHubReleaseDto.editionTagged(): GitHubReleaseDto {
    val apk = assets.firstOrNull {
        it.name.startsWith(RELEASE_ASSET_PREFIX, ignoreCase = true) && it.name.endsWith(".apk", ignoreCase = true)
    } ?: return this
    return copy(tagName = apk.name.substring(RELEASE_ASSET_PREFIX.length).removeSuffix(".apk"))
}
