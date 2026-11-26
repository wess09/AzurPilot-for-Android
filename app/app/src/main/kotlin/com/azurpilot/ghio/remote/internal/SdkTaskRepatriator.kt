package com.azurpilot.ghio.remote.internal

import android.content.Intent
import android.content.pm.PackageManager
import android.view.Display
import com.azurpilot.ghio.AppDispatchers
import com.azurpilot.ghio.BuildConfig
import com.azurpilot.ghio.constant.DefaultDisplayConfig
import com.azurpilot.ghio.third.FakeContext
import com.azurpilot.ghio.third.Ln
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch


/**
 * 渠道 SDK 弹页搬屏：游戏在虚拟屏运行时，把厂商 / 渠道 SDK 弹在主屏的
 * 登录、实名、支付页搬回虚拟屏。
 *
 * 背景：部分定制 ROM（如 vivo OriginOS）会把「自家系统包」（com.vivo.* 等）的
 * Activity 重定向回默认显示器。渠道服游戏的 SDK 登录页正是这类 Activity，
 * 被挡在主屏、压在宿主 App 窗口之下——用户看不见，采集与注入也够不着，
 * 游戏等不到登录回调就永远停在触发页（issue #8：vivo 服弹不出登录界面；
 * 类原生 ROM 上同一页面能正常落在虚拟屏，可作对照）。
 *
 * 记录在虚拟屏见过的游戏任务，整任务漂移或登录页重建任务时凭该记录搬回。游戏仍在虚拟屏时也检查
 * 新出现或换了顶层包的渠道弹页，包括与游戏同包的弹页。桌面、宿主和主屏存量任务不搬移，
 * 每个任务有重试上限。
 *
 * 线程模型：start/stop 由 binder 线程调用；单协程跑在 AppDispatchers.IO
 * limitedParallelism(1) 上，每秒一拍；已见任务表与已搬计数局限在本协程，
 * start() 里重置。
 *
 * Channel-SDK popup repatriation: while the game runs on the virtual display,
 * login / real-name / payment pages that vendor or channel SDKs pop onto the
 * primary display are moved back onto the virtual display.
 *
 * Background: some customized ROMs (e.g. vivo OriginOS) redirect activities of
 * their own system packages (com.vivo.*) to the default display. Channel-server
 * SDK login pages are exactly such activities; they end up stuck on the primary
 * display beneath the host app's window — invisible to the user and out of
 * reach for capture and injection, so the game waits forever on the login
 * callback (issue #8: the vivo-server login never shows; on near-AOSP ROMs the
 * same page lands on the virtual display fine, which serves as a control).
 *
 * Remembers game tasks observed on the virtual display and brings them back if they drift or
 * login creates another game task. While the game is on the virtual display, it checks SDK pages,
 * including pages packaged in the game APK. It excludes home, the host app, and existing
 * primary-display tasks and limits attempts per task.
 *
 * Threading: start/stop run on binder threads; a single coroutine on
 * AppDispatchers.IO limitedParallelism(1) ticks every second; the seen-task
 * table and the moved counts are confined to the loop and reset in start().
 */
object SdkTaskRepatriator {

    /** 轮询周期 / Poll period */
    private const val POLL_INTERVAL_MS = 1000L

    /**
     * 同一任务搬回主屏次数上限：搬回后又被 ROM 重定向回来算一次对抗，超过即放手，
     * 避免与系统无限拉锯把弹页卡在两屏之间
     *
     * Cap on how often one task is moved back: each time the ROM redirects it to the
     * primary display again counts as one round of pushback; past the cap we let go to
     * avoid an endless tug-of-war that leaves the popup stranded between displays.
     */
    private const val MAX_MOVES_PER_TASK = 3

    /**
     * 渠道 SDK / 厂商系统包前缀白名单。这些包通常没有用户会主动点开的入口，
     * 出现在主屏上多半是游戏触发的弹页（登录 / 实名 / 支付 / 防沉迷），命中才搬。
     * 新渠道接入时在此追加前缀即可。
     *
     * Channel-SDK / vendor-system package prefixes. These packages usually have
     * no entry a user would open by hand; appearing on the primary display most
     * likely means a game-triggered popup (login / real-name / payment /
     * anti-addiction). Only hits are moved; append prefixes here for new
     * channels.
     */
    private val SDK_PACKAGE_PREFIXES = listOf(
        "com.vivo.", "com.bbk.",                                                 // vivo / iQOO
        "com.oplus.", "com.heytap.", "com.coloros.", "com.oppo.", "com.nearme.", // OPPO / 一加 / realme
        "com.xiaomi.", "com.miui.",                                              // 小米 / Redmi
        "com.huawei.", "com.hihonor.", "com.hicloud.",                           // 华为 / 荣耀
        "com.meizu.", "com.flyme.",                                              // 魅族
        "com.samsung.", "com.sec.",                                              // 三星
        "com.lenovo.", "com.zui.",                                               // 联想 / 拯救者
        "com.tencent.midas.", "com.tencent.ysdk.",                               // 腾讯支付 / 应用宝 YSDK
        "com.alipay.sdk.",                                                       // 支付宝 H5 支付
        "com.bilibili.", "com.m4399."                                            // B 服 / 4399
    )

    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.IO.limitedParallelism(1))
    private var job: Job? = null

    /** 单个显示器的轮询记录。 / Polling state for one display. */
    private class Watch(val displayId: Int) {
        var primed = false
        var target: String? = null
        var observedGame = false
        val gameTasks = mutableSetOf<Int>()
        val seen = mutableMapOf<Int, String?>()
        val attempts = mutableMapOf<Int, Int>()
    }

    /**
     * 各家 ROM 桌面（含 vivo 的 com.bbk.launcher2，恰好落在厂商前缀白名单内，
     * 必须显式排除）；systemui 一并排除
     *
     * Home launchers of all ROMs — vivo's com.bbk.launcher2 included, which
     * would otherwise hit the vendor-prefix allowlist and must be excluded
     * explicitly; systemui is excluded as well.
     */
    private val homePackages: Set<String> by lazy {
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            FakeContext.get().packageManager
                .queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
                .mapTo(mutableSetOf()) { it.activityInfo.packageName }
        }.onFailure { Ln.w("SdkTaskRepatriator: resolve home packages failed: ${it.message}") }
            .getOrDefault(emptySet()) + "com.android.systemui"
    }

    /**
     * 开始盯防（虚拟屏建好即调）；重复调用先停旧循环
     *
     * Starts watching (called once the virtual display is up); a repeated call stops the old loop first.
     */
    fun start() {
        stop()
        Ln.i("SdkTaskRepatriator: start")
        job = scope.launch {
            var watch = Watch(DefaultDisplayConfig.DISPLAY_NONE)
            while (isActive) {
                val displayId = VirtualDisplayManager.getDisplayId()
                if (displayId != watch.displayId) watch = Watch(displayId)
                if (displayId != DefaultDisplayConfig.DISPLAY_NONE) {
                    runCatching { tick(watch) }.onFailure {
                        Ln.w("SdkTaskRepatriator: tick failed: ${it.message}")
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** 停止轮询并清空会话目标。 / Stops polling and clears the session target. */
    fun stop() {
        job?.cancel()
        job = null
        ActivityUtils.lastLaunchedPackage = null
    }

    private fun tick(watch: Watch) {
        val tasks = ActivityUtils.snapshotRunningTasks() ?: return
        val onPrimary = tasks.filter { it.displayId == Display.DEFAULT_DISPLAY }
        val target = ActivityUtils.lastLaunchedPackage
        if (target != watch.target) {
            watch.target = target
            watch.observedGame = false
            watch.gameTasks.clear()
            watch.attempts.clear()
        }
        val onVirtual = if (target == null) emptyList() else tasks.filter {
            it.displayId == watch.displayId && (it.topPackage == target || it.basePackage == target)
        }
        if (onVirtual.isNotEmpty()) watch.observedGame = true
        watch.gameTasks.retainAll(tasks.mapTo(mutableSetOf()) { it.taskId })
        onVirtual.mapTo(watch.gameTasks) { it.taskId }

        if (!watch.primed) {
            onPrimary.forEach { watch.seen[it.taskId] = it.topPackage }
            watch.primed = true
            return
        }

        watch.seen.keys.retainAll(onPrimary.mapTo(mutableSetOf()) { it.taskId })
        for (task in onPrimary) {
            val pkg = task.topPackage
            val previous = watch.seen.put(task.taskId, pkg)
            if (pkg == null || target == null || pkg == BuildConfig.APPLICATION_ID || pkg in homePackages) continue
            val attempts = watch.attempts.getOrDefault(task.taskId, 0)
            if (attempts >= MAX_MOVES_PER_TASK) continue

            val appeared = pkg != previous || attempts > 0
            // 登录可能重建游戏任务，虚拟屏空时也要接回已确认会话里的同包新任务。
            val gameTask = pkg == target || task.basePackage == target
            val drifted = gameTask && (task.taskId in watch.gameTasks || (watch.observedGame && appeared))
            val sdkPopup = onVirtual.isNotEmpty() && appeared &&
                SDK_PACKAGE_PREFIXES.any { pkg.startsWith(it) }
            if (!drifted && !sdkPopup) continue

            if (VirtualDisplayManager.getDisplayId() != watch.displayId ||
                ActivityUtils.lastLaunchedPackage != target
            ) return
            watch.attempts[task.taskId] = attempts + 1
            Ln.w(
                "SdkTaskRepatriator: moving $pkg (id=${task.taskId}) to display ${watch.displayId} " +
                    "(attempt ${attempts + 1}/$MAX_MOVES_PER_TASK)"
            )
            if (!ActivityUtils.moveTaskById(task.taskId, watch.displayId)) {
                Ln.e("SdkTaskRepatriator: failed to move task ${task.taskId} ($pkg) to display ${watch.displayId}")
            }
        }
    }
}
