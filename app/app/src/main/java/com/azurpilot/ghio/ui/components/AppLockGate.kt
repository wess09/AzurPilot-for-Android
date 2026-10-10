package com.azurpilot.ghio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.azurpilot.ghio.R
import com.azurpilot.ghio.theme.AppTokens

/**
 * 盖出全局应用锁遮罩门
 *
 * 冷启动或从后台切回且尚未解锁时，呈现整屏遮罩，彻底阻断对界面与服务的未授权访问；
 * 展示应用锁提示，并在初次出现时经 [onUnlockRequest] 自动调起原生系统锁屏/生物识别验证。
 *
 * Renders the global app-lock gate.
 *
 * On cold start or return from background while still locked, a full-screen overlay
 * cuts off all unauthorized access to the UI and services; it shows the lock prompt
 * and, on first appearance, automatically raises native screen-lock/biometric
 * authentication via [onUnlockRequest].
 *
 * @param isUnlocked 是否已解锁；false 时盖出遮罩 / whether unlocked; false draws the
 *   overlay
 * @param onUnlockRequest 请求解锁（拉起验证），遮罩首次出现时自动调用一次 / requests
 *   unlock (raises the prompt); invoked once when the overlay first appears
 */
@Composable
fun AppLockGate(
    isUnlocked: Boolean,
    onUnlockRequest: () -> Unit,
    errorMessage: String? = null,
    autoRequestUnlock: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        content()

        if (!isUnlocked) {
            LaunchedEffect(autoRequestUnlock) {
                if (autoRequestUnlock) onUnlockRequest()
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .pointerInput(Unit) {
                        // 拦截所有触控，防止误触底层内容
                        awaitPointerEventScope {
                            while (true) {
                                // Let the unlock button handle its event before
                                // swallowing anything that could hit content beneath.
                                awaitPointerEvent(PointerEventPass.Final).changes.forEach { it.consume() }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.padding(AppTokens.Spacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(AppTokens.Spacing.lg))
                    Text(
                        text = stringResource(R.string.app_lock_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(modifier = Modifier.height(AppTokens.Spacing.sm))
                    Text(
                        text = stringResource(R.string.app_lock_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(AppTokens.Spacing.xl))
                    Button(onClick = onUnlockRequest) {
                        Text(text = stringResource(R.string.app_lock_unlock_button))
                    }
                    if (!errorMessage.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(AppTokens.Spacing.sm))
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}
