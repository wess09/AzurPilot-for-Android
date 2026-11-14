package com.azurpilot.ghio.domain.license

import android.content.Context
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * 开源许可证仓储契约
 *
 * 提供对应用内所有开源组件元数据及其法定协议全文的查询服务。
 *
 * Open-source license repository contract.
 *
 * Provides querying services for all in-app open-source component metadata and their
 * full legal license texts.
 */
interface LicenseRepository {
    /**
     * 获取全部开源组件列表
     *
     * Retrieves all open-source components.
     */
    fun getAllComponents(): List<OpenSourceComponent>

    /**
     * 获取核心与内置组件列表
     *
     * Retrieves core and bundled components.
     */
    fun getCoreComponents(): List<OpenSourceComponent>

    /**
     * 根据组件 ID 获取特定组件
     *
     * Retrieves a specific component by ID.
     */
    fun getComponent(id: String): OpenSourceComponent?

    /**
     * 获取组件对应的完整许可证协议
     *
     * Retrieves the full license associated with a component or license ID.
     */
    fun getLicense(licenseId: String): OpenSourceLicense?

    /**
     * 按关键字过滤组件（匹配名称、group、artifact、licenseId、描述）
     *
     * Filters components by query keyword (matching name, group, artifact, licenseId, or description).
     */
    fun searchComponents(query: String): List<OpenSourceComponent>
}

/**
 * 基于 Assets 的本地开源许可证仓储实现
 *
 * 启动时从 `assets/licenses/licenses.json` 懒加载目录并缓存在内存中。
 *
 * Asset-based local open-source license repository implementation.
 *
 * Lazily loads the catalog from `assets/licenses/licenses.json` on first access
 * and caches it in memory.
 */
class AssetLicenseRepository(
    private val context: Context,
) : LicenseRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val catalog: LicenseCatalog by lazy {
        loadCatalog()
    }

    private fun loadCatalog(): LicenseCatalog {
        return try {
            context.assets.open("licenses/licenses.json").use { inputStream ->
                val content = inputStream.bufferedReader().use { it.readText() }
                json.decodeFromString<LicenseCatalog>(content)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load open-source licenses catalog from assets")
            LicenseCatalog()
        }
    }

    override fun getAllComponents(): List<OpenSourceComponent> {
        return catalog.components
    }

    override fun getCoreComponents(): List<OpenSourceComponent> {
        return catalog.components.filter { it.isCore }
    }

    override fun getComponent(id: String): OpenSourceComponent? {
        return catalog.components.firstOrNull { it.id == id }
    }

    override fun getLicense(licenseId: String): OpenSourceLicense? {
        return catalog.licenses[licenseId]
    }

    override fun searchComponents(query: String): List<OpenSourceComponent> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return catalog.components
        return catalog.components.filter {
            it.name.contains(trimmed, ignoreCase = true) ||
                it.group.contains(trimmed, ignoreCase = true) ||
                it.artifact.contains(trimmed, ignoreCase = true) ||
                it.licenseId.contains(trimmed, ignoreCase = true) ||
                it.description.contains(trimmed, ignoreCase = true)
        }
    }
}
