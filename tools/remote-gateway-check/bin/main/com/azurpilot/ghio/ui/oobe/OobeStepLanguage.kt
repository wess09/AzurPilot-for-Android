package com.azurpilot.ghio.ui.oobe

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.azurpilot.ghio.R
import com.azurpilot.ghio.i18n.AppLocales
import com.azurpilot.ghio.theme.ksu.LocalUiMode
import com.azurpilot.ghio.theme.ksu.UiMode

/**
 * 单独呈现语言选择，系统重建后由向导保存当前步骤。
 *
 * Presents language choices separately, retaining the wizard step across locale recreation.
 */
@Composable
internal fun OobeStepLanguage() {
    val options = listOf(null, "zh-CN", "zh-TW", "en", "ja").map { it to oobeLanguageName(it) }
    val selected = normalizeOobeLanguageTag(AppLocales.currentTag())
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(
        if (LocalUiMode.current == UiMode.Miuix) 16.dp else 0.dp)) {
        OobePageHeader(stringResource(R.string.oobe_pref_lang_title), null, Icons.Outlined.Translate, OobeGlyph.LANGUAGE)
        OobeChoiceGroup(null, options, selected, onSelect = { if (it != selected) AppLocales.apply(it) })
    }
}
