package com.nuvio.tv.updater

import com.nuvio.tv.data.remote.dto.GitHubReleaseDto

/** Prism: one release carries both apps, so this app's version comes from its own APK's name. */
internal fun GitHubReleaseDto.editionTagged(): GitHubReleaseDto {
    val apk = assets.firstOrNull {
        it.name.startsWith(RELEASE_ASSET_PREFIX, ignoreCase = true) && it.name.endsWith(".apk", ignoreCase = true)
    } ?: return this
    return copy(tagName = apk.name.substring(RELEASE_ASSET_PREFIX.length).removeSuffix(".apk"))
}
