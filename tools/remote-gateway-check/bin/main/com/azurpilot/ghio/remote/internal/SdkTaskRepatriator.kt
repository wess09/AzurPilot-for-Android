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
 * 策略：游戏任务自身的漂移由启动校验（ensureAppOnDisplay）与 AppWatchdog 负责，本对象
 * 只盯**主屏上新出现或换了顶层包**的任务：顶层包命中渠道 SDK / 厂商系统包白名单、或就是
 * 游戏包名自身——渠道 SDK 的 Activity 打包在游戏 APK 内（如 vivo 服碧蓝航线
 * com.bilibili.blhx.vivo 里的 com.vivo.* 登录页），组件包名与游戏一致，落回主屏时顶层包名
 * 看起来就是「游戏本身」，旧版恰在此把它放走（issue #8 修复后复测仍卡的原因）——又不是
 * 宿主 App 与桌面，且游戏任务确实在虚拟屏上时，搬到虚拟屏。只动增量、每任务搬回次数有上限
 * （ROM 可能对抗重定向），用户自己打开的旧应用与桌面一律不碰。
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
 * Policy: drift of the game task itself is the launch-time check's job
 * (ensureAppOnDisplay) plus the AppWatchdog. This watcher only looks at tasks
 * **newly appeared or top-changed** on the primary display: when the top package
 * hits the channel-SDK / vendor-system allowlist **or is the game package
 * itself** — channel-SDK activities are packaged inside the game APK (the
 * com.vivo.* login pages inside the vivo-server AzurLane package
 * com.bilibili.blhx.vivo, say), so their component package equals the game's and
 * a stranded page's top package reads exactly like "the game", which the old
 * exclusion let slip (why the issue #8 fix still failed on retest) — and is
 * neither the host app nor a launcher, and the game task is actually on the
 * virtual display, the task is moved over. Only increments are touched, moves
 * per task are capped (the ROM may redirect back in a fight), and apps the user
 * opened themselves and the launcher are never touched.
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

    /** 基线是否已建立；首拍只记录不搬 / Whether the baseline is primed; the first tick only records */
    private var primed = false

    /** 已见主屏任务：taskId -> 顶层包名 / Tasks seen on the primary display: taskId -> top package */
    private val seen = HashMap<Int, String?>()

    /** 已搬过的任务与次数：taskId -> 已搬次数，到 [MAX_MOVES_PER_TASK] 为止 / Moved tasks with counts: taskId -> times moved, up to [MAX_MOVES_PER_TASK] */
    private val movedCount = HashMap<Int, Int>()

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
        primed = false
        seen.clear()
        movedCount.clear()
        Ln.i("SdkTaskRepatriator: start")
        job = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                runCatching { tick() }.onFailure {
                    Ln.w("SdkTaskRepatriator: tick failed: ${it.message}")
                }
            }
        }
    }

    /** 停止轮询并清状态；顺带清掉「游戏已启动」标记，见 [ActivityUtils.lastLaunchedPackage] */
    fun stop() {
        job?.cancel()
        job = null
        ActivityUtils.lastLaunchedPackage = null
    }

    /**
     * 单拍：虚拟屏不在或快照失败时静默等下一拍；基线未建立则先记录存量任务。
     * 游戏任务不在虚拟屏上（未启动 / 已死）时不做任何搬移。
     */
    private fun tick() {
        val displayId = VirtualDisplayManager.getDisplayId()
        if (displayId == DefaultDisplayConfig.DISPLAY_NONE) {
            primed = false
            seen.clear()
            return
        }
        val tasks = runCatching { ActivityUtils.snapshotRunningTasks() }.getOrNull() ?: return
        val onPrimary = tasks.filter { it.displayId == Display.DEFAULT_DISPLAY }

        if (!primed) {
            // 基线快照：盯防开始前已在主屏的任务一律视为存量，不搬——
            // 桌面、用户手开的应用都在其中
            onPrimary.forEach { seen[it.taskId] = it.topPackage }
            primed = true
            return
        }

        val target = ActivityUtils.lastLaunchedPackage
        val gameOnVirtualDisplay = target != null &&
                tasks.any { it.displayId == displayId && it.topPackage == target }
        for (task in onPrimary) {
            val pkg = task.topPackage
            val prev = seen.put(task.taskId, pkg)
            // 出现判定：新任务、换了顶层包，或是被我们搬回后又被 ROM 重定向回主屏
            // Appeared: a new task, a top-package change, or a moved task the ROM sent back
            val appeared = prev == null || (pkg != null && pkg != prev) || task.taskId in movedCount
            if (!appeared) continue
            if (pkg == null || !gameOnVirtualDisplay || !qualifies(pkg, target)) {
                Ln.d("SdkTaskRepatriator: task ${task.taskId} ($pkg) on the primary display, ignored")
                continue
            }
            val attempts = movedCount.getOrDefault(task.taskId, 0)
            if (attempts >= MAX_MOVES_PER_TASK) {
                // 放手即清计数：留着会因「taskId in movedCount」每拍都判成新出现而刷屏；
                // 顶层包再变（任务里换了新页）时仍会经 appeared 重新进来重新计数
                movedCount.remove(task.taskId)
                Ln.w(
                    "SdkTaskRepatriator: task $pkg (id=${task.taskId}) returned to the primary " +
                    "display $attempts times, giving up to avoid a tug-of-war"
                )
                continue
            }
            Ln.w(
                "SdkTaskRepatriator: SDK/game task $pkg (id=${task.taskId}) appeared on the " +
                "primary display while the game runs on the virtual display, moving it back " +
                "(attempt ${attempts + 1}/$MAX_MOVES_PER_TASK)"
            )
            if (runCatching { ActivityUtils.moveTaskById(task.taskId, displayId) }.getOrDefault(false)) {
                movedCount[task.taskId] = attempts + 1
            } else {
                Ln.e("SdkTaskRepatriator: failed to move task ${task.taskId} ($pkg) to display $displayId")
            }
        }
    }

    /**
     * 白名单 + 排除项判定。**游戏包名自身的任务要收**：渠道 SDK 的 Activity 打包在游戏
     * APK 内，组件包名就是游戏包名（vivo 服碧蓝航线 com.bilibili.blhx.vivo 里的
     * com.vivo.* 登录页），落回主屏时顶层包名与游戏本体一模一样——旧版在此放走它，
     * 是 issue #8 修复后复测仍卡的原因。「游戏不在虚拟屏」的 gate 已挡掉无会话场景，
     * 用户在主屏手开游戏前虚拟屏上必然先有游戏任务。
     *
     * Allowlist and exclusion check. Tasks of the game package itself ARE collected:
     * channel-SDK activities are packaged inside the game APK, so their component
     * package is the game's own package (the com.vivo.* login pages inside the
     * vivo-server AzurLane com.bilibili.blhx.vivo), and a stranded page's top package
     * reads exactly like the game itself — the old exclusion let it slip, which is why
     * issue #8 still failed on retest. The "game on the virtual display" gate already
     * screened the no-session case: the game task exists on the virtual display before
     * the user could open the game on the primary display by hand.
     */
    private fun qualifies(pkg: String, target: String?): Boolean {
        if (target == null) return false // 游戏还没经桥启动过，不存在 SDK 弹页，一律不碰
        if (pkg == BuildConfig.APPLICATION_ID) return false // 宿主 App 自己 / the host app itself
        if (pkg in homePackages) return false // 桌面 / the launcher
        return pkg == target || SDK_PACKAGE_PREFIXES.any { pkg.startsWith(it) }
    }
}
