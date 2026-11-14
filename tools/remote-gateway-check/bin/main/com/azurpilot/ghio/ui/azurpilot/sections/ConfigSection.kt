package com.azurpilot.ghio.ui.azurpilot.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.R
import com.azurpilot.ghio.ui.components.AppActionLabel
import com.azurpilot.ghio.ui.components.AppSymbol
import com.azurpilot.ghio.proot.ApValue
import com.azurpilot.ghio.proot.AzurPilotEditorState
import com.azurpilot.ghio.proot.AzurPilotField
import com.azurpilot.ghio.proot.AzurPilotRepository
import com.azurpilot.ghio.proot.AzurPilotStartup
import com.azurpilot.ghio.proot.toValueMap
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.ui.azurpilot.ApEmptyState
import com.azurpilot.ghio.ui.azurpilot.ApErrorState
import com.azurpilot.ghio.ui.azurpilot.ApTopBarAction
import com.azurpilot.ghio.ui.azurpilot.apContentWidth
import com.azurpilot.ghio.ui.components.AppCard
import com.azurpilot.ghio.ui.components.AppCardSurface
import com.azurpilot.ghio.ui.components.AppLabeledControlRow
import com.azurpilot.ghio.ui.components.AppNavigationRow
import com.azurpilot.ghio.ui.components.ExpressiveLoadingIndicator
import com.azurpilot.ghio.ui.ksu.appSettingsGroup
import com.azurpilot.ghio.ui.ksu.AppButton as Button
import com.azurpilot.ghio.ui.ksu.AppDialog as AlertDialog
import com.azurpilot.ghio.ui.ksu.AppHorizontalDivider as HorizontalDivider
import com.azurpilot.ghio.ui.ksu.AppIcon as Icon
import com.azurpilot.ghio.ui.ksu.AppIconButton as IconButton
import com.azurpilot.ghio.ui.ksu.AppSurface as Surface
import com.azurpilot.ghio.ui.ksu.AppSwitch as Switch
import com.azurpilot.ghio.ui.ksu.AppText as Text
import com.azurpilot.ghio.ui.ksu.AppTextButton as TextButton
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * FleetInfo 是唯一不走通用表单的任务：它的结果是一个隐藏的 JSON 舰队快照
 *
 * FleetInfo is the only task that bypasses the generic form: its result is a hidden JSON fleet
 * snapshot.
 */
private const val FLEET_INFO = "FleetInfo"

/**
 * 自启开关挂在系统设置任务页上——它描述的是「整机启动时怎么办」，不属于任何一个任务参数
 *
 * The startup switches hang on the system-settings task page — they describe "what happens at app
 * start", not any single task argument.
 */
private const val STARTUP_TASK = "Alas"

/**
 * 舰队快照里的角色键，与编队号一起定位一支舰队 / The role keys in a fleet snapshot; together with a fleet number they
 * locate one fleet.
 */
private val FLEET_ROLES = listOf("vanguard", "main", "submarine")

/** 编队号取值范围 / The possible fleet numbers. */
private val FLEET_NUMBERS = listOf(1, 2, 3, 4, 5, 6)

/** 工具任务页展示的日志行数 / The number of log lines shown on a tool-task page. */
private const val TOOL_LOG_LINES = 60

/**
 * 配置：任务目录
 *
 * 目录结构与名称全部取自 `schema.get`——菜单分组由 `menu.json` 定，任务名与分组标题走运行时翻译， 所以上游加了任务这里自动就有了。
 *
 * The Config section: the task catalog.
 *
 * Catalog structure and names all come from `schema.get` — menu grouping is defined by `menu.json`,
 * and task names and group titles go through runtime translation, so tasks added upstream show up
 * here automatically.
 *
 * @param repository 网关仓库 / the gateway repository
 * @param onOpenTask 打开某任务的配置页 / opens one task's config page
 */
@Composable
fun ConfigSection(repository: AzurPilotRepository, onOpenTask: (String) -> Unit) {
    val schema by repository.schema.collectAsStateWithLifecycle()
    val schemaError by repository.schemaError.collectAsStateWithLifecycle()
    val selected by repository.selectedInstance.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize().apContentWidth(),
        contentPadding =
            PaddingValues(
                start = AppTokens.Spacing.lg,
                end = AppTokens.Spacing.lg,
                top = AppTokens.Spacing.sm,
                bottom = AppTokens.Spacing.xl,
            ),
    ) {
        when {
            selected == null ->
                item(key = "empty") {
                    AppCard {
                        ApEmptyState(
                            icon = Icons.Filled.Build,
                            title = stringResource(R.string.ap_overview_no_instance),
                            hint = stringResource(R.string.ap_instance_none),
                        )
                    }
                }

            schema == null && schemaError != null ->
                item(key = "error") {
                    AppCard {
                        ApErrorState(schemaError!!) { repository.ensureSchema(force = true) }
                    }
                }

            schema == null ->
                item(key = "loading") {
                    AppCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            ExpressiveLoadingIndicator()
                        }
                    }
                }

            else -> {
                val data = schema!!
                data.menu.forEach { group ->
                    appSettingsGroup(
                        key = group.key,
                        values = group.tasks,
                        title = { data.menuTitle(group.key) },
                        itemKey = { it },
                    ) { task ->
                        AppNavigationRow(
                            label = data.taskTitle(task),
                            description =
                                if (group.isTool) {
                                    stringResource(R.string.ap_config_tool_hint)
                                } else task,
                            onClick = { onOpenTask(task) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一个任务的配置页
 *
 * 参数、分组、控件的形状全部由 schema 决定；界面只负责「渲染 + 提交」， 所以 97 个任务、2500 多个参数不需要各写一遍。
 *
 * 布局：搜索条是列表的**第一项**，上滑就跟着内容滚走——参数动辄八九组上百项， 把搜索和目录钉在顶上等于常驻吃掉一整行。滚走之后由顶栏的搜索图标把它找回来。
 *
 * One task's config page.
 *
 * Arguments, grouping, and control shapes are all decided by the schema; the UI only "renders +
 * commits", which is why 97 tasks with 2500+ arguments need no per-task code.
 *
 * Layout: the search bar is the list's **first item** and scrolls away with the content — tasks
 * routinely span eight or nine groups with hundreds of items, and pinning search + directory to the
 * top would permanently eat a whole row. Once scrolled away, the top bar's search icon brings it
 * back.
 *
 * @param repository 网关仓库 / the gateway repository
 * @param task 任务名，来自路由参数 / the task name from the route argument
 * @param topBarAction 外壳的顶栏动作位：搜索条滚走之后，用它在顶栏显示「找回搜索」 的入口；null 表示宿主没有顶栏 / the shell's top-bar action
 *   slot: once the search bar scrolls away it shows the search-recovery entry in the top bar; null
 *   when the host has no top bar
 */
@Composable
fun TaskConfigPage(
    repository: AzurPilotRepository,
    task: String,
    topBarAction: ApTopBarAction? = null,
) {
    val schema by repository.schema.collectAsStateWithLifecycle()
    val editor by repository.configEditor.state.collectAsStateWithLifecycle()
    val selected by repository.selectedInstance.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // rememberSaveable：分屏 / 深色切换重建 Activity 后，搜索词不丢（运行连续性）
    var query by rememberSaveable { mutableStateOf("") }
    var pendingRun by remember { mutableStateOf<String?>(null) }
    var wantSearchFocus by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }

    LaunchedEffect(selected) { selected?.let { repository.configEditor.load(it) } }

    val data = schema ?: return
    // 这一层的键是**分组名**，再往里才是参数名；取参数值必须走 group/argument 两级
    val taskValues = editor.values[task].orEmpty()
    val isTool = data.toolTasks.contains(task)
    val isFleetInfo = task == FLEET_INFO

    // 搜索按 分组名 / 参数显示名 / 原始 `Group.Arg` 匹配——中文翻译想不起来时可以直接敲参数名
    val groups =
        remember(data, taskValues, query, task, isFleetInfo) {
            if (isFleetInfo) {
                emptyList()
            } else {
                data.groupsOf(task).mapNotNull { (group, fields) ->
                    val visible = fields.filter { (argument, field) ->
                        if (!isFieldVisible(argument, field, taskValues[group]?.get(argument))) {
                            return@filter false
                        }
                        if (query.isBlank()) return@filter true
                        val label = data.fieldLabel(group, argument)
                        label.contains(query, ignoreCase = true) ||
                            "$group.$argument".contains(query, ignoreCase = true) ||
                            argument.contains(query, ignoreCase = true)
                    }
                    if (visible.isEmpty()) null else group to visible
                }
            }
        }

    // 运行会真的接管游戏，先确认；确认后还要等编辑队列落盘——调度器读的是磁盘上的配置，
    // 手上还有没保存的改动就直接起，跑的是上一版参数
    fun run(what: String) {
        scope.launch {
            repository.configEditor.settle()
            repository.runTask(what)
        }
    }

    pendingRun?.let { what ->
        AlertDialog(
            onDismissRequest = { pendingRun = null },
            title = { Text(stringResource(R.string.ap_run_title, data.taskTitle(what))) },
            text = { Text(stringResource(R.string.ap_run_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRun = null
                        run(what)
                    }
                ) {
                    AppActionLabel(AppSymbol.Play, stringResource(R.string.ap_run_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRun = null }) {
                    Text(stringResource(R.string.ap_cancel))
                }
            },
        )
    }

    val listState = rememberLazyListState()
    val startupCard = task == STARTUP_TASK && query.isBlank()
    // 头部整项滚出屏幕之后，才由顶栏接管搜索入口
    val headerGone by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

    DisposableEffect(topBarAction) {
        onDispose {
            topBarAction?.visible = false
            topBarAction?.onClick = null
        }
    }

    LaunchedEffect(topBarAction, listState) {
        if (topBarAction == null) return@LaunchedEffect
        topBarAction.onClick = {
            if (listState.firstVisibleItemIndex == 0) {
                runCatching { searchFocus.requestFocus() }
            } else {
                wantSearchFocus = true
                scope.launch { listState.animateScrollToItem(0) }
            }
        }
    }
    LaunchedEffect(topBarAction, headerGone) { topBarAction?.visible = headerGone }
    // 头部要等滚回顶部才重新组合出来，聚焦得等它到位
    LaunchedEffect(headerGone) {
        if (!headerGone && wantSearchFocus) {
            runCatching { searchFocus.requestFocus() }
            wantSearchFocus = false
        }
    }

    // 分组卡在列表里的起始下标：头部一项，加上可能出现的加载 / 错误 / 自启 / 空态
    val groupsStart =
        1 +
            (if (editor.loading) 1 else 0) +
            (if (editor.error != null) 1 else 0) +
            (if (startupCard) 1 else 0) +
            (if (groups.isEmpty()) 1 else 0)

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().apContentWidth(),
        contentPadding =
            PaddingValues(
                start = AppTokens.Spacing.lg,
                end = AppTokens.Spacing.lg,
                top = AppTokens.Spacing.sm,
                bottom = AppTokens.Spacing.xl,
            ),
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = AppTokens.Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
            ) {
                ApCompactSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.ap_config_search),
                    focusRequester = searchFocus,
                    modifier = Modifier.weight(1f),
                )
                // 一个任务动辄八九组；目录值得有入口，但不值得常驻一整行 chip
                if (groups.size > 1) {
                    GroupDirectoryMenu(
                        titles = groups.map { data.groupTitle(it.first) },
                        onJump = { index ->
                            val offset = groups.take(index).sumOf { it.second.size + 1 }
                            scope.launch { listState.animateScrollToItem(groupsStart + offset) }
                        },
                    )
                }
            }
        }

        if (editor.loading) {
            item {
                AppCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        ExpressiveLoadingIndicator()
                    }
                }
            }
        }
        editor.error?.let { message ->
            item {
                AppCard {
                    ApErrorState(message) {
                        selected?.let { repository.configEditor.load(it, force = true) }
                    }
                }
            }
        }

        if (isFleetInfo) {
            item { FleetInfoCard(taskValues["FleetInfo"]?.get("Result")) }
            return@LazyColumn
        }

        if (startupCard) {
            item(key = "startup") {
                val startup by repository.startup.collectAsStateWithLifecycle()
                StartupCard(repository, startup)
            }
        }

        if (groups.isEmpty()) {
            item {
                AppCard {
                    // 「搜索没命中」与「这个任务本来就没有参数」是两件事，别用同一句话打发
                    if (query.isNotBlank()) {
                        ApEmptyState(
                            icon = Icons.Filled.Search,
                            title = stringResource(R.string.ap_config_no_match),
                            hint = stringResource(R.string.ap_config_no_match_hint),
                        )
                    } else {
                        ApEmptyState(
                            icon = Icons.Filled.Tune,
                            title = stringResource(R.string.ap_config_no_config),
                            hint = stringResource(R.string.ap_config_no_config_hint),
                        )
                    }
                }
            }
        }

        groups.forEach { (group, fields) ->
            appSettingsGroup(
                key = group,
                values = fields.entries.toList(),
                title = { data.groupTitle(group) },
                itemKey = { it.key },
                contentType = "configField",
            ) { (argument, field) ->
                val path = "$task.$group.$argument"
                ApFieldRow(
                    schema = data,
                    group = group,
                    argument = argument,
                    field = field,
                    value = taskValues[group]?.get(argument) ?: field.value,
                    status = fieldStatus(path, editor),
                    statusError = editor.failed[path],
                    onChange = {
                        // 回收旧实例的行时不能把草稿提交到刚切换的新实例。
                        if (selected != null && repository.selectedInstance.value == selected &&
                            repository.configEditor.state.value.instance == selected) {
                            repository.configEditor.update(task, group, argument, it)
                        }
                    },
                    onRetry = { repository.configEditor.retry(path) },
                    // 「立刻运行」＝把调度时间回落到默认的过去时间（WebUI 用 prepareValue('')
                    // 得到的是同一个值）；直接提交空串会被服务端的日期格式校验拒绝
                    onRunNow =
                        if (group == "Scheduler" && argument == "NextRun") {
                            {
                                repository.configEditor.update(
                                    task,
                                    group,
                                    argument,
                                    field.value,
                                )
                            }
                        } else {
                            null
                        },
                    onCheckScript =
                        if (field.mode == "restricted_lua") {
                            { script -> checkScript(repository, task, script) }
                        } else {
                            null
                        },
                )
            }
        }

        if (isTool) {
            item(key = "toolLogs") {
                val logs by repository.logs.collectAsStateWithLifecycle()
                AppCard(title = stringResource(R.string.ap_tool_logs)) {
                    Button(
                        onClick = { pendingRun = task },
                        enabled = !editor.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(AppTokens.IconSize.md),
                        )
                        Text(
                            text = stringResource(R.string.ap_config_run_tool),
                            modifier = Modifier.padding(start = AppTokens.Spacing.sm),
                        )
                    }
                    ApLogBoard(
                        entries = logs.takeLast(TOOL_LOG_LINES),
                        modifier = Modifier.height(220.dp),
                        emptyHint = stringResource(R.string.ap_tool_logs_empty),
                    )
                }
            }
        }
    }
}

/**
 * 紧凑搜索条
 *
 * 自绘 Surface + BasicTextField，而不是 OutlinedTextField：后者带标签与 supportingText 的内边距， 单行也要 56dp
 * 起步，在这个位置太贵。全圆角与 MD3 的 search bar 一致。
 *
 * A compact search field.
 *
 * A hand-rolled Surface + BasicTextField instead of OutlinedTextField: the latter carries label and
 * supportingText padding — 56 dp minimum even for a single line — too expensive in this spot. The
 * fully rounded shape matches the MD3 search bar.
 *
 * @param value 当前搜索词 / the current query
 * @param onValueChange 搜索词变化回调 / invoked when the query changes
 * @param placeholder 占位文案 / the placeholder text
 * @param modifier 应用于搜索条的修饰符 / the modifier applied to the field
 * @param focusRequester 外部请求聚焦用（如顶栏找回搜索） / lets the outside request focus (e.g. the top-bar search
 *   recovery)
 */
@Composable
private fun ApCompactSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    Surface(
        // min 而不是定高：系统字体放大档位下文字不能被裁掉（小米大屏规范：响应字体档位）
        modifier = modifier.heightIn(min = 44.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AppTokens.Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(AppTokens.IconSize.md),
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle =
                    MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
                modifier =
                    Modifier.weight(1f)
                        .padding(start = AppTokens.Spacing.sm)
                        .then(
                            if (focusRequester != null) Modifier.focusRequester(focusRequester)
                            else Modifier
                        ),
            )
            if (value.isNotEmpty()) {
                IconButton(
                    onClick = { onValueChange("") },
                    modifier = Modifier.size(AppTokens.IconSize.lg),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.ap_clear_search),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(AppTokens.IconSize.md),
                    )
                }
            }
        }
    }
}

/**
 * 分组目录：一眼看全分组，且不占配置区的高度
 *
 * The group directory: every group visible at a glance without occupying any config-area height.
 *
 * @param titles 分组标题，顺序与列表一致 / the group titles, in list order
 * @param onJump 跳到第 index 组的卡片 / jumps to the index-th group's card
 */
@Composable
private fun GroupDirectoryMenu(titles: List<String>, onJump: (Int) -> Unit) {
    com.azurpilot.ghio.ui.ksu.AppDropdownPreference(
        title = stringResource(R.string.ap_config_directory),
        items = titles,
        selectedIndex = 0,
        onSelectedIndexChange = onJump,
    )
}

/**
 * 自启开关
 *
 * 「启动时自动运行」与「启动时记忆运行」是两个不同的策略，WebUI 把它们并排放在系统设置任务页上； 这里照做——只在别处给一个「自动运行」会让人以为记忆功能不存在。
 *
 * The startup-switches card.
 *
 * "Auto-run at start" and "remember the run at start" are two different policies; the WebUI puts
 * them side by side on the system-settings task page, and this card does the same — offering
 * "auto-run" alone elsewhere would make people believe the remember feature does not exist.
 *
 * @param repository 网关仓库 / the gateway repository
 * @param startup 自启配置；null 表示尚未加载，此时开关禁用 / the startup config; null while not yet loaded, in which
 *   case the switches are disabled
 */
@Composable
private fun StartupCard(repository: AzurPilotRepository, startup: AzurPilotStartup?) {
    val ready = startup != null
    com.azurpilot.ghio.ui.ksu.AppSettingsGroup(
        title = stringResource(R.string.ap_startup_title),
    ) {
        item {
            com.azurpilot.ghio.ui.components.AppSwitchPreference(
                title = stringResource(R.string.ap_startup_autorun),
                summary = stringResource(R.string.ap_startup_autorun_help),
                checked = startup?.enabled == true,
                enabled = ready,
                onCheckedChange = { repository.setStartup(enabled = it) },
            )
        }
        item {
            com.azurpilot.ghio.ui.components.AppSwitchPreference(
                title = stringResource(R.string.ap_startup_remember),
                summary = stringResource(R.string.ap_startup_remember_help),
                checked = startup?.remember == true,
                enabled = ready,
                onCheckedChange = { repository.setStartup(remember = it) },
            )
        }
    }
}

/**
 * 舰队扫描结果
 *
 * `FleetInfo.Result` 是 `display: hide` 的 `stored` 字段，通用表单看不见它，所以这个任务页 必须单独渲染——否则点进去只有一句「没有可配置项」。
 *
 * The fleet-scan result card.
 *
 * `FleetInfo.Result` is a `stored` field with `display: hide` that the generic form cannot see, so
 * this task page must render it on its own — otherwise the page shows nothing but "nothing to
 * configure".
 *
 * @param value `FleetInfo.Result` 的原始值 / the raw `FleetInfo.Result` value
 */
@Composable
private fun FleetInfoCard(value: ApValue) {
    val snapshot = remember(value) { parseFleetInfo(value) }
    when {
        snapshot == null ->
            AppCard {
                ApEmptyState(
                    icon = Icons.Filled.DirectionsBoat,
                    title = stringResource(R.string.ap_fleet_invalid),
                    hint = stringResource(R.string.ap_fleet_invalid_hint),
                )
            }

        snapshot.roles.isEmpty() ->
            AppCard {
                ApEmptyState(
                    icon = Icons.Filled.DirectionsBoat,
                    title = stringResource(R.string.ap_fleet_empty),
                    hint = stringResource(R.string.ap_fleet_empty_hint),
                )
            }

        else ->
            FLEET_NUMBERS.forEach { number ->
                AppCard(title = stringResource(R.string.ap_fleet_title, number)) {
                    val roles =
                        snapshot.roles.mapNotNull { (role, byFleet) ->
                            byFleet[number]?.let { role to it }
                        }
                    if (roles.isEmpty()) {
                        Text(
                            text = stringResource(R.string.ap_fleet_no_record),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        roles.forEach { (role, ships) ->
                            Column(
                                verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.xxs)
                            ) {
                                Text(
                                    text = stringResource(fleetRoleLabel(role)),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                ships.forEach { ship ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement =
                                            Arrangement.spacedBy(AppTokens.Spacing.sm),
                                    ) {
                                        Text(
                                            text = ship.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        ship.level?.let {
                                            Text(
                                                text = stringResource(R.string.ap_fleet_level, it),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
    }
}

/** 角色键 → 分区标题资源 / Maps a role key to its section-title resource. */
private fun fleetRoleLabel(role: String): Int =
    when (role) {
        "vanguard" -> R.string.ap_fleet_vanguard
        "main" -> R.string.ap_fleet_main
        else -> R.string.ap_fleet_submarine
    }

/** 一艘船：名字 + 可选等级 / One ship: a name plus an optional level. */
private data class FleetShip(val name: String, val level: Int?)

/** 舰队快照：角色 → 编队号 → 船列表 / A fleet snapshot: role → fleet number → ship list. */
private data class FleetSnapshot(val roles: Map<String, Map<Int, List<FleetShip>>>)

/**
 * 解析舰队快照
 *
 * 服务端存的是 `{vanguard: {"1": [{name, level}]}, main: …, submarine: …}`，但历史上也存过 JSON 字符串。两种都收；返回 null
 * 表示内容根本不像舰队记录（与「记录为空」区分开）。
 *
 * Parses a fleet snapshot.
 *
 * The server stores `{vanguard: {"1": [{name, level}]}, main: …, submarine: …}`, but historically
 * it also stored a JSON string. Both are accepted; returning null means the content does not look
 * like a fleet record at all (kept distinct from "the record is empty").
 */
private fun parseFleetInfo(value: ApValue): FleetSnapshot? {
    val map: Map<*, *> =
        when (value) {
            null -> return FleetSnapshot(emptyMap())
            is Map<*, *> -> value
            is String -> {
                if (value.isBlank()) return FleetSnapshot(emptyMap())
                runCatching { JSONObject(value).toValueMap() }.getOrNull() ?: return null
            }

            else -> return null
        }
    if (map.isEmpty()) return FleetSnapshot(emptyMap())
    val roles = LinkedHashMap<String, Map<Int, List<FleetShip>>>()
    FLEET_ROLES.forEach { role ->
        val byFleet = map[role] as? Map<*, *> ?: return@forEach
        val parsed = LinkedHashMap<Int, List<FleetShip>>()
        byFleet.forEach { (key, ships) ->
            val number = key?.toString()?.toIntOrNull() ?: return@forEach
            val list =
                (ships as? List<*>)
                    ?.mapNotNull { item ->
                        when (item) {
                            is String -> FleetShip(item, null)
                            is Map<*, *> ->
                                (item["name"] as? String)?.let {
                                    FleetShip(it, (item["level"] as? Number)?.toInt())
                                }

                            else -> null
                        }
                    }
                    .orEmpty()
            if (list.isNotEmpty()) parsed[number] = list
        }
        if (parsed.isNotEmpty()) roles[role] = parsed
    }
    return FleetSnapshot(roles)
}

/**
 * 受限 Lua 脚本的检查：返回 null 表示通过，否则是可直接显示的诊断文案
 *
 * Validates a restricted Lua script: null means it passed; otherwise the return value is a
 * diagnostic ready to display.
 *
 * @param repository 网关仓库 / the gateway repository
 * @param task 任务名，随校验请求回传 / the task name, sent with the validation request
 * @param script 脚本内容 / the script content
 * @return 诊断文案；通过时为 null / the diagnostic text, or null when valid
 */
private suspend fun checkScript(
    repository: AzurPilotRepository,
    task: String,
    script: String,
): String? {
    val instance = repository.selectedInstance.value ?: return null
    val result = repository.validateShopStrategy(instance, task, script) ?: return null
    if (result.valid) return null
    val first = result.diagnostics.firstOrNull() ?: return null
    val where = buildString {
        first.line?.let { append(" 第 $it 行") }
        first.column?.let { append(" 第 $it 列") }
    }
    return first.message + where
}

/** 由编辑器状态推导某参数行的展示状态 / Derives a field row's display status from the editor state. */
private fun fieldStatus(path: String, editor: AzurPilotEditorState): ApFieldStatus =
    when {
        editor.failed.containsKey(path) -> ApFieldStatus.Failed
        path in editor.saving -> ApFieldStatus.Saving
        path in editor.saved -> ApFieldStatus.Saved
        else -> ApFieldStatus.Idle
    }

/**
 * 一个参数要不要显示
 *
 * 与 WebUI 的 `isFieldVisible` 同一套规则：`display: hide` 不显示；空的 `storage` 既不生成字段也不生成分组——旧版
 * `put_arg_storage` 就是这么做的，空存储区显示出来只会是噪音。
 *
 * Whether an argument should be shown.
 *
 * Same rules as the WebUI's `isFieldVisible`: `display: hide` is not shown; an empty `storage`
 * produces neither a field nor a group — the legacy `put_arg_storage` behaves the same, and an
 * empty storage area would be pure noise on screen.
 */
private fun isFieldVisible(argument: String, field: AzurPilotField, value: ApValue): Boolean {
    if (argument == "_info" || field.hidden) return false
    if (field.type == "storage") {
        val map = value as? Map<*, *> ?: return true
        if (map.isEmpty()) return false
    }
    return true
}
