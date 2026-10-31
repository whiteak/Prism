package com.nuvio.tv.updater

import okhttp3.OkHttpClient

// Prism (shared with the Supernatural edition): releases live in a private repo, so the updater reads them with a read-only key
// (filled in at build time from the UPDATE_TOKEN secret).
private const val EDITION_UPDATE_TOKEN = "@UPDATE_TOKEN@"

private fun editionAuthHeaders(): Map<String, String> =
    if (EDITION_UPDATE_TOKEN.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $EDITION_UPDATE_TOKEN")

fun editionGitHubClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
    .addInterceptor { chain ->
        val request = chain.request()
        val builder = request.newBuilder()
        if (request.url.host == "api.github.com") editionAuthHeaders().forEach { (k, v) -> builder.header(k, v) }
        chain.proceed(builder.build())
    }
    .build()

/** Private assets download through the API, which needs the key and asks for the raw file. */
fun editionDownloadHeaders(url: String): Map<String, String> =
    if (url.startsWith("https://api.github.com/")) editionAuthHeaders() + ("Accept" to "application/octet-stream") else emptyMap()
