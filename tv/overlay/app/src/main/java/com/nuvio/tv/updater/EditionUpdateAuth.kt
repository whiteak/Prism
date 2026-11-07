package com.nuvio.tv.updater

import okhttp3.OkHttpClient

// Prism's releases are public, so the updater sends no key.
private fun editionAuthHeaders(): Map<String, String> = emptyMap()

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
