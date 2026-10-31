package com.nuvio.app.features.trakt

import com.nuvio.app.features.simkl.SimklPkceCrypto
import com.nuvio.app.features.simkl.base64UrlWithoutPadding

// New Trakt apps sign in with PKCE only (no Client Secret), so the authorize link carries an S256 challenge.
internal fun editionTraktChallengeParams(verifier: String?): String {
    if (verifier.isNullOrBlank()) return ""
    val challenge = SimklPkceCrypto.sha256(verifier.encodeToByteArray()).base64UrlWithoutPadding()
    return "&code_challenge=$challenge&code_challenge_method=S256"
}
