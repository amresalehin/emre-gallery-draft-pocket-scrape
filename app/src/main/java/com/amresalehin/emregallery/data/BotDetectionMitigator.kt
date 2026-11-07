package com.amresalehin.emregallery.data

import android.webkit.CookieManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Detects site access restrictions and persists cookies from the user's visible browser session.
 *
 * This component does not modify browser fingerprints, spoof browser capabilities, or automate
 * challenge completion. Authentication and verification remain under the user's control.
 */
object BotDetectionMitigator {

    fun isBotChallenge(title: String?, url: String?): Boolean {
        val t = title.orEmpty().lowercase()
        val u = url.orEmpty().lowercase()
        return t.contains("just a moment") ||
                t.contains("attention required") ||
                t.contains("cloudflare") ||
                t.contains("security check") ||
                t.contains("checking your browser") ||
                u.contains("challenge-platform") ||
                u.contains("cf-chl-widget")
    }

    fun accessRestrictionMessage(title: String?, url: String?): String? {
        return when {
            isSignInPage(url, title) ->
                "Authentication required. Open the source page in the browser and sign in manually."
            isBotChallenge(title, url) ->
                "Bot verification challenge detected. Open the source page in the browser and complete verification manually."
            else -> null
        }
    }

    /** Flush cookies set by the user's visible browser session; cookie values are never returned. */
    fun syncCookiesFromWebView(url: String?) {
        if (url.isNullOrBlank() || url == "about:blank") return
        try {
            val cm = CookieManager.getInstance()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    cm.flush()
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    fun indicatesCookiesOrBotVerificationNeeded(error: String?): Boolean {
        if (error.isNullOrBlank()) return false
        val err = error.lowercase()
        return err.contains("sign in to confirm you're not a bot") ||
                err.contains("bot verification") ||
                err.contains("too many requests") ||
                err.contains("http error 429") ||
                err.contains("http error 403") ||
                err.contains("cf_clearance") ||
                err.contains("cloudflare") ||
                err.contains("cookies are required") ||
                err.contains("use --cookies") ||
                err.contains("checkpoint_required") ||
                err.contains("login required") ||
                err.contains("sign in to confirm your age") ||
                err.contains("only available to registered users") ||
                err.contains("authentication required") ||
                err.contains("rate limit")
    }

    fun indicatesSignInRequired(error: String?): Boolean {
        if (error.isNullOrBlank()) return false
        val err = error.lowercase()
        return err.contains("sign in to confirm your age") ||
                err.contains("only available to registered users") ||
                err.contains("login required") ||
                err.contains("authentication required") ||
                err.contains("private video") ||
                err.contains("private account") ||
                err.contains("redirected to login") ||
                err.contains("restricted video")
    }

    fun isSignInPage(url: String?, title: String?): Boolean {
        val u = url.orEmpty().lowercase()
        val t = title.orEmpty().lowercase()
        return u.contains("/login") ||
                u.contains("/signin") ||
                u.contains("accounts.google.com") ||
                u.contains("accounts/login") ||
                u.contains("/checkpoint/") ||
                u.contains("/auth/") ||
                t.startsWith("sign in") ||
                t.startsWith("log in") ||
                t.startsWith("login") ||
                t.contains("sign in - google")
    }
}
