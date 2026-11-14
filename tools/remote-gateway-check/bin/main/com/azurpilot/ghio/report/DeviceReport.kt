package com.azurpilot.ghio.report

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import com.azurpilot.ghio.BuildConfig
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * 保存可公开的机型参数，不读取任何设备唯一标识符；可在 IO 线程采集。
 *
 * Stores publishable model parameters without reading unique device identifiers.
 * Collection may run on the IO dispatcher.
 *
 * @property schemaVersion 报告协议版本。 / Report protocol version.
 * @property manufacturer 厂商名称。 / Manufacturer name.
 * @property brand 品牌名称。 / Brand name.
 * @property model 公开型号。 / Public model name.
 * @property marketingName 商品名称。 / Marketing name.
 * @property romName 厂商系统名称。 / Vendor OS name.
 * @property romVersion 厂商系统版本。 / Vendor OS version.
 * @property buildDisplay 构建显示编号。 / Build display ID.
 * @property buildIncremental 完整系统版本。 / Full incremental system build.
 * @property device 机型代号。 / Model codename.
 * @property product 产品代号。 / Product codename.
 * @property hardware 硬件平台。 / Hardware platform.
 * @property socModel 芯片型号，旧系统为空。 / SoC model, empty on older systems.
 * @property socManufacturer 芯片厂商，旧系统为空。 / SoC vendor, empty on older systems.
 * @property androidRelease 系统版本。 / Android release.
 * @property sdkInt Android API 级别。 / Android API level.
 * @property securityPatch 安全补丁日期。 / Security patch date.
 * @property supportedAbis 系统支持的 ABI。 / Supported system ABIs.
 * @property screenWidth 主屏像素宽度。 / Main display pixel width.
 * @property screenHeight 主屏像素高度。 / Main display pixel height.
 * @property densityDpi 屏幕密度。 / Display density.
 * @property refreshRate 四舍五入的刷新率。 / Rounded refresh rate.
 * @property memoryGiB 四舍五入的内存容量。 / Rounded memory capacity.
 * @property appVersion App 版本名称。 / App version name.
 * @property appVersionCode App 版本号。 / App version code.
 * @property compatibility 用户明确选择的实测结果，空值表示尚未选择。
 *     / Explicit tested result, empty before selection.
 * @property backend 当前配置的提权后端。 / Currently configured privilege backend.
 * @property runMode 当前配置的运行模式。 / Currently configured run mode.
 */
@Serializable
data class DeviceReport(
    val schemaVersion: Int = 2,
    val manufacturer: String,
    val brand: String,
    val model: String,
    val marketingName: String,
    val romName: String,
    val romVersion: String,
    val buildDisplay: String,
    val buildIncremental: String,
    val device: String,
    val product: String,
    val hardware: String,
    val socModel: String,
    val socManufacturer: String,
    val androidRelease: String,
    val sdkInt: Int,
    val securityPatch: String,
    val supportedAbis: List<String>,
    val screenWidth: Int,
    val screenHeight: Int,
    val densityDpi: Int,
    val refreshRate: Int,
    val memoryGiB: Int,
    val appVersion: String,
    val appVersionCode: Int,
    val compatibility: String = "",
    val backend: String,
    val runMode: String,
) {
    companion object {
        private val sensitive = Regex(
            "(?i)(imei|imsi|serial|android[_ -]?id|mac[_ -]?address|\\bSN\\s*[:=]|" +
                "[0-9]{14,}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|" +
                "(?:[0-9a-f]{2}:){5}[0-9a-f]{2}|[\\w.+-]+@[\\w.-]+\\.[a-z]{2,})",
        )

        /** 过滤疑似标识符及控制字符。 / Filters suspected identifiers and control characters. */
        internal fun clean(value: String): String {
            if (sensitive.containsMatchIn(value)) return "[redacted]"
            return value.filter {
                !it.isISOControl() && Character.getType(it) != Character.FORMAT.toInt()
            }.replace('@', '＠').trim().take(128)
        }

        /**
         * 只调用公开机型 API 及只读属性白名单；不扫描属性、日志或电话服务。
         *
         * Uses public model APIs and allowlisted read-only properties; never scans logs or telephony.
         */
        fun collect(context: Context, backend: String, runMode: String): DeviceReport {
            val identity = DeviceIdentity.collect(context)
            val display = context.getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY)
            val metrics = context.resources.displayMetrics
            val memory = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
            return DeviceReport(
                manufacturer = clean(Build.MANUFACTURER),
                brand = clean(Build.BRAND),
                model = clean(Build.MODEL),
                marketingName = identity.marketingName,
                romName = identity.romName,
                romVersion = identity.romVersion,
                buildDisplay = identity.buildDisplay,
                buildIncremental = identity.buildIncremental,
                device = clean(Build.DEVICE),
                product = clean(Build.PRODUCT),
                hardware = clean(Build.HARDWARE),
                socModel = if (Build.VERSION.SDK_INT >= 31) clean(Build.SOC_MODEL) else "",
                socManufacturer = if (Build.VERSION.SDK_INT >= 31) clean(Build.SOC_MANUFACTURER) else "",
                androidRelease = clean(Build.VERSION.RELEASE),
                sdkInt = Build.VERSION.SDK_INT,
                securityPatch = Build.VERSION.SECURITY_PATCH.takeIf {
                    it.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))
                }.orEmpty(),
                supportedAbis = Build.SUPPORTED_ABIS.filter {
                    it in setOf("arm64-v8a", "armeabi-v7a", "armeabi", "x86", "x86_64", "riscv64")
                }.distinct(),
                screenWidth = display?.mode?.physicalWidth ?: metrics.widthPixels,
                screenHeight = display?.mode?.physicalHeight ?: metrics.heightPixels,
                densityDpi = metrics.densityDpi,
                refreshRate = (display?.refreshRate ?: 60f).roundToInt().coerceAtLeast(1),
                memoryGiB = (memory.totalMem.toDouble() / (1L shl 30)).roundToInt().coerceAtLeast(1),
                appVersion = clean(BuildConfig.VERSION_NAME),
                appVersionCode = BuildConfig.VERSION_CODE,
                backend = backend,
                runMode = runMode,
            )
        }
    }
}
