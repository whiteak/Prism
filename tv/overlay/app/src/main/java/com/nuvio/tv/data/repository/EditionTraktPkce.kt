package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

// New Trakt apps sign in with PKCE only (no Client Secret). The TV code flow sends an S256 challenge
// when it asks for the code, and the matching verifier while it waits for approval.
internal object EditionTraktPkce {
    private const val KEY = "trakt_pkce_verifier"

    private fun prefs(context: Context) = context.getSharedPreferences("edition", Context.MODE_PRIVATE)

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    /** Starts a new sign-in and returns its challenge. */
    fun newChallenge(context: Context): String {
        val verifier = encode(ByteArray(32).also { SecureRandom().nextBytes(it) })
        prefs(context).edit().putString(KEY, verifier).apply()
        return encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    }

    fun verifier(context: Context): String? = prefs(context).getString(KEY, null)
}
