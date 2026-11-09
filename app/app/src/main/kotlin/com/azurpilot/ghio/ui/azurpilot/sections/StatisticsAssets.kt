package com.azurpilot.ghio.ui.azurpilot.sections

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import java.io.InputStream
import java.util.zip.ZipInputStream
import org.json.JSONObject

/** 读取随 APK 发布的 WebUI 统计缩略图和固定词条。 / Loads the WebUI thumbnails and fixed terms shipped with the APK. */
internal class StatisticsAssets(input: InputStream) {
    private val images = mutableMapOf<String, ByteArray>()
    private var terms = JSONObject()

    init {
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.name.endsWith(".webp") -> images[entry.name] = zip.readBytes()
                    entry.name == "terms.json" -> terms = JSONObject(zip.readBytes().decodeToString())
                }
                zip.closeEntry()
            }
        }
    }

    /** 优先使用 WebUI 固定词条，其次使用服务器词汇表。 / Resolves WebUI terms before the server glossary. */
    fun translate(text: String, language: String, glossary: Map<String, String>): String {
        if (language == "zh-CN") return text
        fun term(value: String) = terms.optJSONObject(value)?.optString(language)?.takeIf { it.isNotEmpty() }
            ?: glossary[value]
        term(text)?.let { return it }
        if ('、' in text && '。' !in text && '；' !in text) {
            return text.split('、').joinToString(if (language == "en-US") ", " else "、") { part ->
                val name = part.substringBeforeLast(" x")
                (term(name) ?: name) + part.removePrefix(name)
            }
        }
        if ('。' !in text) return text
        return text.split('。').filter { it.isNotEmpty() }.joinToString(" ") { part ->
            val sentence = part.trim() + "。"
            term(sentence) ?: sentence
        }
    }

    /** 取出已缩放的资源图标。 / Retrieves a resized resource icon. */
    fun icon(key: String): ByteArray? = images["${key.replaceFirst(':', '/')}.webp"]
}

/** 资源与 WebUI 图片的对应表，使用报表原始键或中文标签。 / Maps wire keys and source labels to WebUI images. */
private val resourceIcons = mapOf(
    "Oil" to "oil", "石油" to "oil", "Coin" to "gold", "物资" to "gold",
    "Gem" to "diamond", "钻石" to "diamond", "Cube" to "cube", "心智魔方" to "cube", "魔方" to "cube",
    "Pt" to "pt", "活动 PT" to "pt", "Core" to "core_data", "核心数据" to "core_data",
    "Medal" to "honor_medal", "荣誉勋章" to "honor_medal", "完成委托" to "honor_medal",
    "Merit" to "merit", "功勋" to "merit", "GuildCoin" to "stamina", "舰队币" to "stamina",
    "ActionPoint" to "guild_coin", "行动力" to "guild_coin", "行动力资产" to "action_asset",
    "海里数" to "nautical_miles", "YellowCoin" to "supply_token", "作战补给凭证" to "supply_token",
    "PurpleCoin" to "special_token", "特别兑换凭证" to "special_token",
    "Chip" to "cognitive_chips", "心智单元" to "cognitive_chips",
)

internal fun isStatisticsTemplate(text: String): Boolean =
    text.startsWith("research:") || text.startsWith("opsi:") || text.startsWith("storage:")

/** 显示 WebUI 的游戏图标，缺失的模板以占位图标显示。 / Shows WebUI game art and a placeholder for unavailable templates. */
@Composable
internal fun StatisticsIcon(assets: StatisticsAssets?, label: String, reference: String? = null, fallback: ImageVector? = null) {
    val key = reference ?: resourceIcons[label]
    val bitmap = remember(assets, key) {
        key?.let { assets?.icon(it) }?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(26.dp))
    } else {
        val vector = fallback ?: Icons.Filled.Inventory2.takeIf { key != null && isStatisticsTemplate(key) }
        if (vector != null) {
            Icon(vector, contentDescription = null, modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** 对应 WebUI 的非物品类指标图标。 / Matches the role of the WebUI's non-item metric icons. */
internal fun metricSymbol(key: String): ImageVector? = when (key) {
    "battles", "today_battles", "sorties" -> Icons.Filled.CheckCircle
    "action_point_cost", "net_action_point" -> Icons.Filled.Bolt
    "akashi_encounters", "akashi_encounter_rate" -> Icons.Filled.Pets
    "action_point_purchased", "action_point_per_purchase" -> Icons.Filled.ShoppingCart
    "average_battle_time", "avg_battle_time", "avg_round_time" -> Icons.Filled.AccessTime
    "today_runtime_minutes" -> Icons.Filled.Timer
    "exp_per_hour", "loop_efficiency", "today_exp", "siren_research_rate", "siren_research_devices" -> Icons.AutoMirrored.Filled.ShowChart
    else -> null
}
