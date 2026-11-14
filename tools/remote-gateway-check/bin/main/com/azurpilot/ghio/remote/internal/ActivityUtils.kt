package com.azurpilot.ghio.remote.internal

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.Display
import com.azurpilot.ghio.bridge.InputControlUtils
import com.azurpilot.ghio.third.FakeContext
import com.azurpilot.ghio.third.Ln
import com.azurpilot.ghio.third.wrappers.ServiceManager

/**
 * Activity / Task 层工具：以 shell 身份跨屏启动与搬运应用任务
 *
 * 全程隐藏 API + 反射，任一依赖不可用即走对应兜底，绝不向上抛。
 * 跑在特权进程内，被 RemoteServiceImpl（binder 线程）与 [AppWatchdog]（IO 协程）调用；
 * 拉回与校验路径内部会 sleep 轮询，调用方需容忍阻塞。
 *
 * 反射依赖三项：ActivityOptions.setLaunchWindowingMode、IActivityTaskManager 的
 * moveRootTaskToDisplay / moveStackToDisplay、RunningTaskInfo.displayId 字段。
 *
 * Activity and task utilities: launches and moves app tasks across displays with shell
 * identity.
 *
 * Everything rides on hidden APIs and reflection; when any dependency is unavailable the
 * matching fallback kicks in — nothing is ever thrown upward. Runs inside the privileged
 * process, called from RemoteServiceImpl (binder threads) and [AppWatchdog] (an IO coroutine);
 * the repin and verification paths sleep and poll internally, so callers must tolerate
 * blocking.
 *
 * Three reflective dependencies: ActivityOptions.setLaunchWindowingMode, the
 * moveRootTaskToDisplay / moveStackToDisplay methods of IActivityTaskManager, and the
 * RunningTaskInfo.displayId field.
 */
@SuppressLint("BlockedPrivateApi")
object ActivityUtils {

    // android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
    private const val WINDOWING_MODE_FULLSCREEN = 1

    // getAppDisplayId：包名无任何运行中任务
    private const val DISPLAY_NO_TASK = -2

    // 启动后等待任务出现在目标屏的窗口
    private const val APP_PIN_TIMEOUT_MS = 10_000L
    private const val APP_PIN_POLL_INTERVAL_MS = 500L

    // 拉回操作后等系统完成 reparent 的时间
    private const val REPIN_SETTLE_MS = 1_000L

    /**
     * 虚拟屏启动是否强制 FULLSCREEN 窗口模式（app 侧经 RemoteServiceImpl 下发）
     *
     * Whether launches onto the virtual display force FULLSCREEN windowing (pushed by the app
     * via RemoteServiceImpl).
     */
    @Volatile
    var forceFullscreenOnVirtualDisplay: Boolean = false

    /**
     * 最近一次经桥启动的包名（已拆成纯包名）；[SdkTaskRepatriator] 用它判断
     * 「自动化会话已开始」并排除游戏本体，虚拟屏停止时由 [SdkTaskRepatriator.stop] 清空
     *
     * The most recent package launched through the bridge (flattened to a plain package name);
     * [SdkTaskRepatriator] uses it to tell "an automation session has started" and to exclude
     * the game itself, and [SdkTaskRepatriator.stop] clears it when the virtual display stops.
     */
    @Volatile
    var lastLaunchedPackage: String? = null

    private val setLaunchWindowingMode by lazy {
        runCatching {
            ActivityOptions::class.java
                .getDeclaredMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .also { it.isAccessible = true }
        }.onFailure { Ln.w("setLaunchWindowingMode: reflection failed", it) }.getOrNull()
    }

    /**
     * 以 shell 身份启动指定 Intent 的 Activity，绕过 BAL 限制。
     * [forceFullscreen] 为 true 时无视用户设置强制 FULLSCREEN 窗口模式（用于漂移拉回重试）。
     *
     * Starts the activity for the given intent as shell, bypassing background-activity-launch
     * restrictions. With [forceFullscreen] set, FULLSCREEN windowing mode is forced regardless
     * of user settings (used by drift-repin retries).
     *
     * binder 调用返回负数或抛异常时降级为 `am start` 命令行兜底。
     * Falls back to the `am start` command when the binder call returns a negative code or
     * throws.
     *
     * @param intent 要启动的 Intent / the intent to launch
     * @param displayId 目标屏，默认主屏 / target display, defaults to the primary display
     * @param forceFullscreen 强制全屏窗口模式 / forces fullscreen windowing mode
     * @return true=启动成功（含兜底路径）/ true on success (including the fallback path)
     */
    @JvmStatic
    @JvmOverloads
    fun startActivity(intent: Intent, displayId: Int = 0, forceFullscreen: Boolean = false): Boolean {
        val am = ServiceManager.getActivityManager()
        try {
            val launchOptions = ActivityOptions.makeBasic()
            if (displayId != Display.DEFAULT_DISPLAY) {
                launchOptions.launchDisplayId = displayId
                if (forceFullscreenOnVirtualDisplay || forceFullscreen) {
                    runCatching {
                        setLaunchWindowingMode?.invoke(launchOptions, WINDOWING_MODE_FULLSCREEN)
                    }.onFailure {
                        Ln.e("invoke setLaunchWindowingMode failed", it)
                    }
                }
            }
            val ret = try {
                am.startActivity(intent, launchOptions.toBundle())
            } catch (e: Exception) {
                Ln.w("startActivity failed, returning -1", e)
                -1
            }
            if (ret < 0) {
                Ln.w("startActivity returned error code $ret, fallback to am command")
                return startViaAmCommand(intent, displayId)
            }
            return true
        } catch (e: Exception) {
            Ln.w("startActivity failed, fallback to am command", e)
            return startViaAmCommand(intent, displayId)
        }
    }

    /**
     * PI 的 StartApp 允许把 package 写成 `包名/Activity` 的 component 全名（M9A 的 startup.json 即是），
     * 官方 adb controller 原样塞进 am start 所以两种都能用；走 PackageManager 与包名比对的地方必须先拆
     * 拆不出来时原样返回，让调用方按纯包名走既有失败路径
     *
     * PI's StartApp allows the package field to be a full `package/Activity` component name
     * (M9A's startup.json does this); the official adb controller passes it verbatim to
     * `am start`, so both forms work there. Every site comparing against PackageManager or a
     * package name must flatten it first. Unparseable input is returned as-is so callers keep
     * their existing plain-package failure path.
     */
    @JvmStatic
    fun packageNameOf(spec: String): String = componentOf(spec)?.packageName ?: spec

    /** `包名/Activity` 形态才拆，纯包名返回 null / Flattens only `package/Activity` forms; null for a plain package name */
    private fun componentOf(spec: String): ComponentName? =
        spec.takeIf { it.contains('/') }?.let { ComponentName.unflattenFromString(it) }

    /**
     * 启动指定应用到 [displayId]：先强停清场，再投放 launcher intent
     *
     * [packageName] 允许 `包名/Activity` component 全名（见 [packageNameOf]）；
     * launcher 与 leanback intent 都解析不到时返回 false。
     *
     * Launches the given app onto [displayId]: force-stops it first for a clean slate, then
     * fires the launcher intent.
     *
     * [packageName] accepts a full `package/Activity` component name (see [packageNameOf]);
     * returns false when neither a launcher nor a leanback launch intent can be resolved.
     *
     * @param forceStop 启动前先强停目标包，保证落在目标屏的是新任务 / force-stops the package before launch so a fresh task lands on the target display
     * @param excludeFromRecents 加 EXCLUDE_FROM_RECENTS，最近任务里不露虚拟屏任务 / adds EXCLUDE_FROM_RECENTS so the virtual-display task stays out of Recents
     * @return 是否成功发起启动 / whether the launch was dispatched successfully
     */
    @JvmStatic
    @JvmOverloads
    fun startApp(
        packageName: String,
        displayId: Int,
        forceStop: Boolean = true,
        excludeFromRecents: Boolean = true
    ): Boolean {
        val pm = FakeContext.get().packageManager

        val component = componentOf(packageName)
        val targetPackage = component?.packageName ?: packageName

        val intent = if (component != null) {
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
        } else {
            pm.getLaunchIntentForPackage(packageName)
                ?: pm.getLeanbackLaunchIntentForPackage(packageName)
        }

        if (intent == null) {
            Ln.w("Cannot create launch intent for app $packageName")
            return false
        }

        var flag = Intent.FLAG_ACTIVITY_NEW_TASK
        if (excludeFromRecents) {
            flag = flag or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        }
        intent.addFlags(flag)

        if (forceStop) {
            ServiceManager.getActivityManager().forceStopPackage(targetPackage)
        }
        if (displayId > 0 && displayId == VirtualDisplayManager.getDisplayId()) {
            try {
                GameResolutionController.prepare(targetPackage, displayId)
            } catch (error: Exception) {
                Ln.e("Native resolution preparation failed for $targetPackage", error)
                return false
            }
        }
        Ln.i("startApp ${intent.component?.flattenToShortString()}")
        lastLaunchedPackage = targetPackage

        return startActivity(intent, displayId)
    }

    /**
     * 返回运行在 [displayId] 上的最顶层 app 包名；没有任务或 API 不支持时返回 null。
     * 看门狗用它从虚拟屏反推目标 app，无需外部告知包名。
     *
     * Returns the top app package running on [displayId]; null when there is no task or the
     * API is unavailable. The watchdog uses it to infer the target app from the virtual display
     * instead of being told the package externally.
     */
    fun getTopPackageOnDisplay(displayId: Int): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (taskDisplayIdField == null) return null
        return runCatching {
            val am = FakeContext.get().getSystemService(ActivityManager::class.java) ?: return null
            @Suppress("DEPRECATION")
            am.getRunningTasks(100).firstOrNull { task -> getTaskDisplayId(task) == displayId }
                ?.topActivity?.packageName
        }.onFailure { Ln.w("getTopPackageOnDisplay: failed", it) }.getOrNull()
    }

    /**
     * 等待目标应用的副屏窗口接收聚焦序列；只操作当前虚拟屏上的指定包。
     *
     * 启动后 Activity 和输入窗口不一定同时就绪，最多重试 3 秒；无法确认顶层包时不注入。
     * 特权工作线程调用，持显示生命周期锁，防止等待期间虚拟屏被重建。
     *
     * Waits for the app's secondary window to accept focus, targeting only the current virtual display.
     *
     * Activities and input windows can become ready at different times after launch, so it retries
     * for up to three seconds. No input is injected without verifying the top package. Privileged
     * worker threads hold the display lifecycle lock to prevent recreation during the wait.
     */
    fun focusAppOnDisplay(packageName: String, displayId: Int): Boolean =
        synchronized(displayLifecycleLock) {
            if (displayId <= 0 || displayId != VirtualDisplayManager.getDisplayId()) {
                return@synchronized false
            }
            val target = packageNameOf(packageName)
            val deadline = SystemClock.uptimeMillis() + 3_000L
            do {
                if (getTopPackageOnDisplay(displayId) == target &&
                    InputControlUtils.requestFocus(displayId)
                ) {
                    Ln.i("focusAppOnDisplay: focused $target on display $displayId")
                    return@synchronized true
                }
                SystemClock.sleep(100L)
            } while (SystemClock.uptimeMillis() < deadline)
            Ln.w("focusAppOnDisplay: no ready window for $target on display $displayId")
            false
        }

    /**
     * 检查指定包名的应用最近的活动 task 是否运行在给定 displayId 上。
     * API 28 无 TaskInfo.displayId 字段（@hide），宽松返回 true（不拦截）。
     * 任何异常也宽松返回 true，避免误伤。
     *
     * Checks whether the app's most recent active task runs on [targetDisplayId].
     * API 28 lacks the hidden TaskInfo.displayId field, so it leniently returns true (no
     * interception); any exception is also leniently true to avoid false positives.
     */
    fun isAppOnDisplay(packageName: String, targetDisplayId: Int): Boolean {
        return when (getAppDisplayId(packageName)) {
            null -> true // 无法判断，宽松放行
            targetDisplayId -> true
            else -> false
        }
    }

    /**
     * 启动后校验：等待 [packageName] 的任务出现在 [displayId] 上；若发现任务落在其它
     * display（如 One UI / 部分 ROM 会把游戏从虚拟屏挪回主屏，B 服 U8 SDK 二段跳也可能
     * 丢失 launchDisplayId），立即尝试拉回。仅在确认漂移且拉回失败时返回 false。
     *
     * Post-launch verification: waits for [packageName]'s task to appear on [displayId]; if the
     * task is seen on another display (One UI and some ROMs move the game back to the primary
     * display, and the B-server U8 SDK's two-stage launch can also drop launchDisplayId), a
     * repin is attempted immediately. Returns false only when drift is confirmed and the repin
     * fails.
     *
     * @param timeoutMs 等待任务出现的窗口，超时未观测到也宽松放行 / window waited for the task to appear; a timeout without a sighting passes leniently too
     */
    @JvmStatic
    @JvmOverloads
    fun ensureAppOnDisplay(
        packageName: String,
        displayId: Int,
        timeoutMs: Long = APP_PIN_TIMEOUT_MS
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            when (val current = getAppDisplayId(packageName)) {
                null -> return true // 无法判断，宽松放行
                displayId -> return true
                DISPLAY_NO_TASK -> Unit // 任务尚未出现，继续等待
                else -> {
                    Ln.w("ensureAppOnDisplay: $packageName drifted to display $current (expected $displayId), trying to move it back")
                    return repinAppToDisplay(packageName, displayId)
                }
            }
            if (SystemClock.uptimeMillis() >= deadline) {
                Ln.w("ensureAppOnDisplay: no task of $packageName observed within ${timeoutMs}ms, passing leniently")
                return true
            }
            SystemClock.sleep(APP_PIN_POLL_INTERVAL_MS)
        }
    }

    /**
     * 把 [packageName] 的任务拉回 [displayId]：
     * 1. moveRootTaskToDisplay / moveStackToDisplay（hidden API，shell 有 MANAGE_ACTIVITY_TASKS）
     * 2. am display move-stack 命令兜底
     * 3. 重新投放 launch intent（强制 FULLSCREEN），让系统 reparent 现有任务
     *
     * Moves [packageName]'s task back to [displayId]:
     * 1. moveRootTaskToDisplay / moveStackToDisplay (hidden APIs; shell holds
     *    MANAGE_ACTIVITY_TASKS)
     * 2. the `am display move-stack` command as fallback
     * 3. re-firing the launch intent (forced FULLSCREEN) so the system reparents the existing
     *    task
     */
    @JvmStatic
    fun repinAppToDisplay(packageName: String, displayId: Int): Boolean {
        if (moveAppTaskToDisplay(packageName, displayId)) {
            SystemClock.sleep(REPIN_SETTLE_MS)
            if (isAppOnDisplay(packageName, displayId)) {
                Ln.i("repinAppToDisplay: $packageName moved back to display $displayId")
                return true
            }
        }
        val pm = FakeContext.get().packageManager
        val intent = pm.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            lastLaunchedPackage = packageNameOf(packageName)
            if (startActivity(intent, displayId, forceFullscreen = true)) {
                SystemClock.sleep(REPIN_SETTLE_MS)
                if (isAppOnDisplay(packageName, displayId)) {
                    Ln.i("repinAppToDisplay: $packageName relaunched onto display $displayId")
                    return true
                }
            }
        }
        Ln.e("repinAppToDisplay: failed to pin $packageName on display $displayId")
        return false
    }

    /**
     * 返回 [packageName] 最近任务所在的 displayId。
     * null = 无法判断（API < Q / 反射失败 / 异常）；[DISPLAY_NO_TASK] = 无运行中任务。
     * 取最近任务而非任意任务：漂移时新任务落在主屏，虚拟屏上可能残留旧任务。
     *
     * Returns the display of [packageName]'s most recent task.
     * null = undeterminable (API < Q, reflection failure, or exception); [DISPLAY_NO_TASK] = no
     * running task. The most recent task is used rather than any task: on drift the new task
     * lands on the primary display while a stale one may linger on the virtual display.
     */
    private fun getAppDisplayId(packageName: String): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (taskDisplayIdField == null) return null
        return runCatching {
            val task = findRecentTask(packageName) ?: return@runCatching DISPLAY_NO_TASK
            getTaskDisplayId(task).takeIf { it >= 0 }
        }.getOrNull()
    }

    /** 找不到任务返回 null（区分「无任务」与「判不出」，见 [getAppDisplayId]）/ null when no task matches (distinguishes "no task" from "undeterminable", see [getAppDisplayId]) */
    private fun findRecentTask(packageName: String): ActivityManager.RunningTaskInfo? {
        val am = FakeContext.get().getSystemService(ActivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        return am.getRunningTasks(100).firstOrNull { task ->
            task.topActivity?.packageName == packageName
                    || task.baseActivity?.packageName == packageName
        }
    }

    /**
     * 全量任务快照（taskId / displayId / 顶层包名）；API < Q 或反射不可用时返回 null。
     * [SdkTaskRepatriator] 每秒一拍用它做增量比对。
     *
     * A snapshot of all running tasks (taskId / displayId / top package); null on API < Q or
     * when reflection is unavailable. [SdkTaskRepatriator] diffs it tick by tick every second.
     */
    @JvmStatic
    fun snapshotRunningTasks(): List<TaskOnDisplay>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (taskDisplayIdField == null) return null
        return runCatching {
            val am = FakeContext.get().getSystemService(ActivityManager::class.java) ?: return null
            @Suppress("DEPRECATION")
            am.getRunningTasks(100).map { task ->
                TaskOnDisplay(
                    taskId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) task.taskId else task.id,
                    displayId = getTaskDisplayId(task),
                    topPackage = task.topActivity?.packageName
                )
            }
        }.onFailure { Ln.w("snapshotRunningTasks: failed", it) }.getOrNull()
    }

    /** [snapshotRunningTasks] 的单条记录 / One entry of [snapshotRunningTasks] */
    data class TaskOnDisplay(
        val taskId: Int,
        val displayId: Int,
        val topPackage: String?
    )

    /** 隐藏 API 搬任务，失败再试 am 命令 / Moves the task via the hidden API, falling back to the am command */
    private fun moveAppTaskToDisplay(packageName: String, displayId: Int): Boolean {
        val task = runCatching { findRecentTask(packageName) }.getOrNull() ?: run {
            Ln.w("moveAppTaskToDisplay: no running task of $packageName")
            return false
        }
        @Suppress("DEPRECATION")
        val taskId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) task.taskId else task.id
        return moveTaskById(taskId, displayId)
    }

    /**
     * 按 taskId 把任务搬到 [displayId]：moveRootTaskToDisplay / moveStackToDisplay
     * （hidden API，shell 有 MANAGE_ACTIVITY_TASKS），失败再走 am 命令兜底
     *
     * Moves the task with [taskId] onto [displayId] via moveRootTaskToDisplay /
     * moveStackToDisplay (hidden APIs; shell holds MANAGE_ACTIVITY_TASKS), falling back to the
     * am command.
     */
    @JvmStatic
    fun moveTaskById(taskId: Int, displayId: Int): Boolean {
        moveTaskToDisplayMethod?.let { method ->
            runCatching {
                method.invoke(activityTaskManager, taskId, displayId)
                Ln.i("moveTaskById: ${method.name}($taskId, $displayId) invoked")
                return true
            }.onFailure { Ln.w("moveTaskById: ${method.name} failed", it) }
        }
        return moveTaskViaAmCommand(taskId, displayId)
    }

    private val activityTaskManager by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("getService", String::class.java)
                .invoke(null, "activity_task")
            Class.forName("android.app.IActivityTaskManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        }.onFailure { Ln.w("activityTaskManager: reflection failed", it) }.getOrNull()
    }

    // API 31+: moveRootTaskToDisplay；API 29/30: moveStackToDisplay
    private val moveTaskToDisplayMethod by lazy {
        val atm = activityTaskManager ?: return@lazy null
        sequenceOf("moveRootTaskToDisplay", "moveStackToDisplay").firstNotNullOfOrNull { name ->
            runCatching {
                atm.javaClass.getMethod(name, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            }.getOrNull()
        } ?: run {
            Ln.w("moveTaskToDisplayMethod: no candidate method found")
            null
        }
    }

    /** `am display move-stack` 兜底 / `am display move-stack` fallback */
    private fun moveTaskViaAmCommand(taskId: Int, displayId: Int): Boolean {
        return try {
            val args = arrayOf("am", "display", "move-stack", taskId.toString(), displayId.toString())
            Ln.i("moveTaskViaAmCommand: exec: ${args.joinToString(" ")}")
            val process = Runtime.getRuntime().exec(args)
            val exitCode = process.waitFor()
            val stderr = process.errorStream.bufferedReader().use { it.readText() }.trim()
            if (stderr.isNotEmpty()) Ln.w("moveTaskViaAmCommand: am stderr: $stderr")
            if (exitCode != 0) {
                Ln.w("moveTaskViaAmCommand: am exited with code $exitCode")
                return false
            }
            true
        } catch (e: Exception) {
            Ln.e("moveTaskViaAmCommand: am command failed", e)
            false
        }
    }

    // displayId 字段随版本在 TaskInfo 及其父类间挪动，沿继承链向上找
    private val taskDisplayIdField by lazy {
        runCatching {
            var cls: Class<*>? = ActivityManager.RunningTaskInfo::class.java
            var field: java.lang.reflect.Field? = null
            while (cls != null && field == null) {
                field = runCatching { cls.getDeclaredField("displayId") }.getOrNull()
                cls = cls.superclass
            }
            field?.also { it.isAccessible = true }
        }.onFailure { Ln.w("taskDisplayIdField: reflection failed", it) }.getOrNull()
    }

    private fun getTaskDisplayId(task: ActivityManager.RunningTaskInfo): Int =
        runCatching { taskDisplayIdField?.getInt(task) ?: -1 }.getOrDefault(-1)

    /** `am start` 命令行兜底 / `am start` command-line fallback */
    private fun startViaAmCommand(intent: Intent, displayId: Int): Boolean {
        try {
            val intentUri = intent.toUri(Intent.URI_INTENT_SCHEME)
            val args = if (displayId == Display.DEFAULT_DISPLAY) {
                arrayOf("am", "start", intentUri)
            } else {
                arrayOf("am", "start", "--display", displayId.toString(), intentUri)
            }
            Ln.i("startViaAmCommand: displayId=$displayId, exec: ${args.joinToString(" ")}")
            val process = Runtime.getRuntime().exec(args)
            val exitCode = process.waitFor()
            // am start 输出量极小（远小于管道缓冲），先 waitFor 再读不会死锁
            val stdout = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val stderr = process.errorStream.bufferedReader().use { it.readText() }.trim()
            if (stdout.isNotEmpty()) Ln.i("startViaAmCommand: am stdout: $stdout")
            if (stderr.isNotEmpty()) Ln.w("startViaAmCommand: am stderr: $stderr")
            if (exitCode != 0) {
                Ln.w("startViaAmCommand: am exited with code $exitCode")
                return false
            }
            Ln.i("startViaAmCommand: success (exitCode=0)")
            return true
        } catch (e: Exception) {
            Ln.e("startViaAmCommand: am command fallback failed", e)
            return false
        }
    }
}
