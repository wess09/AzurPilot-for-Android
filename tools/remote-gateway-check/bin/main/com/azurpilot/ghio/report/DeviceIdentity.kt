package com.azurpilot.ghio.report

import android.content.Context
import android.os.Build
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * 通过只读属性白名单识别商品名和厂商系统；未知型号保留公开的型号代码。
 *
 * Resolves marketing names and vendor OS versions from allowlisted read-only properties.
 * Unknown devices retain their public model code. All work runs on the IO dispatcher.
 *
 * @property marketingName 用户熟悉的商品名称。 / Familiar marketing name.
 * @property romName 厂商系统名称。 / Vendor OS name.
 * @property romVersion 厂商系统版本。 / Vendor OS version.
 * @property buildDisplay 公开构建显示编号。 / Public build display ID.
 * @property buildIncremental 完整系统构建版本。 / Full incremental system build.
 */
internal data class DeviceIdentity(
    val marketingName: String,
    val romName: String,
    val romVersion: String,
    val buildDisplay: String,
    val buildIncremental: String,
) {
    companion object {
        private val properties = setOf(
            "ro.product.marketname", "ro.product.vendor.marketname", "ro.vendor.product.marketname",
            "ro.mi.os.version.name", "ro.mi.os.version.incremental", "ro.miui.ui.version.name",
            "ro.build.version.oplusrom", "ro.build.version.opporom", "ro.build.version.realmeui",
            "ro.rom.version", "ro.build.version.emui", "ro.build.version.magic",
            "ro.vivo.os.name", "ro.vivo.os.version", "ro.vivo.os.build.display.id",
            "ro.build.version.oneui", "ro.build.display.id",
        )

        private fun property(key: String): String {
            require(key in properties)
            return runCatching {
                val process = ProcessBuilder("/system/bin/getprop", key).start()
                try {
                    if (!process.waitFor(2, TimeUnit.SECONDS)) return@runCatching ""
                    process.inputStream.bufferedReader().use {
                        DeviceReport.clean(it.readText().take(256))
                    }
                } finally {
                    process.destroy()
                }
            }.getOrDefault("")
        }

        /**
         * 精确匹配品牌、型号与代号；多个商品名时不猜测。
         *
         * Matches brand, model and codename exactly without guessing ambiguous names.
         */
        internal fun catalogName(context: Context): String = runCatching {
            val brands = setOf(Build.BRAND, Build.MANUFACTURER).map { it.lowercase(Locale.ROOT) }
            val model = Build.MODEL.lowercase(Locale.ROOT)
            val device = Build.DEVICE.lowercase(Locale.ROOT)
            val exact = mutableSetOf<String>()
            val byModel = mutableSetOf<String>()
            context.assets.open("device-report/device-names.catalog").use { asset ->
                GZIPInputStream(asset).bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val row = line.split('\t', limit = 4)
                        if (row.size == 4 && row[0] in brands && row[1] == model) {
                            byModel += row[3]
                            if (row[2] == device) exact += row[3]
                        }
                    }
                }
            }
            (exact.singleOrNull() ?: byModel.singleOrNull()).orEmpty()
        }.getOrDefault("")

        private fun version(value: String): String =
            Regex("(?:OS|V)?(\\d+(?:\\.\\d+){1,3})", RegexOption.IGNORE_CASE)
                .find(value)?.groupValues?.get(1) ?: value

        /**
         * 优先使用稳定版完整版本，开发版日期编号则回退到厂商声明的版本系列。
         *
         * Uses full stable releases and falls back to the vendor family for dated development builds.
         */
        internal fun xiaomiVersion(incremental: String, family: String): String {
            val stable = Regex("^(?:OS|V)?(\\d+(?:\\.\\d+){3})(?:\\.|$)", RegexOption.IGNORE_CASE)
                .find(incremental)?.groupValues?.get(1)
            if (stable != null) return stable
            val value = family.removePrefix("OS").removePrefix("V")
            return if (value.matches(Regex("1\\d\\d"))) value.take(2) + "." + value.last() else value
        }

        private fun vendorVersion(name: String, vararg keys: String): Pair<String, String> {
            val value = keys.asSequence().map(::property).firstOrNull { it.isNotEmpty() }
            return if (value != null) name to version(value) else "Android" to Build.VERSION.RELEASE
        }

        /**
         * 读取厂商公开版本属性，不读取用户设置的设备名称或完整属性表。
         *
         * Reads public vendor version properties, never user-set device names or full property dumps.
         */
        fun collect(context: Context): DeviceIdentity {
            val incremental = DeviceReport.clean(Build.VERSION.INCREMENTAL)
            val display = DeviceReport.clean(Build.DISPLAY)
            val brand = (Build.MANUFACTURER + " " + Build.BRAND).lowercase(Locale.ROOT)
            val (name, release) = when {
                "xiaomi" in brand || "redmi" in brand || "poco" in brand -> {
                    val hyper = property("ro.mi.os.version.name")
                    val miui = property("ro.miui.ui.version.name")
                    when {
                        hyper.isNotEmpty() -> "HyperOS" to xiaomiVersion(
                            property("ro.mi.os.version.incremental").ifEmpty { incremental }, hyper)
                        miui.isNotEmpty() -> "MIUI" to xiaomiVersion(incremental, miui)
                        else -> "Android" to Build.VERSION.RELEASE
                    }
                }
                "realme" in brand -> vendorVersion("realme UI", "ro.build.version.realmeui")
                "oppo" in brand -> vendorVersion("ColorOS", "ro.build.version.oplusrom", "ro.build.version.opporom")
                "oneplus" in brand -> {
                    val oxygen = property("ro.rom.version")
                    if (oxygen.isNotEmpty()) "OxygenOS" to version(oxygen)
                    else vendorVersion("ColorOS", "ro.build.version.oplusrom", "ro.build.version.opporom")
                }
                "honor" in brand -> vendorVersion("MagicOS", "ro.build.version.magic")
                "huawei" in brand -> vendorVersion("EMUI", "ro.build.version.emui")
                "vivo" in brand || "iqoo" in brand -> {
                    val vivoName = property("ro.vivo.os.name")
                    val vivoVersion = property("ro.vivo.os.version").ifEmpty { property("ro.vivo.os.build.display.id") }
                    if (vivoName.isEmpty() && vivoVersion.isEmpty()) "Android" to Build.VERSION.RELEASE
                    else vivoName.ifEmpty { "vivo OS" } to version(vivoVersion)
                }
                "samsung" in brand -> {
                    val encoded = property("ro.build.version.oneui").toIntOrNull()
                    if (encoded != null && encoded in 10000..990099)
                        "One UI" to "${encoded / 10000}.${encoded % 10000 / 100}"
                    else "Android" to Build.VERSION.RELEASE
                }
                "meizu" in brand && display.contains("Flyme", ignoreCase = true) -> "Flyme" to version(display)
                else -> "Android" to Build.VERSION.RELEASE
            }
            val marketing = sequenceOf("ro.product.marketname", "ro.product.vendor.marketname",
                "ro.vendor.product.marketname").map(::property).firstOrNull { it.isNotEmpty() }
                ?: catalogName(context).ifEmpty { Build.MODEL }
            return DeviceIdentity(DeviceReport.clean(marketing), DeviceReport.clean(name),
                DeviceReport.clean(release), display, incremental)
        }
    }
}
