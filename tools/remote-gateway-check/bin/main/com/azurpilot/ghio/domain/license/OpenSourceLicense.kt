package com.azurpilot.ghio.domain.license

import kotlinx.serialization.Serializable

/**
 * 开源许可证定义
 *
 * 表示一份标准的开源协议元数据及其完整的法定正文。
 *
 * Open-source license definition.
 *
 * Represents standard open-source license metadata along with its full legal text.
 *
 * @property name 协议全名（如 Apache License 2.0） / License full name
 * @property spdxId SPDX 标准许可证标识符 / SPDX identifier
 * @property url 官方许可文本链接 / Official license URL
 * @property text 完整的许可证法定英文全文 / Full legal text of the license
 */
@Serializable
data class OpenSourceLicense(
    val name: String,
    val spdxId: String,
    val url: String,
    val text: String,
)
