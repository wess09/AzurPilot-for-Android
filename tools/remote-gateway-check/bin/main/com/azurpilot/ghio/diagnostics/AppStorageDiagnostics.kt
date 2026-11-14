package com.azurpilot.ghio.diagnostics

import android.content.Context
import android.system.Os
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * 在 IO 线程统计 APK、运行环境与缓存，供受 DUMP 权限保护的 ADB 入口调用。
 *
 * 不跟随软链接，同一硬链接只计一次；仅报告固定分类与依赖包名，不读取文件内容。
 * 扫描期间文件可能改变，失败条目单独计数，结果是当前可读文件的快照。
 *
 * Measures APK, runtime, and cache storage on IO threads for the DUMP-protected ADB entry.
 * Does not follow symlinks and counts hard links once. Reports fixed categories and dependency
 * names without reading contents. Files may change during scanning; failures are counted
 * separately, so results describe the currently readable files.
 */
internal object AppStorageDiagnostics {
    /** 返回逻辑大小和实际分配块大小。 / Returns logical sizes and allocated block sizes. */
    fun measure(context: Context): JsonObject {
        val seen = mutableSetOf<Pair<Long, Long>>()
        val groups = mutableMapOf<String, Size>()
        val dependencies = mutableMapOf<String, Size>()
        var symlinks = 0L
        var failures = 0L
        val roots = linkedMapOf(
            "apk" to listOf(context.applicationInfo.sourceDir) +
                context.applicationInfo.splitSourceDirs.orEmpty(),
            "native_libraries" to listOf(context.applicationInfo.nativeLibraryDir),
            "files" to listOf(context.filesDir.absolutePath),
            "no_backup" to listOf(context.noBackupFilesDir.absolutePath),
            "cache" to listOf(context.cacheDir.absolutePath, context.codeCacheDir.absolutePath),
            "external_files" to listOfNotNull(context.getExternalFilesDir(null)?.absolutePath),
            "external_cache" to listOfNotNull(context.externalCacheDir?.absolutePath),
        )
        for ((scope, paths) in roots) for (path in paths) {
            val root = File(path).toPath()
            if (!Files.exists(root)) continue
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    if (attributes.isSymbolicLink) symlinks++
                    if (!attributes.isRegularFile) return FileVisitResult.CONTINUE
                    val relative = root.relativize(file).toString().replace('\\', '/')
                    val group = when {
                        scope == "files" && relative.startsWith("rootfs/") -> when {
                            relative.startsWith("rootfs/var/cache/apt/") -> "runtime_apt_cache"
                            relative.startsWith("rootfs/opt/azurpilot-venv/") -> "runtime_python_dependencies"
                            relative.startsWith("rootfs/opt/azurpilot/") -> when {
                                relative.startsWith("rootfs/opt/azurpilot/bin/") -> "ap_binaries_models"
                                relative.startsWith("rootfs/opt/azurpilot/assets/") -> "ap_game_assets"
                                relative.startsWith("rootfs/opt/azurpilot/log/") -> "ap_logs"
                                relative.startsWith("rootfs/opt/azurpilot/config/") -> "ap_configuration"
                                relative.startsWith("rootfs/opt/azurpilot/.git/") -> "ap_git"
                                else -> "ap_source_other"
                            }
                            else -> "runtime_system_python"
                        }
                        scope == "files" && relative.startsWith("rootfs.previous/") -> "runtime_rollback"
                        scope == "files" && relative.startsWith("rootfs.tmp/") -> "runtime_staging"
                        scope == "files" && relative.endsWith(".tar.xz") -> "runtime_downloads"
                        scope == "no_backup" && relative.startsWith("ocr-models/") -> "ocr_model_cache"
                        scope == "no_backup" && relative.startsWith("ocr-npu/") -> "ocr_driver_cache"
                        else -> scope
                    }
                    try {
                        val stat = Os.lstat(file.toString())
                        if (seen.add(stat.st_dev to stat.st_ino)) {
                            val allocated = stat.st_blocks * 512
                            groups.getOrPut(group, ::Size).add(stat.st_size, allocated)
                            if (group == "runtime_python_dependencies") {
                                val dependency = relative.substringAfter("/site-packages/", "")
                                    .substringBefore('/')
                                if (dependency.isNotEmpty()) dependencies.getOrPut(dependency, ::Size)
                                    .add(stat.st_size, allocated)
                            }
                        }
                    } catch (_: Exception) { failures++ }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, error: IOException): FileVisitResult {
                    failures++
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                    if (error != null) failures++
                    return FileVisitResult.CONTINUE
                }
            })
        }
        return buildJsonObject {
            put("logical_file_bytes", groups.values.sumOf { it.bytes })
            put("allocated_file_bytes", groups.values.sumOf { it.allocated })
            put("regular_files", groups.values.sumOf { it.files })
            put("symlinks_skipped", symlinks)
            put("unreadable_entries", failures)
            put("groups", buildJsonObject {
                groups.toSortedMap().forEach { (name, size) -> put(name, size.json()) }
            })
            put("largest_python_dependencies", buildJsonObject {
                dependencies.entries.sortedByDescending { it.value.bytes }.take(25)
                    .forEach { (name, size) -> put(name, size.json()) }
            })
            put("scope", "APK, native libraries, files, no-backup, caches, external app files; " +
                "excludes databases/preferences, directory blocks and shared system files")
        }
    }

    private class Size {
        var bytes = 0L
        var allocated = 0L
        var files = 0L
        fun add(length: Long, blocks: Long) { bytes += length; allocated += blocks; files++ }
        fun json() = buildJsonObject {
            put("logical_bytes", bytes); put("allocated_bytes", allocated); put("files", files)
        }
    }
}
