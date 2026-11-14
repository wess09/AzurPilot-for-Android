package com.azurpilot.ghio.ui.oobe

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.azurpilot.ghio.R
import com.azurpilot.ghio.domain.license.AssetLicenseRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.azurpilot.ghio.ui.ksu.AppIcon as Icon
import com.azurpilot.ghio.ui.ksu.AppIconButton as IconButton
import com.azurpilot.ghio.ui.ksu.AppSurface as Surface
import com.azurpilot.ghio.ui.ksu.AppText as Text

/** 本地协议目录；开源许可复用设置页的完整正文。 / Local legal documents sharing settings' full license text. */
internal enum class OobeLegalDocument(@param:StringRes val title: Int, val asset: String?) {
    TERMS(R.string.oobe_legal_terms, "terms.txt"),
    PRIVACY(R.string.oobe_legal_privacy, "privacy.txt"),
    AGPL(R.string.oobe_legal_agpl, null);

    /** 离线读取对应语言，不改变 AGPL 的英文原文。 / Reads localized text offline, preserving AGPL's English original. */
    fun read(context: Context, language: String): String {
        if (this == AGPL) return checkNotNull(AssetLicenseRepository(context).getLicense("AGPL-3.0")).text
        val locale = when {
            language.startsWith("en") -> "en"
            language.startsWith("ja") -> "ja"
            language.contains("Hant") || language.startsWith("zh-TW") || language.startsWith("zh-HK") -> "zh-TW"
            else -> "zh-CN"
        }
        // 编辑器可能写入 CRLF；统一段落分隔，避免整份长协议落进一个 Text。
        return context.assets.open("legal/$locale/$asset").bufferedReader().use { it.readText() }
            .replace("\r\n", "\n").replace('\r', '\n')
    }
}

/**
 * 使用当前主题显示可选择、可滚动的离线全文，关闭后保留向导的确认状态。
 *
 * Presents selectable, scrollable offline text in the active theme, retaining wizard consent
 * state when dismissed.
 */
@Composable
internal fun OobeLegalReader(document: OobeLegalDocument, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val language = LocalConfiguration.current.locales[0].toLanguageTag()
    val body by produceState<String?>(null, document, language) {
        value = withContext(Dispatchers.IO) { document.read(context, language) }
    }
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onDismiss, Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.oobe_action_prev))
                    }
                    Text(stringResource(document.title), Modifier.weight(1f).padding(horizontal = 12.dp)
                        .semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                }
                SelectionContainer(Modifier.weight(1f)) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        if (document == OobeLegalDocument.AGPL) {
                            item { Text(stringResource(R.string.oobe_legal_agpl_note),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            // 许可证保持单份原文，包含所有条款与末尾的应用说明。
                            body?.let { text -> item { Text(text, style = MaterialTheme.typography.bodyMedium) } }
                        } else {
                            items(body?.trim()?.split("\n\n").orEmpty()) { block ->
                                val heading = block.substringBefore('\n')
                                if (heading.matches(Regex("\\d+\\. .+"))) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(heading, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                                        Text(block.substringAfter('\n'), style = MaterialTheme.typography.bodyLarge)
                                    }
                                } else Text(block, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}
