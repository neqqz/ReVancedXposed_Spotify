package io.github.chsbuffer.revancedxposed.spotify.misc

import app.revanced.extension.shared.Logger
import app.revanced.extension.spotify.misc.UnlockPremiumPatch
import io.github.chsbuffer.revancedxposed.callMethod
import io.github.chsbuffer.revancedxposed.findFirstFieldByExactType
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook
import io.github.chsbuffer.revancedxposed.xposed.MethodHookParam
import io.github.chsbuffer.revancedxposed.xposed.XC_MethodHook
import io.github.chsbuffer.revancedxposed.xposed.XposedBridge
import io.github.chsbuffer.revancedxposed.xposed.XposedHelpers
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.Constructor
import java.lang.reflect.Field

/**
 * Context menu item IDs which lead to a Premium upsell/ad.
 * The item model is obfuscated and its field names change between releases, so the ID is read
 * reflectively from every String field instead of relying on a fixed field name.
 */
private val premiumContextMenuItemIds = setOf(
    "context_menu_remove_ads",
    "playlist_entity_reinventfree_adsfree_context_menu_item",
)

private fun isPremiumContextMenuItem(item: Any?): Boolean {
    if (item == null) return false
    return item.javaClass.declaredFields.any { field ->
        if (field.type != String::class.java) return@any false
        val value = runCatching {
            field.isAccessible = true
            field.get(item) as? String
        }.getOrNull() ?: return@any false
        value.startsWith("premium_destination_") ||
            value.startsWith("playlist_entity_reinventfree_") ||
            value in premiumContextMenuItemIds
    }
}

@Suppress("UNCHECKED_CAST")
fun SpotifyHook.UnlockPremium() {
    // Override the attributes map in the getter method.
    ::productStateProtoFingerprint.hookMethod {
        val field = ::attributesMapField.field
        before { param ->
            Logger.printDebug { field.get(param.thisObject)!!.toString() }
            UnlockPremiumPatch.overrideAttributes(field.get(param.thisObject) as Map<String, *>)
        }
    }

    // Add the query parameter trackRows to show popular tracks in the artist page.
    ::buildQueryParametersFingerprint.hookMethod {
        after { param ->
            val result = param.result
            val FIELD = "checkDeviceCapability"
            if (result.toString().contains("${FIELD}=")) {
                param.result = XposedBridge.invokeOriginalMethod(
                    param.method, param.thisObject, arrayOf(param.args[0], true)
                )
            }
        }
    }

    // Enable choosing a specific song/artist via Google Assistant.
    ::contextFromJsonFingerprint.hookMethod {
        fun removeStationString(field: Field, obj: Any) {
            val value = field.get(obj) as? String ?: return
            field.set(obj, UnlockPremiumPatch.removeStationString(value))
        }

        after { param ->
            val thiz = param.result ?: return@after
            val clazz = thiz.javaClass
            for (name in arrayOf("uri", "url")) {
                val field = clazz.declaredFields.firstOrNull { it.name == name } ?: continue
                field.isAccessible = true
                removeStationString(field, thiz)
            }
        }
    }

    // Disable forced shuffle when asking for an album/playlist via Google Assistant.
    XposedHelpers.findAndHookMethod(
        "com.spotify.player.model.command.options.AutoValue_PlayerOptionOverrides\$Builder",
        classLoader,
        "build",
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.thisObject!!.callMethod("shufflingContext", false)
            }
        })

    // Hook the method which adds context menu items and return before adding if the item is a Premium ad.
    val contextMenuViewModelClazz = ::contextMenuViewModelClass.clazz
    XposedBridge.hookAllConstructors(
        contextMenuViewModelClazz, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val parameterTypes = (param.method as Constructor<*>).parameterTypes
                Logger.printDebug { "ContextMenuViewModel(${parameterTypes.joinToString(",") { it.name }})" }
                for (i in 0 until param.args.size) {
                    if (parameterTypes[i].name != "java.util.List") continue
                    val original = param.args[i] as? List<*> ?: continue
                    Logger.printDebug { "List value type: ${original.firstOrNull()?.javaClass}" }
                    val filtered = original.filterNot { isPremiumContextMenuItem(it) }
                    param.args[i] = filtered
                    Logger.printDebug { "Filtered ${original.size - filtered.size} context menu items." }
                }
            }
        })

    // Remove ads sections from home.
    ::homeStructureGetSectionsFingerprint.hookMethod {
        after { param ->
            val sections = param.result ?: return@after
            // Set sections mutable
            sections.javaClass.findFirstFieldByExactType(Boolean::class.java).set(sections, true)
            UnlockPremiumPatch.removeHomeSections(sections as MutableList<*>)
        }
    }
    // Remove ads sections from browser.
    ::browseStructureGetSectionsFingerprint.hookMethod {
        after { param ->
            val sections = param.result ?: return@after
            // Set sections mutable
            sections.javaClass.findFirstFieldByExactType(Boolean::class.java).set(sections, true)
            UnlockPremiumPatch.removeBrowseSections(sections as MutableList<*>)
        }
    }

    // Remove pendragon (pop up ads) requests and return the errors instead.
    val replaceFetchRequestSingleWithError = object : XC_MethodHook() {
        val justMethod =
            DexMethod("Lio/reactivex/rxjava3/core/Single;->just(Ljava/lang/Object;)Lio/reactivex/rxjava3/core/Single;").toMethod()

        val onErrorField =
            DexField("Lio/reactivex/rxjava3/internal/operators/single/SingleOnErrorReturn;->b:Lio/reactivex/rxjava3/functions/Function;").toField()

        override fun afterHookedMethod(param: MethodHookParam) {
            val result = param.result ?: return
            if (!result.javaClass.name.endsWith("SingleOnErrorReturn")) return
            val justError = justMethod.invoke(null, onErrorField.get(result))
            param.result = justError
        }
    }

    ::pendragonJsonFetchMessageRequestFingerprint.hookMethod(replaceFetchRequestSingleWithError)
    ::pendragonJsonFetchMessageListRequestFingerprint.hookMethod(replaceFetchRequestSingleWithError)
}