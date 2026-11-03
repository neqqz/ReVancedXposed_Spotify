package io.github.chsbuffer.revancedxposed.xposed

import android.content.pm.ApplicationInfo
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Information about the package that is being hooked.
 *
 * Mirrors the subset of the legacy `XC_LoadPackage.LoadPackageParam` that this module uses.
 */
class LoadPackageParam(
    @JvmField val packageName: String,
    @JvmField val appInfo: ApplicationInfo,
    @JvmField val classLoader: ClassLoader,
    @JvmField val isFirstApplication: Boolean,
)

/** The module entry instance, assigned in [io.github.chsbuffer.revancedxposed.MainHook.onModuleLoaded]. */
lateinit var xposedModule: XposedModule
    internal set

/**
 * Legacy style hook parameter, backed by a modern [XposedInterface.Chain].
 *
 * Exposes the same shape as `XC_MethodHook.MethodHookParam` so the hooks themselves do not need
 * to know which Xposed API is in use.
 */
class MethodHookParam internal constructor(
    @JvmField var method: Member,
    @JvmField var thisObject: Any?,
    @JvmField var args: Array<Any?>,
) {
    @JvmField
    internal var rawResult: Any? = null

    @JvmField
    internal var rawThrowable: Throwable? = null

    /** Set when a hook called [setResult] or [setThrowable], meaning the origin must be skipped. */
    @JvmField
    internal var returnEarly = false

    var result: Any?
        get() = rawResult
        set(value) {
            rawResult = value
            rawThrowable = null
            returnEarly = true
        }

    var throwable: Throwable?
        get() = rawThrowable
        set(value) {
            rawThrowable = value
            returnEarly = true
        }

    fun hasThrowable(): Boolean = rawThrowable != null

    fun getResultOrThrowable(): Any? {
        rawThrowable?.let { throw it }
        return rawResult
    }

    private val extras = HashMap<String, Any?>()

    fun setObjectExtra(name: String, value: Any?) {
        extras[name] = value
    }

    fun getObjectExtra(name: String): Any? = extras[name]
}

/** Legacy style method hook. Only the before/after callbacks are used by this module. */
abstract class XC_MethodHook {
    protected open fun beforeHookedMethod(param: MethodHookParam) {}

    protected open fun afterHookedMethod(param: MethodHookParam) {}

    internal fun callBefore(param: MethodHookParam) = beforeHookedMethod(param)

    internal fun callAfter(param: MethodHookParam) = afterHookedMethod(param)
}

private class LegacyHooker(private val callback: XC_MethodHook) : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val param = MethodHookParam(chain.executable, chain.thisObject, chain.args.toTypedArray())
        callback.callBefore(param)

        if (!param.returnEarly && param.rawThrowable == null) {
            try {
                param.rawResult = chain.proceed(param.args)
            } catch (t: Throwable) {
                param.rawThrowable = t
            }
        }

        // Exceptions thrown by an after hook propagate to the caller, as with the legacy API.
        callback.callAfter(param)

        param.rawThrowable?.let { throw it }
        return param.rawResult
    }
}

/** Legacy style bridge backed by the modern Xposed API. */
object XposedBridge {
    private const val TAG = "ReVancedXposed"

    @JvmStatic
    fun log(message: String?) {
        val msg = message ?: "null"
        Log.i(TAG, msg)
        if (::xposedModule.isInitialized) {
            runCatching { xposedModule.log(Log.INFO, TAG, msg) }
        }
    }

    @JvmStatic
    fun log(t: Throwable) {
        log(Log.getStackTraceString(t))
    }

    @JvmStatic
    fun hookMethod(member: Member, callback: XC_MethodHook): XposedInterface.HookHandle =
        xposedModule.hook(member as Executable).intercept(LegacyHooker(callback))

    @JvmStatic
    fun hookAllMethods(clazz: Class<*>, methodName: String, callback: XC_MethodHook) {
        clazz.declaredMethods.filter { it.name == methodName }.forEach { hookMethod(it, callback) }
    }

    @JvmStatic
    fun hookAllConstructors(clazz: Class<*>, callback: XC_MethodHook) {
        clazz.declaredConstructors.forEach { hookMethod(it, callback) }
    }

    @JvmStatic
    fun invokeOriginalMethod(method: Member, thisObject: Any?, args: Array<Any?>): Any? =
        when (method) {
            is Method -> xposedModule.getInvoker(method)
                .setType(XposedInterface.Invoker.Type.ORIGIN)
                .invoke(thisObject, *args)

            is Constructor<*> -> xposedModule.getInvoker(method)
                .setType(XposedInterface.Invoker.Type.ORIGIN)
                .newInstance(*args)

            else -> throw IllegalArgumentException("Unsupported member: $method")
        }
}

/**
 * Reflection helpers with the same names as the legacy `XposedHelpers`, so the fingerprint and
 * patch code can stay unchanged. All lookups walk the superclass chain like the legacy helpers.
 */
@Suppress("unused")
object XposedHelpers {
    private fun resolveType(name: String, classLoader: ClassLoader?): Class<*> = when (name) {
        "void" -> Void.TYPE
        "boolean" -> java.lang.Boolean.TYPE
        "byte" -> java.lang.Byte.TYPE
        "char" -> java.lang.Character.TYPE
        "short" -> java.lang.Short.TYPE
        "int" -> java.lang.Integer.TYPE
        "long" -> java.lang.Long.TYPE
        "float" -> java.lang.Float.TYPE
        "double" -> java.lang.Double.TYPE
        else -> when {
            name.endsWith("[]") ->
                ReflectArray.newInstance(resolveType(name.dropLast(2), classLoader), 0).javaClass

            name.startsWith("L") && name.endsWith(";") ->
                Class.forName(name.substring(1, name.length - 1).replace('/', '.'), false, classLoader)

            else -> Class.forName(name, false, classLoader)
        }
    }

    private fun resolveTypes(types: Array<out Any?>, classLoader: ClassLoader?): Array<Class<*>> =
        types.map {
            when (it) {
                is Class<*> -> it
                is String -> resolveType(it, classLoader)
                else -> throw IllegalArgumentException("Unsupported parameter type: $it")
            }
        }.toTypedArray()

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        else -> type
    }

    private fun isCompatible(paramTypes: Array<Class<*>>, args: Array<out Any?>): Boolean {
        if (paramTypes.size != args.size) return false
        return paramTypes.indices.all { i ->
            val arg = args[i]
            if (arg == null) !paramTypes[i].isPrimitive else boxed(paramTypes[i]).isInstance(arg)
        }
    }

    private fun unwrap(e: InvocationTargetException): Throwable = e.targetException ?: e

    private fun invokeMethod(method: Method, thisObject: Any?, args: Array<out Any?>): Any? = try {
        method.invoke(thisObject, *args)
    } catch (e: InvocationTargetException) {
        throw unwrap(e)
    }

    @JvmStatic
    fun findClass(className: String, classLoader: ClassLoader?): Class<*> =
        resolveType(className, classLoader ?: XposedHelpers::class.java.classLoader)

    @JvmStatic
    fun findClassIfExists(className: String, classLoader: ClassLoader?): Class<*>? = try {
        findClass(className, classLoader)
    } catch (_: ClassNotFoundException) {
        null
    }

    @JvmStatic
    fun findMethodExact(
        className: String, classLoader: ClassLoader?, methodName: String?, vararg parameterTypes: Any?,
    ): Method = findMethodExact(findClass(className, classLoader), methodName, *parameterTypes)

    @JvmStatic
    fun findMethodExact(clazz: Class<*>, methodName: String?, vararg parameterTypes: Any?): Method =
        findMethodExactIfExists(clazz, methodName, *parameterTypes)
            ?: throw NoSuchMethodError("$methodName(${parameterTypes.joinToString()}) in ${clazz.name}")

    @JvmStatic
    fun findMethodExactIfExists(
        clazz: Class<*>, methodName: String?, vararg parameterTypes: Any?,
    ): Method? {
        val name = methodName ?: return null
        val types = resolveTypes(parameterTypes, clazz.classLoader)
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, *types).apply { isAccessible = true }
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            }
        }
        return null
    }

    @JvmStatic
    fun findConstructorExact(clazz: Class<*>, vararg parameterTypes: Any?): Constructor<*> =
        findConstructorExactIfExists(clazz, *parameterTypes)
            ?: throw NoSuchMethodError("<init>(${parameterTypes.joinToString()}) in ${clazz.name}")

    @JvmStatic
    fun findConstructorExactIfExists(
        clazz: Class<*>, vararg parameterTypes: Any?,
    ): Constructor<*>? = try {
        clazz.getDeclaredConstructor(*resolveTypes(parameterTypes, clazz.classLoader))
            .apply { isAccessible = true }
    } catch (_: NoSuchMethodException) {
        null
    }

    @JvmStatic
    fun findField(clazz: Class<*>, fieldName: String?): Field =
        findFieldIfExists(clazz, fieldName) ?: throw NoSuchFieldError("$fieldName in ${clazz.name}")

    @JvmStatic
    fun findFieldIfExists(clazz: Class<*>, fieldName: String?): Field? {
        val name = fieldName ?: return null
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                return current.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    @JvmStatic
    fun findFirstFieldByExactType(clazz: Class<*>, type: Class<*>?): Field {
        val target = type ?: throw NoSuchFieldError("null type in ${clazz.name}")
        var current: Class<*>? = clazz
        while (current != null) {
            for (field in current.declaredFields) {
                if (field.type == target) return field.apply { isAccessible = true }
            }
            current = current.superclass
        }
        throw NoSuchFieldError("${target.name} in ${clazz.name}")
    }

    @JvmStatic
    fun getObjectField(obj: Any?, fieldName: String?): Any? =
        findField(requireNotNull(obj).javaClass, fieldName).get(obj)

    @JvmStatic
    fun getIntField(obj: Any?, fieldName: String?): Int =
        findField(requireNotNull(obj).javaClass, fieldName).getInt(obj)

    @JvmStatic
    fun getLongField(obj: Any?, fieldName: String?): Long =
        findField(requireNotNull(obj).javaClass, fieldName).getLong(obj)

    @JvmStatic
    fun getBooleanField(obj: Any?, fieldName: String?): Boolean =
        findField(requireNotNull(obj).javaClass, fieldName).getBoolean(obj)

    @JvmStatic
    fun getStaticObjectField(clazz: Class<*>, fieldName: String?): Any? =
        findField(clazz, fieldName).get(null)

    @JvmStatic
    fun setObjectField(obj: Any?, fieldName: String?, value: Any?) {
        findField(requireNotNull(obj).javaClass, fieldName).set(obj, value)
    }

    @JvmStatic
    fun setIntField(obj: Any?, fieldName: String?, value: Int) {
        findField(requireNotNull(obj).javaClass, fieldName).setInt(obj, value)
    }

    @JvmStatic
    fun setLongField(obj: Any?, fieldName: String?, value: Long) {
        findField(requireNotNull(obj).javaClass, fieldName).setLong(obj, value)
    }

    @JvmStatic
    fun setBooleanField(obj: Any?, fieldName: String?, value: Boolean) {
        findField(requireNotNull(obj).javaClass, fieldName).setBoolean(obj, value)
    }

    @JvmStatic
    fun setFloatField(obj: Any?, fieldName: String?, value: Float) {
        findField(requireNotNull(obj).javaClass, fieldName).setFloat(obj, value)
    }

    @JvmStatic
    fun setStaticObjectField(clazz: Class<*>, fieldName: String?, value: Any?) {
        findField(clazz, fieldName).set(null, value)
    }

    private fun findMethodBestMatch(
        clazz: Class<*>, methodName: String, args: Array<out Any?>, static: Boolean,
    ): Method {
        var current: Class<*>? = clazz
        while (current != null) {
            for (method in current.declaredMethods) {
                if (method.name != methodName) continue
                if (Modifier.isStatic(method.modifiers) != static) continue
                if (!isCompatible(method.parameterTypes, args)) continue
                return method.apply { isAccessible = true }
            }
            current = current.superclass
        }
        throw NoSuchMethodError("$methodName(${args.joinToString()}) in ${clazz.name}")
    }

    private fun findConstructorBestMatch(clazz: Class<*>, args: Array<out Any?>): Constructor<*> {
        for (constructor in clazz.declaredConstructors) {
            if (isCompatible(constructor.parameterTypes, args)) {
                return constructor.apply { isAccessible = true }
            }
        }
        throw NoSuchMethodError("<init>(${args.joinToString()}) in ${clazz.name}")
    }

    @JvmStatic
    fun callMethod(obj: Any?, methodName: String?, vararg args: Any?): Any? {
        val name = methodName ?: throw NullPointerException("methodName")
        return invokeMethod(findMethodBestMatch(requireNotNull(obj).javaClass, name, args, static = false), obj, args)
    }

    @JvmStatic
    fun callMethod(
        obj: Any?, methodName: String?, parameterTypes: Array<Class<*>>, vararg args: Any?,
    ): Any? = invokeMethod(findMethodExact(requireNotNull(obj).javaClass, methodName, *parameterTypes), obj, args)

    @JvmStatic
    fun callStaticMethod(clazz: Class<*>, methodName: String?, vararg args: Any?): Any? {
        val name = methodName ?: throw NullPointerException("methodName")
        return invokeMethod(findMethodBestMatch(clazz, name, args, static = true), null, args)
    }

    @JvmStatic
    fun callStaticMethod(
        clazz: Class<*>, methodName: String?, parameterTypes: Array<Class<*>>, vararg args: Any?,
    ): Any? = invokeMethod(findMethodExact(clazz, methodName, *parameterTypes), null, args)

    @JvmStatic
    fun newInstance(clazz: Class<*>, vararg args: Any?): Any =
        findConstructorBestMatch(clazz, args).newInstance(*args)!!

    @JvmStatic
    fun newInstance(
        clazz: Class<*>, parameterTypes: Array<Class<*>>, vararg args: Any?,
    ): Any = findConstructorExact(clazz, *parameterTypes).newInstance(*args)!!

    @JvmStatic
    fun findAndHookMethod(
        className: String, classLoader: ClassLoader?, methodName: String?,
        vararg parameterTypesAndCallback: Any?,
    ): XC_MethodHook {
        val callback = parameterTypesAndCallback.lastOrNull() as? XC_MethodHook
            ?: throw IllegalArgumentException("Last argument must be an XC_MethodHook")
        val parameterTypes = parameterTypesAndCallback.dropLast(1).toTypedArray()
        val method = findMethodExact(findClass(className, classLoader), methodName, *parameterTypes)
        XposedBridge.hookMethod(method, callback)
        return callback
    }
}
