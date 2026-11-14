package com.azurpilot.ghio.root

import android.annotation.SuppressLint
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.os.IBinder

import com.azurpilot.ghio.third.Ln

/**
 * 在 root/shell `app_process` JVM 中反射创建应用的特权服务 Binder。
 *
 * 此对象重建最小 ActivityThread/系统 Context 环境，以 shell 或 root 身份加载 app 代码；
 * OEM 对隐藏 API 的差异会导致完整 Application 创建失败，因此允许退化为可用的 package
 * Context。所有入口只由 [RootServiceStarter] 的特权进程主线程调用。
 *
 * Reflectively creates the app's privileged-service Binder inside a root/shell `app_process` JVM.
 *
 * This object rebuilds the minimum ActivityThread/system-Context environment to load app code as
 * shell or root. OEM hidden-API variation can make full Application creation fail, so it may fall
 * back to a usable package Context. [RootServiceStarter] calls all entry points only on the
 * privileged-process main thread.
 */
object RootUserService {

    private const val TAG = "RootUserService"

    /**
     * 解析 launcher 参数、构建应用加载环境并实例化目标 Binder 服务。
     *
     * 任一隐藏 API、类加载或构造失败都记录后返回 null，让 [RootServiceStarter] 以非零状态
     * 退出。MIUI 等 OEM 若在 `LoadedApk.makeApplication` 因主题或系统文件缺失失败，会退化为
     * package Context，因为服务构造所需的 class loader 仍可用。
     *
     * Parses launcher arguments, builds the application loading environment, and instantiates the
     * target Binder service.
     *
     * Any hidden-API, class-loading, or construction failure logs and returns null so
     * [RootServiceStarter] exits nonzero. If OEMs such as MIUI fail in `LoadedApk.makeApplication`
     * because theme or system files are unavailable, this falls back to package Context because its
     * class loader remains usable for service construction.
     */
    fun create(args: Array<String>): CreatedService? {
        val parsed = ParsedArgs.parse(args) ?: return null

        val userId = parsed.uid / 100000
        val appName = parsed.debugName ?: (parsed.packageName + ":root_service")

        Ln.i(String.format("%s: starting service %s/%s...", TAG, parsed.packageName, parsed.className))
        return try {
            val activityThread = createActivityThread()
            val systemContext = getSystemContext(activityThread)
                ?: throw IllegalStateException("system context is null")

            setAppName(appName, userId)

            val packageContext = createPackageContextAsUser(systemContext, parsed.packageName, userId)
            val application = if (parsed.contextOnly) null else try {
                makeApplication(activityThread, packageContext)
            } catch (tr: Throwable) {
                // 部分 OEM（如 MIUI）修改了 LoadedApk.makeApplication；shell 身份缺少主题或
                // 系统文件时会失败，但 packageContext 仍可提供服务构造所需 class loader，故降级。
                Ln.w(TAG + ": makeApplication failed, falling back to packageContext", tr)
                null
            }
            val constructorContext: Context = application ?: packageContext
            val classLoader = constructorContext.classLoader
            val serviceClass = classLoader.loadClass(parsed.className)
            val service = instantiateService(serviceClass, constructorContext)

            CreatedService(service, parsed.token, parsed.packageName, userId)
        } catch (tr: Throwable) {
            Ln.e(String.format("%s: unable to start service %s/%s", TAG, parsed.packageName, parsed.className), tr)
            null
        }
    }

    /**
     * 使用隐藏的 `ActivityThread.systemMain` 构建系统 ActivityThread。
     *
     * 该方法只允许在 app_process 的特权 JVM 中调用；普通 app 进程不应自行创建第二个
     * ActivityThread。
     *
     * Builds the system ActivityThread through hidden `ActivityThread.systemMain`.
     *
     * Only the privileged app_process JVM may call this; an ordinary app process must not create a
     * second ActivityThread of its own.
     */
    @SuppressLint("PrivateApi,DiscouragedPrivateApi")
    @Throws(Exception::class)
    private fun createActivityThread(): Any {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val systemMain = activityThreadClass.getDeclaredMethod("systemMain")
        systemMain.isAccessible = true
        return systemMain.invoke(null)
    }

    /** 从隐藏 ActivityThread 取得系统 Context。 / Obtains the system Context from a hidden ActivityThread. */
    @Throws(Exception::class)
    private fun getSystemContext(activityThread: Any): Context {
        val method = activityThread.javaClass.getDeclaredMethod("getSystemContext")
        method.isAccessible = true
        return method.invoke(activityThread) as Context
    }

    /**
     * 通过 LoadedApk 创建应用并回填 ActivityThread 的 initial application。
     *
     * 这是 best-effort 增强；[create] 会在 OEM 失败时改用 package Context。
     *
     * Creates the application through LoadedApk and fills ActivityThread's initial application.
     * This is a best-effort enhancement; [create] uses package Context on OEM failure.
     */
    @Throws(Exception::class)
    private fun makeApplication(activityThread: Any, packageContext: Context): Application {
        val packageInfoField = packageContext.javaClass.getDeclaredField("mPackageInfo")
        packageInfoField.isAccessible = true
        val loadedApk = packageInfoField.get(packageContext)

        val makeApplication = loadedApk.javaClass
            .getDeclaredMethod("makeApplication", Boolean::class.java, Instrumentation::class.java)
        makeApplication.isAccessible = true
        val application = makeApplication.invoke(loadedApk, true, null) as Application

        val initialApplicationField = activityThread.javaClass.getDeclaredField("mInitialApplication")
        initialApplicationField.isAccessible = true
        initialApplicationField.set(activityThread, application)
        return application
    }

    /**
     * 创建指定 Android user 的代码加载 Context，并在旧平台/OEM 缺失 API 时退化。
     *
     * [Context.CONTEXT_IGNORE_SECURITY] 仅用于已由 root/shell launcher 隔离的特权子进程；
     * 不能用于普通 app 进程路径。
     *
     * Creates a code-loading Context for the specified Android user and falls back when an old
     * platform or OEM lacks the API.
     *
     * [Context.CONTEXT_IGNORE_SECURITY] is used only in the privileged child already isolated by
     * the root/shell launcher; it must not be used on ordinary app-process paths.
     */
    @Throws(Exception::class)
    private fun createPackageContextAsUser(context: Context, packageName: String, userId: Int): Context {
        val flags = Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
        return try {
            val userHandleClass = Class.forName("android.os.UserHandle")
            val userHandle: Any = try {
                val ofMethod = userHandleClass.getDeclaredMethod("of", Int::class.java)
                ofMethod.invoke(null, userId)
            } catch (ignored: Throwable) {
                val constructor = userHandleClass.getDeclaredConstructor(Int::class.java)
                constructor.isAccessible = true
                constructor.newInstance(userId)
            }

            val createMethod = Context::class.java.getMethod(
                "createPackageContextAsUser",
                String::class.java,
                Int::class.java,
                userHandleClass
            )
            createMethod.invoke(context, packageName, flags, userHandle) as Context
        } catch (ignored: Throwable) {
            context.createPackageContext(packageName, flags)
        }
    }

    /**
     * 为该进程设置可识别的 DDM 名称；失败不影响服务创建。
     *
     * Sets a recognizable DDM name for this process; failure does not block service creation.
     */
    @SuppressLint("PrivateApi,DiscouragedPrivateApi")
    private fun setAppName(name: String, userId: Int) {
        try {
            val cls = Class.forName("android.ddm.DdmHandleAppName")
            val method = cls.getDeclaredMethod("setAppName", String::class.java, Int::class.java)
            method.invoke(null, name, userId)
        } catch (tr: Throwable) {
            Ln.w(TAG + ": setAppName failed", tr)
        }
    }

    /**
     * 优先用 Context 构造器实例化服务，兼容无参构造器的旧实现。
     *
     * 返回值必须是 [IBinder]；类型错误由调用方统一记录并使启动失败。
     *
     * Instantiates a service through its Context constructor first, supporting legacy no-argument
     * constructors as a fallback. The result must be an [IBinder]; a type mismatch is logged by
     * the caller and fails startup.
     */
    @Throws(Exception::class)
    private fun instantiateService(serviceClass: Class<*>, context: Context): IBinder {
        return try {
            val constructor = serviceClass.getConstructor(Context::class.java)
            constructor.newInstance(context) as IBinder
        } catch (ignored: NoSuchMethodException) {
            val constructor = serviceClass.getDeclaredConstructor()
            constructor.isAccessible = true
            constructor.newInstance() as IBinder
        }
    }

    /**
     * 封装已创建服务及其 bootstrap 回传元数据。
     *
     * [userId] 由 Android uid 以每用户 100000 的规则计算，必须与 provider 查找 user 一致。
     *
     * Holds a created service and metadata needed for bootstrap handoff.
     *
     * [userId] is derived from Android uid with the 100000-per-user rule and must match the user
     * used for provider lookup.
     */
    data class CreatedService(
        val service: IBinder,
        val token: String,
        val packageName: String,
        val userId: Int,
    )

    /**
     * 保存 native launcher 传来的已校验启动参数。
     *
     * 仅 [parse] 构造本类型；字段缺失或 uid 非法时返回 null，避免反射加载未限定目标。
     *
     * Holds validated startup arguments supplied by the native launcher.
     *
     * Only [parse] constructs this type. Missing fields or an invalid uid return null, preventing
     * reflective loading of an unconstrained target.
     */
    private data class ParsedArgs(
        val token: String,
        val packageName: String,
        val className: String,
        val uid: Int,
        val debugName: String?,
        val contextOnly: Boolean,
    ) {
        companion object {
            /**
             * 解析 `--key=value` 启动参数并验证必填字段。
             *
             * 字段缺失时返回 null，调用方会在反射或创建 Binder 前终止启动。
             *
             * Parses `--key=value` startup arguments and validates required fields.
             *
             * Missing fields return null, so callers stop before reflection or Binder creation.
             */
            fun parse(args: Array<String>): ParsedArgs? {
                var token: String? = null
                var packageName: String? = null
                var className: String? = null
                var debugName: String? = null
                var uid = -1
                var contextOnly = false

                for (arg in args) {
                    if (arg.startsWith("--token=")) {
                        token = arg.substring(8)
                    } else if (arg.startsWith("--package=")) {
                        packageName = arg.substring(10)
                    } else if (arg.startsWith("--class=")) {
                        className = arg.substring(8)
                    } else if (arg.startsWith("--uid=")) {
                        uid = arg.substring(6).toInt()
                    } else if (arg.startsWith("--debug-name=")) {
                        debugName = arg.substring(13)
                    } else if (arg == "--context-only") {
                        contextOnly = true
                    }
                }

                if (token == null || packageName == null || className == null || uid < 0) {
                    Ln.e(TAG + ": missing required args")
                    return null
                }
                return ParsedArgs(token, packageName, className, uid, debugName, contextOnly)
            }
        }
    }
}
