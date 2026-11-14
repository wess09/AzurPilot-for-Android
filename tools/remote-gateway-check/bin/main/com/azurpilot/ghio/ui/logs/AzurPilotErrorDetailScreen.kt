package com.azurpilot.ghio.ui.logs

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.AppDispatchers
import com.azurpilot.ghio.R
import com.azurpilot.ghio.log.AzurPilotErrorDetailViewModel
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.ui.components.AppCardSurface
import com.azurpilot.ghio.ui.ksu.AppHorizontalDivider as HorizontalDivider
import com.azurpilot.ghio.ui.ksu.AppText as Text
import java.io.File
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

/**
 * 渲染一个 AzurPilot 错误现场的详情（二级页面）：log.txt 用共用的尾部加载查看器， 下方一排 PNG 截图缩略图，点开全屏（fit-center，不做捏合缩放）
 *
 * Renders one AzurPilot error scene in detail (second-level page): log.txt goes through the shared
 * tail-loading viewer, with a row of PNG screenshot thumbnails below; tapping one opens it
 * fullscreen (fit-center, no pinch zoom).
 *
 * @param dirName 现场目录名（毫秒时间戳，路由参数已解码）/ scene directory name (millisecond timestamp, route argument
 *   already decoded)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AzurPilotErrorDetailScreen(
    dirName: String,
    onBack: () -> Unit,
    viewModel: AzurPilotErrorDetailViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(dirName) { viewModel.load(dirName) }
    var fullscreenImage by remember { mutableStateOf<File?>(null) }

    com.azurpilot.ghio.ui.ksu.AppPageScaffold(
        title = logTimestamp(dirName.toLongOrNull() ?: 0L),
        onBack = onBack,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> AzurPilotErrorMessage(R.string.common_loading, Modifier.weight(1f))
                state.missing ->
                    AzurPilotErrorMessage(R.string.log_tail_missing, Modifier.weight(1f))
                state.logTxt == null && state.images.isEmpty() ->
                    AzurPilotErrorMessage(R.string.log_tail_empty, Modifier.weight(1f))

                else -> {
                    if (state.logTxt != null) {
                        LogTailContent(file = state.logTxt!!, modifier = Modifier.weight(1f))
                    } else {
                        // 现场只剩截图：文案占位，图在下面
                        AzurPilotErrorMessage(R.string.log_tail_missing, Modifier.weight(1f))
                    }
                    if (state.images.isNotEmpty()) {
                        HorizontalDivider()
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(AppTokens.Spacing.md),
                            horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
                        ) {
                            items(state.images, key = { it.name }) { image ->
                                AzurPilotErrorThumbnail(
                                    file = image,
                                    onClick = { fullscreenImage = image },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fullscreenImage?.let { image ->
        AzurPilotErrorFullscreen(image = image, onDismiss = { fullscreenImage = null })
    }
}

@Composable
private fun AzurPilotErrorMessage(textRes: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.padding(AppTokens.Spacing.lg),
        contentAlignment = Alignment.TopStart,
    ) {
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AzurPilotErrorThumbnail(file: File, onClick: () -> Unit) {
    val bitmap by rememberDecodedBitmap(file, maxDimension = THUMB_SIZE_PX)
    AppCardSurface(modifier = Modifier.size(THUMB_SIZE_DP).clickable(onClick = onClick)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = file.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun AzurPilotErrorFullscreen(image: File, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val bitmap by rememberDecodedBitmap(image, maxDimension = FULLSCREEN_MAX_PX)
        val closeAction = stringResource(R.string.log_preview_close_action)
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .background(Color.Black)
                    .semantics {
                        role = Role.Button
                        contentDescription = closeAction
                    }
                    .clickable(
                        onClickLabel = closeAction,
                        onClick = onDismiss,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!,
                    contentDescription = image.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * 按目标尺寸采样解码；原图直接进内存是 1080p PNG 的数倍，缩略图必须 inSampleSize
 *
 * Decodes an image sampled down to the target size; loading an original into memory costs several
 * times a 1080p PNG, so thumbnails must go through inSampleSize.
 */
@Composable
private fun rememberDecodedBitmap(file: File, maxDimension: Int) =
    produceState<ImageBitmap?>(
        initialValue = null,
        key1 = file,
        key2 = maxDimension,
    ) {
        value =
            withContext(AppDispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    var sample = 1
                    while (
                        bounds.outWidth / (sample * 2) >= maxDimension &&
                            bounds.outHeight / (sample * 2) >= maxDimension
                    ) {
                        sample *= 2
                    }
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
                }
                    .getOrNull()
            }
    }

/**
 * 缩略图取 96dp 边长，解码上限 192px（2x 屏再留一倍余量，Crop 后仍清晰）； 全屏图解码上限 1920px——已盖过主流设备屏幕的宽高，再大只费内存
 *
 * Thumbnails are 96dp square with a 192px decode cap (headroom for 2x screens, still sharp after
 * Crop); the fullscreen decode caps at 1920px — beyond mainstream device screens, larger only burns
 * memory.
 */
private val THUMB_SIZE_DP = 96.dp
private const val THUMB_SIZE_PX = 192
private const val FULLSCREEN_MAX_PX = 1920
