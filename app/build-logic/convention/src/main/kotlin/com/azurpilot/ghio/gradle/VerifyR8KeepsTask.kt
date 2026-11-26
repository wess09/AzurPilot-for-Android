package com.azurpilot.ghio.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * 会被 native 代码或其他进程按字面名查找的类
 *
 * bridge.cpp 把它们持有为字符串常量，特权进程则用 `app_process --class=<name>` 拉起。
 * 改名在构建期完全不可见，只会在设备上暴露——在一个没有调试器的进程里，
 * 甚至可能要等一次运行真正进行中才暴露。
 *
 * Classes native code and other processes look up by their literal name.
 *
 * bridge.cpp holds them as string constants, and the privileged process is started with
 * `app_process --class=<name>`. Renaming one is invisible at build time and only shows up on
 * a device, inside a process without a debugger, possibly only once a run is actually under
 * way.
 */
internal val R8_CRITICAL_CLASSES = setOf(
    "com.azurpilot.ghio.bridge.NativeBridgeLib",
    "com.azurpilot.ghio.bridge.DriverClass",
    "com.azurpilot.ghio.remote.RemoteServiceImpl",
    "com.azurpilot.ghio.root.RootServiceStarter",
    "com.azurpilot.ghio.root.RootUserService",
    "com.azurpilot.ghio.ocr.OcrNative",
    "com.azurpilot.ghio.ocr.OcrHiaiNative",
    "com.google.ai.edge.litert.JniHandle",
    "com.google.ai.edge.litert.LiteRtException",
    "com.google.ai.edge.litert.TensorBufferRequirements",
)

/**
 * R8 不能裁掉的无参构造器
 *
 * Room 2.2.5 的 consumer rule 只保留 [androidx.room.RoomDatabase] 实现类名，
 * WorkManager 却用反射调用 [androidx.work.impl.WorkDatabase_Impl] 的无参构造器。
 * 若构造器被裁掉，应用会在 androidx.startup 初始化阶段直接闪退。
 * WorkManager 反射加载输入合并器，Glance 反射加载接收器并用类名注册组件会话。
 *
 * No-argument constructors R8 must retain.
 *
 * Room 2.2.5's consumer rule retains only [androidx.room.RoomDatabase] implementation
 * class names, while WorkManager reflectively calls [androidx.work.impl.WorkDatabase_Impl]'s
 * no-argument constructor. Removing it crashes the app during androidx.startup initialization.
 * WorkManager reflectively loads its input merger. Glance loads receivers by name and registers
 * widget sessions under class names.
 */
internal val R8_CRITICAL_NO_ARG_CONSTRUCTORS = setOf(
    "androidx.work.impl.WorkDatabase_Impl",
    "androidx.work.OverwritingInputMerger",
    "com.azurpilot.ghio.widget.AzurPilotControlWidget",
    "com.azurpilot.ghio.widget.AzurPilotControlWidgetReceiver",
    "com.azurpilot.ghio.widget.AzurPilotQuickWidget",
    "com.azurpilot.ghio.widget.AzurPilotQuickWidgetReceiver",
)

/**
 * 断言 [R8_CRITICAL_CLASSES] 的 keep 规则仍然生效
 *
 * R8 不为保持原名的类输出映射：右侧出现别的名字意味着 keep 规则已失效；
 * 映射里整个缺失则意味着类已被 shrink 掉。
 *
 * Asserts the keep rules for [R8_CRITICAL_CLASSES] still hold.
 *
 * R8 omits identity mappings, so a class that appears with a different name on the right-hand
 * side lost its keep rule; a class missing from the mapping altogether was shrunk away.
 */
abstract class VerifyR8KeepsTask : DefaultTask() {

    /** release 变体产出的混淆映射文件 / The obfuscation mapping produced by the release variant. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mapping: RegularFileProperty

    /**
     * 保留成员的清单；R8 映射可能省略未改名的字段。
     *
     * Kept-member list; R8 mappings may omit fields whose names are unchanged.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    val seeds: File get() = mapping.get().asFile.resolveSibling("seeds.txt")

    /** 需要保名验证的类全集 / The set of classes whose name must survive. */
    @get:Input
    abstract val criticalClasses: SetProperty<String>

    /** 需要保留的无参构造器所属类 / Classes whose no-argument constructor must survive. */
    @get:Input
    abstract val criticalNoArgConstructors: SetProperty<String>

    @TaskAction
    fun verify() {
        val file = mapping.get().asFile
        if (!file.isFile) return
        val lines = file.readLines()

        val renamed = mutableMapOf<String, String>()
        lines.forEach { line ->
            if (line.startsWith(" ") || !line.endsWith(":")) return@forEach
            val parts = line.dropLast(1).split(" -> ")
            if (parts.size == 2) renamed[parts[0]] = parts[1]
        }

        val broken = criticalClasses.get().mapNotNull { name ->
            when (val mapped = renamed[name]) {
                null -> "$name was shrunk away"
                name -> null
                else -> "$name was renamed to $mapped"
            }
        }.toMutableList()
        // OCR JNI 读取这个字段；仅保留类名还不足以保证原生句柄可访问。
        val handleClass = "com.google.ai.edge.litert.JniHandle"
        val handleStart = lines.indexOf("$handleClass -> $handleClass:")
        val handleEnd = if (handleStart < 0) -1 else lines.subList(handleStart + 1, lines.size)
            .indexOfFirst { line -> !line.startsWith(" ") && line.endsWith(":") }
            .let { index -> if (index < 0) lines.size else handleStart + 1 + index }
        val handleRenamed = handleStart >= 0 && lines.subList(handleStart, handleEnd).any {
            val line = it.trim()
            line.startsWith("long handle -> ") && line != "long handle -> handle"
        }
        if (handleStart < 0 || handleRenamed || !seeds.readLines().contains("$handleClass: long handle")) {
            broken += "$handleClass.handle was removed or renamed"
        }
        criticalNoArgConstructors.get().forEach { name ->
            val classStart = lines.indexOf("$name -> $name:")
            val classEnd = if (classStart < 0) -1 else lines.subList(classStart + 1, lines.size)
                .indexOfFirst { line -> !line.startsWith(" ") && line.endsWith(":") }
                .let { index -> if (index < 0) lines.size else classStart + 1 + index }
            if (classStart < 0 || lines.subList(classStart, classEnd).none { it.contains("void <init>():") }) {
                broken += "$name no-argument constructor was shrunk away"
            }
        }
        if (broken.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("R8 broke a name or reflective constructor required at runtime:")
                    broken.forEach { appendLine("  - $it") }
                    appendLine("Check the keep rules in app/proguard-rules.pro before shipping this.")
                },
            )
        }
        logger.lifecycle(
            "R8 keeps verified: ${criticalClasses.get().size} classes and " +
                "${criticalNoArgConstructors.get().size} constructors retained",
        )
    }
}
