package io.github.chsbuffer.revancedxposed.spotify.misc.privacy

import android.content.ClipData
import android.content.Intent
import app.revanced.extension.spotify.misc.privacy.SanitizeSharingLinksPatch
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.chsbuffer.revancedxposed.scopedHook
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook

fun SpotifyHook.SanitizeSharingLinks() {
    ::shareCopyUrlFingerprint.hookMethod(
        scopedHook(
            XposedHelpers.findMethodExact(
                ClipData::class.java.name,
                lpparam.classLoader,
                "newPlainText",
                CharSequence::class.java,
                CharSequence::class.java
            )
        ) {
            before { param ->
                val url = param.args[1] as String
                param.args[1] = SanitizeSharingLinksPatch.sanitizeSharingLink(url)
            }
        })

    // Sanitize the text passed to the Android share sheet. The app-side formatter is
    // obfuscated and changes between releases, so hook the platform intent instead.
    XposedBridge.hookAllMethods(
        Intent::class.java,
        "putExtra",
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args.size < 2 || param.args[0] != Intent.EXTRA_TEXT) return

                val text = when (val value = param.args[1]) {
                    is String -> value
                    is CharSequence -> value.toString()
                    else -> return
                }
                param.args[1] = URL_REGEX.replace(text) { match ->
                    SanitizeSharingLinksPatch.sanitizeSharingLink(match.value)
                }
            }
        })
}

private val URL_REGEX = Regex("""https?://\S+""")
