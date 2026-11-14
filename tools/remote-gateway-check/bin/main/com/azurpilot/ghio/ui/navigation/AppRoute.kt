package com.azurpilot.ghio.ui.navigation

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * 定义根导航的可序列化目的地；参数作为数据保存，不拼接或解码 URL。
 *
 * Defines serializable destinations for root navigation; arguments are saved as data, without URL
 * construction or decoding.
 */
@Serializable
sealed interface AppRoute : NavKey {
    /** 依赖运行时的详情目的地。 / A runtime-dependent detail destination. */
    @Serializable sealed interface Console : AppRoute

    /** 主页面。 / Main page. */
    @Serializable data object Main : AppRoute

    /** 应用设置入口（独立页面）。 / Settings as a standalone destination. */
    @Serializable data object Settings : AppRoute

    /** 启动器日志列表。 / Launcher log list. */
    @Serializable data object AppLog : AppRoute

    /** 运行时日志列表。 / Runtime log list. */
    @Serializable data object AzurPilotLog : AppRoute

    /** KSU 外观设置。 / KSU appearance settings. */
    @Serializable data object Appearance : AppRoute

    /** 显示设置。 / Display settings. */
    @Serializable data object Display : AppRoute

    /** 虚拟屏设置。 / Virtual display settings. */
    @Serializable data object VirtualDisplay : AppRoute

    /** 日志设置。 / Log settings. */
    @Serializable data object Logs : AppRoute

    /** 保活设置。 / Keep-alive settings. */
    @Serializable data object KeepAlive : AppRoute

    /** 运行与安全设置。 / Runtime and security settings. */
    @Serializable data object Advanced : AppRoute

    /** 桌面组件设置。 / Widget settings. */
    @Serializable data object Widget : AppRoute

    /** 运行时设置。 / Runtime settings. */
    @Serializable data object Runtime : AppRoute

    /** 无需本机运行时的远程配对页。 / Remote pairing without a local Runtime. */
    @Serializable data object RemoteAccess : AppRoute

    /** OCR 设置。 / OCR settings. */
    @Serializable data object Ocr : AppRoute

    /** 关于页面。 / About page. */
    @Serializable data object About : AppRoute

    /** 机型报告。 / Device report. */
    @Serializable data object DeviceReport : AppRoute

    /** 开源协议列表。 / Open-source license list. */
    @Serializable data object Licenses : AppRoute

    /** 运行实例管理。 / Runtime instance management. */
    @Serializable data object Instances : Console

    /** 运行时公告。 / Runtime announcements. */
    @Serializable data object Announcement : Console

    /** 猫粮设置。 / Meowfficer settings. */
    @Serializable data object Meowfficer : Console

    /** 运行时更新。 / Runtime update. */
    @Serializable data object Updater : Console

    /** 部署设置。 / Deployment settings. */
    @Serializable data object Deploy : Console

    /**
     * 保存启动器日志相对路径。
     *
     * Saves relative launcher log path.
     *
     * @property fileName 启动器日志相对路径。 / Relative launcher log path.
     */
    @Serializable data class AppLogDetail(val fileName: String) : AppRoute

    /**
     * 保存运行时日志文件名。
     *
     * Saves runtime log file name.
     *
     * @property fileName 运行时日志文件名。 / Runtime log file name.
     */
    @Serializable data class AzurPilotLogDetail(val fileName: String) : AppRoute

    /**
     * 保存错误现场目录名。
     *
     * Saves error scene directory name.
     *
     * @property dirName 错误现场目录名。 / Error scene directory name.
     */
    @Serializable data class AzurPilotErrorDetail(val dirName: String) : AppRoute

    /**
     * 保存组件标识。
     *
     * Saves component identifier.
     *
     * @property componentId 组件标识。 / Component identifier.
     */
    @Serializable data class LicenseDetail(val componentId: String) : AppRoute

    /**
     * 保存上游任务名，允许斜杠与非 ASCII 字符。
     *
     * Saves upstream task name, including slashes and non-ASCII characters.
     *
     * @property taskName 上游任务名，允许斜杠与非 ASCII 字符。 / Upstream task name, including slashes and
     *   non-ASCII characters.
     */
    @Serializable data class Task(val taskName: String) : Console
}
