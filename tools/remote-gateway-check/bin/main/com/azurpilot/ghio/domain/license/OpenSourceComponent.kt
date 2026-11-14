package com.azurpilot.ghio.domain.license

import kotlinx.serialization.Serializable

/**
 * 开源组件定义
 *
 * 表示应用中内嵌或引用的一个开源构件/软件项。
 *
 * Open-source component definition.
 *
 * Represents an open-source artifact or software package bundled or referenced
 * by the application.
 *
 * @property id 唯一标识符 / Unique identifier
 * @property name 组件名称 / Component name
 * @property group 组织或分组（如 Maven group 或 Native / Bundled） / Group or organization
 * @property artifact 模块标识 / Module or artifact identifier
 * @property version 版本号 / Version string
 * @property licenseId 关联的开源协议 ID / Associated license identifier
 * @property url 项目官方地址或仓库链接 / Project URL or repository link
 * @property description 简短描述 / Short description
 * @property category 分类标识（core / android_lib） / Category identifier
 * @property isCore 是否属于核心/内置组件 / Whether this is a core or bundled component
 * @property tomlAlias 对应的版本目录别名（如有） / Version catalog alias if available
 */
@Serializable
data class OpenSourceComponent(
    val id: String,
    val name: String,
    val group: String,
    val artifact: String,
    val version: String,
    val licenseId: String,
    val url: String,
    val description: String = "",
    val category: String = "android_lib",
    val isCore: Boolean = false,
    val tomlAlias: String? = null,
)
