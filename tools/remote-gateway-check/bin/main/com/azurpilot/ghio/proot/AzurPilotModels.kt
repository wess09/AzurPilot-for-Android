package com.azurpilot.ghio.proot

import android.graphics.Bitmap

/**
 * 实例状态；网关的 `STATES` 1..4
 *
 * Instance status, mirroring the gateway's `STATES` 1..4.
 */
enum class AzurPilotStatus {
    Running, Stopped, Error, Updating;

    companion object {
        /**
         * 把网关的原始状态串折成枚举；未知或缺失一律退回 [Stopped]
         *
         * Folds a raw gateway status string into the enum; unknown or missing
         * values fall back to [Stopped].
         */
        fun of(raw: String?): AzurPilotStatus = when (raw) {
            "running" -> Running
            "error" -> Error
            "updating" -> Updating
            else -> Stopped
        }
    }
}

/**
 * 调度器里一条任务的状态
 *
 * State of a single task inside the scheduler.
 */
enum class AzurPilotTaskState { Running, Pending, Waiting;
    companion object {
        /**
         * 把网关的原始状态串折成枚举；未知或缺失一律退回 [Waiting]
         *
         * Folds a raw gateway state string into the enum; unknown or missing
         * values fall back to [Waiting].
         */
        fun of(raw: String?): AzurPilotTaskState = when (raw) {
            "running" -> Running
            "pending" -> Pending
            else -> Waiting
        }
    }
}

/**
 * 一条实例的摘要
 *
 * Summary of one instance.
 *
 * @property name 实例名，也是网关各接口的 `instance` 参数 / instance name, also the
 *   `instance` parameter of the gateway APIs
 * @property status 调度器状态 / scheduler status
 * @property currentTask 正在跑的任务名；空闲为 null / running task name; null when idle
 * @property serial 目标模拟器 serial / target emulator serial
 * @property server 目标服务器 / target server
 */
data class AzurPilotInstance(
    val name: String,
    val status: AzurPilotStatus,
    val currentTask: String?,
    val serial: String,
    val server: String,
)

/**
 * 总览里的一条计划任务
 *
 * One scheduled task on the overview.
 *
 * @property name 任务名 / task name
 * @property nextRun 下次运行时间（人可读）/ next run time, human readable
 * @property pending 是否挂在调度队列里 / whether queued in the scheduler
 * @property state 运行态 / run state
 */
data class AzurPilotTask(
    val name: String,
    val nextRun: String,
    val pending: Boolean,
    val state: AzurPilotTaskState,
)

/**
 * 总览里的资源卡：`limit`/`total`/`record` 只有配置里声明了的资源才有
 *
 * A resource card on the overview; `limit`/`total`/`record` exist only for
 * resources declared in the config.
 */
data class AzurPilotResource(
    val name: String,
    val label: String,
    val value: Double?,
    val limit: Double?,
    val total: Double?,
    val record: String?,
)

/**
 * 实例总览快照（`overview` 主题推送与 `overview.get` 的解析结果）
 *
 * Overview snapshot of an instance, parsed from the `overview` topic push and
 * `overview.get`.
 */
data class AzurPilotOverview(
    val instance: String,
    val revision: String,
    val status: AzurPilotStatus,
    val tasks: List<AzurPilotTask>,
    val resources: List<AzurPilotResource>,
)

/**
 * 一份实例配置的完整快照；值树见 [ApConfigValues]
 *
 * A full snapshot of one instance's config; see [ApConfigValues] for the value tree.
 */
data class AzurPilotConfig(
    val instance: String,
    val revision: String,
    val values: ApConfigValues,
)

/**
 * 日志板的一条增量；[id] 单调递增，去重与游标推进都靠它
 *
 * One incremental log entry; the monotonically increasing [id] drives both
 * deduplication and cursor advancement.
 */
data class AzurPilotLogEntry(val id: Long, val level: String, val text: String)

/**
 * 截图预览帧；推送未带图或 base64 解码失败时 [image] 为 null，元数据仍会更新
 *
 * A preview frame; [image] is null when the push carries no image or the
 * base64 decode fails, while the metadata still updates.
 */
data class AzurPilotPreview(
    val capturedAt: String?,
    val runId: String?,
    val image: Bitmap?,
)

/**
 * 实例的开机自启设置（`startup.get` / `startup.set` 的解析结果）
 *
 * An instance's auto-start settings, as returned by `startup.get` and written
 * by `startup.set`.
 */
data class AzurPilotStartup(
    val enabled: Boolean,
    val remember: Boolean,
    val run: List<String>,
)

/**
 * 部署设置里的一个字段（`settings.get` 的 groups[].fields[] 项）
 *
 * One field in the deploy settings (a groups[].fields[] item of `settings.get`).
 *
 * @property key 字段名，回传 `settings.patch` 时用它 / field key, echoed back in
 *   `settings.patch`
 * @property type 控件类型（网关定义）/ widget type, defined by the gateway
 * @property label 显示名 / display name
 * @property help 说明文案 / help text
 * @property value 当前值 / current value
 * @property options 候选值（下拉/开关类）/ candidate values for choice widgets
 */
data class AzurPilotDeployField(
    val key: String,
    val type: String,
    val label: String,
    val help: String,
    val value: ApValue,
    val options: List<ApValue>,
)

/**
 * 部署设置的一个分组，[fields] 顺序即界面渲染顺序
 *
 * One deploy-settings group; [fields] renders in the given order.
 */
data class AzurPilotDeployGroup(val key: String, val label: String, val fields: List<AzurPilotDeployField>)

/**
 * 远程访问状态卡（`settings.get` 的 remote 段）
 *
 * The remote-access status card (the remote section of `settings.get`).
 */
data class AzurPilotRemoteAccess(
    val enabled: Boolean,
    val state: String,
    val address: String,
    val error: String,
)

/**
 * 部署设置页的整份数据；[remote] 为 null 表示网关未提供远程访问段
 *
 * Full payload of the deploy settings page; [remote] is null when the gateway
 * does not report a remote-access section.
 */
data class AzurPilotDeploySettings(
    val groups: List<AzurPilotDeployGroup>,
    val notice: String,
    val demo: Boolean,
    val remote: AzurPilotRemoteAccess?,
)

/**
 * 运行时（AzurPilot 仓库）更新器状态（`updater.status`）
 *
 * Updater status for the runtime (the AzurPilot repository), from
 * `updater.status`.
 *
 * @property state 更新器状态字（idle/checking/…，以网关为准）/ updater state word
 *   (idle/checking/…, as reported by the gateway)
 * @property localHead 本地 HEAD / local HEAD
 * @property upstreamHead 上游 HEAD / upstream HEAD
 * @property branch 当前分支 / current branch
 * @property ahead 领先上游的提交数 / commits ahead of upstream
 * @property behind 落后上游的提交数 / commits behind upstream
 * @property available 有可应用的更新 / an update is available
 * @property busy 更新器正忙于某个操作 / an updater operation is in flight
 * @property canApply 当前允许应用更新 / applying an update is currently allowed
 * @property canCancel 当前允许取消进行中的操作 / the in-flight operation can be cancelled
 * @property error 最近一次错误文案 / last error text
 * @property managedByAndroid 更新由 App 侧托管时为 true / true when updates are
 *   managed by the Android side
 */
data class AzurPilotUpdateStatus(
    val state: String,
    val localHead: String?,
    val upstreamHead: String?,
    val branch: String,
    val ahead: Int,
    val behind: Int,
    val available: Boolean,
    val busy: Boolean,
    val canApply: Boolean,
    val canCancel: Boolean,
    val error: String,
    val managedByAndroid: Boolean,
)

/**
 * 更新页的一条提交记录
 *
 * One commit entry on the update page.
 */
data class AzurPilotCommit(val sha: String, val author: String, val date: String, val message: String)

/**
 * 分页的提交历史；[hasMore] 为真时还有下一页
 *
 * Paged commit history; [hasMore] means another page exists.
 */
data class AzurPilotCommitHistory(
    val entries: List<AzurPilotCommit>,
    val total: Int,
    val hasMore: Boolean,
    val localHead: String?,
    val upstreamHead: String?,
)

/**
 * 运行时公告（`announcement.get`）；[url] 可空
 *
 * A runtime announcement (`announcement.get`); [url] is optional.
 */
data class AzurPilotAnnouncement(
    val id: String,
    val title: String,
    val content: String,
    val url: String?,
)

/**
 * 统计曲线上的一个点；[source] 标注数据出处，可空
 *
 * One point on a statistics series; [source] optionally names where the value
 * came from.
 */
data class AzurPilotStatPoint(val time: String, val value: Double, val source: String?)

/**
 * 一条统计曲线：[key] 是图例标识，[label] 是显示名
 *
 * One statistics series; [key] identifies it and [label] is the display name.
 */
data class AzurPilotStatSeries(val key: String, val label: String, val points: List<AzurPilotStatPoint>)

/**
 * 统计页的一张表；[defaultSortIndex]/[defaultSortDescending] 是网关建议的初始排序
 *
 * One table on the statistics page; the defaultSort pair is the gateway's
 * suggested initial sorting.
 */
data class AzurPilotStatTable(
    val title: String,
    val columns: List<String>,
    val rows: List<List<ApValue>>,
    val note: String,
    val defaultSortIndex: Int?,
    val defaultSortDescending: Boolean,
)

/**
 * 统计页顶部的一张指标卡
 *
 * One metric card at the top of the statistics page.
 */
data class AzurPilotMetric(val label: String, val value: Double?, val unit: String, val icon: String?)

/**
 * 统计查询的任务候选项
 *
 * A task candidate offered for statistics queries.
 */
data class AzurPilotTaskOption(val key: String, val label: String, val count: Int)

/**
 * `statistics.report` 的整份报表：指标卡、曲线、表格与备注一次带回
 *
 * The full `statistics.report` payload: metric cards, series, tables and notes
 * come back in one response.
 */
data class AzurPilotStatisticsReport(
    val instance: String,
    val category: String,
    val month: String,
    val metrics: List<AzurPilotMetric>,
    val series: List<AzurPilotStatSeries>,
    val tables: List<AzurPilotStatTable>,
    val notes: List<String>,
    val taskOptions: List<AzurPilotTaskOption>,
)

/**
 * 统计分类；顺序与 WebUI 的分段控件一致
 *
 * Statistics categories; the order matches the WebUI segmented control.
 */
enum class AzurPilotStatCategory(val key: String) {
    Resources("resources"),
    Action("action"),
    Opsi("opsi"),
    Commission("commission"),
    Ships("ships"),
    Loot("loot"),
    Research("research"),
}

/**
 * `statistics.report` 的可选参数，界面控件直接绑到它
 *
 * Optional parameters for `statistics.report`; UI controls bind to it directly.
 */
data class AzurPilotStatsQuery(
    val category: AzurPilotStatCategory,
    val days: Int = 7,
    val month: String? = null,
    val period: String = "month",
    val series: Int = 0,
    val scope: String = "series",
    val task: String? = null,
)

/**
 * 指挥喵的一条天赋；[inferred] 为真表示等级是推断值而非直接读到的
 *
 * One meowfficer talent; [inferred] marks a level deduced rather than read
 * directly.
 */
data class AzurPilotTalent(val name: String, val level: Int?, val inferred: Boolean)

/**
 * 指挥喵评分报告里的一只猫：原始属性、评分与建议字段一应俱全
 *
 * One cat in the meowfficer scoring report, carrying the raw attributes, the
 * score and the advice fields. 字段与网关 `meowfficer.scoreReport` 的 cats[] 项
 * 一一对应 / Fields map one-to-one to the cats[] items of the gateway's
 * `meowfficer.scoreReport`.
 */
data class AzurPilotCat(
    val cat: String,
    val level: Int?,
    val maxed: Boolean,
    val fixed: Boolean,
    val source: String?,
    val note: String?,
    val talents: List<AzurPilotTalent>,
    val score: Double?,
    val tier: String?,
    val verdict: String?,
    val headline: String?,
    val reason: String?,
    val adviceReason: String?,
    val costText: String?,
    val pointsSpent: Int?,
    val targets: List<String>,
    val tags: List<String>,
)

/**
 * 指挥喵评分报告（`meowfficer.scoreReport`）
 *
 * The meowfficer scoring report (`meowfficer.scoreReport`).
 */
data class AzurPilotMeowfficerReport(
    val generatedAt: String,
    val count: Int,
    val cats: List<AzurPilotCat>,
)

/**
 * 脚本校验的一条诊断；[line]/[column] 定位脚本内的位置，可空
 *
 * One script validation diagnostic; [line]/[column] locate it inside the
 * script and may be absent.
 */
data class AzurPilotDiagnostic(
    val code: String,
    val message: String,
    val line: Int?,
    val column: Int?,
)

/**
 * 商店高级模式脚本的校验结果；不通过时 [diagnostics] 带回定位
 *
 * Validation result for the shop high-level strategy script; failures carry
 * [diagnostics] with positions.
 */
data class AzurPilotShopValidation(val valid: Boolean, val diagnostics: List<AzurPilotDiagnostic>)
