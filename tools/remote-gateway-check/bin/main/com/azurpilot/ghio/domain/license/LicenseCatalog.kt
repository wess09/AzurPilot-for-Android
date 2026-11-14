package com.azurpilot.ghio.domain.license

import kotlinx.serialization.Serializable

/**
 * 开源许可证资产包总目录
 *
 * 对应 `assets/licenses/licenses.json` 的顶层序列化结构。
 *
 * Open-source license catalog envelope.
 *
 * Matches the top-level serialization schema of `assets/licenses/licenses.json`.
 *
 * @property version 数据协议版本 / Catalog schema version
 * @property licenses 协议 ID 到协议详情及完整正文的映射表 / Map of license IDs to license metadata and full text
 * @property components 所有开源组件元数据列表 / List of all open-source component records
 */
@Serializable
data class LicenseCatalog(
    val version: Int = 1,
    val licenses: Map<String, OpenSourceLicense> = emptyMap(),
    val components: List<OpenSourceComponent> = emptyList(),
)
