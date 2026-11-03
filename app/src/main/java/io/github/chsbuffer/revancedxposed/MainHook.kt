package io.github.chsbuffer.revancedxposed

import android.app.Application
import app.revanced.extension.shared.Utils
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook
import io.github.chsbuffer.revancedxposed.xposed.LoadPackageParam
import io.github.chsbuffer.revancedxposed.xposed.MethodHookParam
import io.github.chsbuffer.revancedxposed.xposed.XC_MethodHook
import io.github.chsbuffer.revancedxposed.xposed.XposedBridge
import io.github.chsbuffer.revancedxposed.xposed.XposedHelpers
import io.github.chsbuffer.revancedxposed.xposed.xposedModule
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Modern Xposed API (API 102) entry point.
 *
 * The legacy equivalent implemented `IXposedHookLoadPackage` + `IXposedHookZygoteInit`.
 */
class MainHook : XposedModule() {
    lateinit var lpparam: LoadPackageParam
    lateinit var app: Application
    var targetPackageName: String? = null
    val hooksByPackage = mapOf(
        "com.spotify.music" to { SpotifyHook(app, lpparam) },
    )

    fun shouldHook(packageName: String): Boolean {
        if (!hooksByPackage.containsKey(packageName)) return false
        if (targetPackageName == null) targetPackageName = packageName
        return targetPackageName == packageName
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        xposedModule = this
        modulePath = moduleApplicationInfo.sourceDir
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return
        if (!shouldHook(param.packageName)) return

        val lpparam = LoadPackageParam(
            packageName = param.packageName,
            appInfo = param.applicationInfo,
            classLoader = param.classLoader,
            isFirstApplication = param.isFirstPackage,
        )
        this.lpparam = lpparam

        inContext(lpparam) { app ->
            this.app = app
            if (isReVancedPatched(lpparam)) {
                Utils.showToastLong("ReVanced Xposed module does not work with patched app")
                return@inContext
            }

            hooksByPackage[lpparam.packageName]?.invoke()?.Hook()
        }
    }

    private fun isReVancedPatched(lpparam: LoadPackageParam): Boolean {
        return runCatching {
            lpparam.classLoader.loadClass("app.revanced.extension.shared.Utils")
        }.isSuccess || runCatching {
            lpparam.classLoader.loadClass("app.revanced.extension.shared.utils.Utils")
        }.isSuccess || runCatching {
            lpparam.classLoader.loadClass("app.revanced.integrations.shared.Utils")
        }.isSuccess || runCatching {
            lpparam.classLoader.loadClass("app.revanced.integrations.shared.utils.Utils")
        }.isSuccess
    }
}

fun inContext(lpparam: LoadPackageParam, f: (Application) -> Unit) {
    val appClassName = lpparam.appInfo.className ?: Application::class.java.name
    val appClazz = lpparam.classLoader.loadClass(appClassName)
    val onCreate = XposedHelpers.findMethodExactIfExists(appClazz, "onCreate")
        ?: Application::class.java.getMethod("onCreate")
    XposedBridge.hookMethod(onCreate, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val app = param.thisObject as Application
            Utils.setContext(app)
            f(app)
        }
    })
}
