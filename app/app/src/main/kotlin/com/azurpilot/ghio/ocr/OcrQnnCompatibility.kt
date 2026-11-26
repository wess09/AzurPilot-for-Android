package com.azurpilot.ghio.ocr

// 来源为 LiteRT v2.1.0rc1 的 litert/vendors/qualcomm/supported_soc.csv；内置 HTP 从 v68 起。
private val legacyDspSocs = setOf(
    "SM8250", "SM8150", "SM7250", "SM7225", "SM7125", "SM6350", "SM6225", "SM6150",
    "SM6125", "SM4350", "QRB5165U", "QRB5165LE", "QCS7230LA", "QCS7230LE", "SM6375",
    "SM7150", "SDM845",
)

/**
 * 识别内置 QNN HTP 插件无法使用的 Hexagon v65/v66 芯片，兼容旧 ROM 的板级标识。
 *
 * Identifies Hexagon v65/v66 SoCs that the bundled QNN HTP plugin cannot use, accepting
 * board identifiers reported by older ROMs.
 */
internal fun requiresLegacyQualcommDsp(soc: String, board: String, hardware: String): Boolean =
    listOf(soc, board, hardware).any { identifier ->
        val name = identifier.trim().uppercase().substringBefore('-')
        name in legacyDspSocs || name == "MSMNILE" || name == "KONA"
    }
