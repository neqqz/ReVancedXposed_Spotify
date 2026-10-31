package io.github.chsbuffer.revancedxposed.spotify.misc.widgets

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook

private const val BIND_APP_WIDGET = "android.permission.BIND_APPWIDGET"

fun SpotifyHook.FixThirdPartyLaunchersWidgets() {
    // The media provider only hands out widget data to apps holding BIND_APPWIDGET.
    // Third party launchers do not hold this system permission, so report it as granted
    // for the process. Only this single permission is affected.
    XposedHelpers.findAndHookMethod(
        "android.app.ApplicationPackageManager",
        classLoader,
        "checkPermission",
        String::class.java,
        String::class.java,
        object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.args[0] == BIND_APP_WIDGET) {
                    param.result = 0 // PackageManager.PERMISSION_GRANTED
                }
            }
        })
}
