package com.azurpilot.ghio.ocr

import android.content.Context
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import timber.log.Timber

/**
 * 启动 AP 前替换官方原始权重为模型身份文件，并回收已知旧权重。
 *
 * 全部活动模型先校验；上游改变权重时拒绝误用旧转换。只删除哈希匹配的官方文件，
 * 保留自定义文件和语言字典。路径必须位于 rootfs，且逐级拒绝符号链接。
 *
 * Installs model identity descriptors before AP starts and reclaims known source weights.
 * Validates every active model first; changed upstream weights require an APK update.
 * Deletes only official files matching known hashes, retaining custom files and dictionaries.
 * Every path must stay in rootfs and contain no symbolic-link components.
 */
internal object OcrModelProvisioner {
    /** 校验、回收并创建 AP 身份文件。 / Validates, reclaims, and installs AP descriptors. */
    fun install(context: Context, rootfs: File) {
        val root = rootfs.canonicalFile.toPath()
        val manifest = context.assets.open("ocr/manifest.json").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject
        }
        check(manifest.getValue("version").jsonPrimitive.int == 2)
        val active = manifest.getValue("models").jsonArray.map { value ->
            val model = value.jsonObject
            val asset = model.getValue("asset").jsonPrimitive.content
            check(asset.startsWith("models/"))
            val relative = "opt/azurpilot/bin/ocr_models/" + asset.removePrefix("models/")
            val target = safeFile(root, relative)
            val hash = model.getValue("sha256").jsonPrimitive.content
            if (target.exists()) {
                val identity = if (target.length() <= 4096) runCatching {
                    Json.parseToJsonElement(target.readText()).jsonObject.takeIf {
                        it["android_ocr_model"]?.jsonPrimitive?.int == 2
                    }?.get("sha256")?.jsonPrimitive?.content
                }.getOrNull() else null
                check((identity ?: digest(target)) == hash) { "AP OCR model changed; update the APK: ${target.name}" }
            }
            target to buildJsonObject { put("android_ocr_model", 2); put("sha256", hash) }.toString()
        }
        val retired = context.assets.open("ocr/retired-models.json").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonArray
        }
        var removed = 0L
        retired.forEach { value ->
            val item = value.jsonObject
            val file = safeFile(root, item.getValue("path").jsonPrimitive.content)
            if (file.isFile && file.length() == item.getValue("size").jsonPrimitive.long &&
                digest(file) == item.getValue("sha256").jsonPrimitive.content) {
                val bytes = file.length()
                check(file.delete()) { "Could not retire OCR source weight: ${file.name}" }
                removed += bytes
            }
        }
        active.forEach { (file, descriptor) ->
            if (!file.isFile || file.readText() != descriptor + "\n") {
                check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                val temporary = safeFile(root, root.relativize(file.toPath()).toString() + ".host.tmp")
                temporary.writeText(descriptor + "\n")
                // 同目录原子替换，不留半写入的身份文件。
                android.system.Os.rename(temporary.absolutePath, file.absolutePath)
            }
        }
        listOf("manifest.json" to "ocr-host-models.json", "retired-models.json" to "ocr-retired-models.json").forEach { (asset, name) ->
            val file = safeFile(root, "opt/azurpilot/$name")
            val temporary = safeFile(root, "opt/azurpilot/$name.host.tmp")
            context.assets.open("ocr/$asset").use { input -> temporary.outputStream().use { input.copyTo(it) } }
            android.system.Os.rename(temporary.absolutePath, file.absolutePath)
        }
        Timber.i("OCR source weights retired: %d bytes; %d host descriptors", removed, active.size)
    }

    private fun safeFile(root: java.nio.file.Path, relative: String): File {
        val path = root.resolve(relative).normalize()
        check(path.startsWith(root) && path != root) { "OCR model path escapes rootfs" }
        var parent = root
        root.relativize(path).forEach { component ->
            parent = parent.resolve(component)
            check(!Files.isSymbolicLink(parent)) { "OCR model path contains a symbolic link" }
        }
        check(!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        return path.toFile()
    }

    private fun digest(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val size = input.read(bytes)
                if (size < 0) break
                sha.update(bytes, 0, size)
            }
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }
}
