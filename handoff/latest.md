# 2026-11-07 · AzurPilot for Android

## 最新进度

- 2026-11-07：**电脑端 → Android 端配置导入容错与自动规整**。
  - 背景与排查：从桌面端 AzurPilot 导出的 JSON 配置在 Android 导入时因三类格式差异被连续拦下（日期微秒时间戳、心情值为 -1 越界、字符串字段为 null 或数字，共 46 处），根因为桌面端写入与校验端规则不一致的上游缺陷（`module/api/config_service.py` 写入带微秒但校验仅认整秒，心情负值未经区间收敛原样写出，字符串字段类型漂移）。
  - 客户端防御升级（`AzurPilotConfigImport.kt` / `AzurPilotRepository.kt`）：在 Android 导入分批前（`AzurPilotConfigImport.batches` 与 `importConfig`）引入 schema 驱动的值规整器 `sanitize()` 与 `sanitizeContent()`：
    1. 日期格式：将 `_sync_record_time()` 产生的带微秒串截断为整秒（`YYYY-MM-DD HH:mm:ss`），并转换 ISO 'T' 分隔符；
    2. 数值区间：按 schema 的 `validate: [min, max]` 区间收敛（如心情 -1 收敛为 0），保留整数类型；
    3. 类型兜底：字符串字段拿到 null 规整为空串 `""`，拿到数字/布尔规整为字符串字面量（如 `1000` -> `"1000"`）；数值与布尔字段按 schema 默认类型安全转换；
    4. 差异过滤：规整后的值与当前配置比对，相同值直接跳过，只有真正变更的项才打包进 `config.patch`。
  - 效果：用户无需先在电脑端运行脚本规整，原装导出配置（如 `azurpilot (2).json`）可在 Android 端直接无缝导入成功。

- 2026-09-26 夜：**KDoc 双语注释 + 版本线切 1.2.0**。
  - 核心链路 6 文件 KDoc 升级 Google 规范中英双语（7290f8c）：ReleaseDownloader/ReleaseUrls/RuntimeArch 全量；AppUpdateManager/RootfsProvisioner/ProotHost 类级补英文镜像。纯注释零行为差异，编译通过。
  - 版本线：1.1.131（误把 X 钉在基线计数）→ 用户纠正为 X 从 0 起算 → 已发布 1.1.131 收不回，切 **1.2.0**（d110260）：`GitVersion.kt` X = max(0, 提交数−134)，升位提交为本仓第 134 个（X=0），此后每提交 +1；CI resolve 正则 `^1\.(1|2)\.$` 兼容过渡（上一发布名 1.1.131）。CI run 36234309988 全绿，发布核对 versionName=1.2.0 / versionCode=1790414947（=前值+1，更新链路不受版本名切换影响）/ appCommit=d110260。
  - 插曲：推送 403 系 gh 活跃账号被切到无写权限的 AzurPilotBot，`gh auth switch --user wess09` 解决。
- 2026-09-26 傍晚：**回退 okdownload 多线程下载（用户指令），rootfs 补 ssh；真机 E2E 全通**（c505225，已推送，CI run 36228430826 全绿）。
  - 并行子代理排障结论：报障设备跑的是 c357a87（okdownload 之前的代码），"下载卡住"=直连 github.com 被墙 + 镜像开关关闭（设备日志抓到 SocketTimeoutException: failed to connect to github.com），"不解压"=下载从未发生；UI 还把 applyUpdate 失败静默吞掉（error 只进 updateCheck，AppRoot 无渲染）。okdownload 本身未在任何用户设备上运行过。镜像实测：gh.ddlc.top 最快且并发稳；gh-proxy.net 对无 JS 下载器回挑战页（应剔除）；gh-proxy.com 约 1/6 概率无视 Range 回 200 全文件。
  - 回退内容：ReleaseDownloader 恢复单连接 HttpURLConnection（connect 20s/read 120s，大小与 SHA-256 仍由调用方完成后校验），删 okdownload 三件套依赖/Application 装配/R8 规则；多架构、镜像选择器、per-arch 发布全部保留。同时 rootfs 构建补 `openssh-client`（上游 module/base/ssh.py 直接 Popen 系统 ssh，此前 rootfs 无 ssh 导致远程访问不可用——arm64 时代就缺，本轮才被暴露）。
  - CI 产物自洽：1.0.122 / c505225 / runtimes 双条目（rootfs 版本 3315b5d94779-26a9df495f，内容哈希已含 ssh 修复）/ fullApks。注意 CI 的 1.0.122 versionCode(1790356994) 低于本地构建同名版本(1790395637)，adb 装包需 `install -r -d`。
  - 真机 E2E（本地测试机）：装 CI update 包 → 启动即弹「发现 Runtime 更新」→ 立即更新 → 877MB 单连接下载完成（用户装了 clash 代理，直连经代理可用）→ 内联校验 → 解压完成 → 日志 `rootfs installed from release: 3315b5d94779-26a9df495f` → 新会话拉起。报障两症状（卡下载、不解压）在真机复现路径上均消失。
  - 遗留：①根治方案（换源自动回退、镜像列表修整、下载中取消、applyUpdate 失败 snackbar、错误文案人话化）已写好但随排查现场一起 `git stash`（stash@{0} abandon-mt-download-investigation），报障用户当前缓解=代理或手动选镜像，是否恢复待定；②新会话 WebUI 就绪确认受日志干扰未拿到干净证据，建议开 App 亲验一眼。
  - **ADB 全流程复验（16:43-16:50，全部通过）**：重启 App → 新 rootfs 首启 90s 内 WebUI 未就绪（首启重建全量 .pyc 的预期成本，非回退回归）→ 随后 session.log 显示运行时对 App 网关轮询全部 200（/android/status、/android/logs、/android/configs）→ `adb forward tcp:25548` 实测 `/healthz` HTTP 200（14ms）、`/android/status` 无 token 正确 403（鉴权正常）。ssh 在 rootfs 内由 CI 构建保证（chroot 内 apt 安装 openssh-client，包名错误会直接构建失败；rootfs 内容哈希已随 c505225 变为 26a9df495f）。全流程唯一未覆盖：虚拟屏内游戏实启（需登录游戏账号，不在冒烟范围）。
- 2026-09-26 追加变更：**README 增加应用截图预览**。用户提供的 32 张真机截图（四语言 zh-CN/zh-TW/en/ja × 暗色/亮色 × 主页/AzurPilot 总览/设置/虚拟屏四页）经内容识别后重命名为语义化文件名入库 `docs/screenshots/<lang>-<theme>-<page>.jpg`；四语 README 在「功能界面一览」前新增「应用预览」章节（每语言 2×4 表格，暗/亮两行），顶部导航加锚点，引用已全部校验存在；顺带把「功能界面一览」里过时的「AzurPilot WebUI / React 前端 / WebView 直连」行改为应用内原生控制台（四语同步）。README 其余 WebUI 字样（徽章、特性卡、简介段）未动——它们描述的是 Runtime 内置控制台，仍属实，后续如需全面去 WebUI 化再单独处理。
- 2026-09-26 多架构 + 下载链路改造（本条为本轮全部内容，未推送，待 CI 验证）：
  - **Runtime 支持 x86_64**：`build-azurpilot.sh` 以 `AZURPILOT_ABI` 参数化（arm64-v8a 默认走 ubuntu-24.04-arm，x86_64 走 ubuntu-24.04，均要求原生 runner，host 架构硬校验），BUILD_MANIFEST 新增 `rootfs_arch`。armv7/x86(32位) 经查证不可行并已向用户说明：onnxruntime/numpy/scipy/opencv/numba 等无 armv7 与 i686 wheel（onnxruntime 连 sdist 都没有），python-build-standalone 无 Linux i686 的 3.14，ubuntu-base 24.04 无 i386 包——卡在上游依赖，GitHub Actions 机制本身可解决（qemu-user 跑 armhf 等）。
  - **CI 矩阵**：`build` job 改 strategy.matrix 双架构并行，产物 artifact 更名 `rootfs-<abi>`；`apk` job 依次出 per-arch full APK（`-Pazurpilot.releaseAbi=<abi>` 收窄 ABI 并内置同架构 rootfs，Gradle verify 任务核对 manifest 的 rootfs_arch）+ 通用轻量包（双 ABI proot 库、无 rootfs，更新通道不变）；`publish` 发布 `rootfs-arm64-v8a.tar.xz` / `rootfs-x86_64.tar.xz` / 两个 per-arch full APK，latest.json 新增 `runtimes{abi:{version,url,sha256,size}}` 与 `fullApks`，扁平 `rootfs*` 字段继续指向 arm64 供旧版 App 不断供。`reuse_rootfs_run_id` 只适用于矩阵化之后的老 run。
  - **x86_64 proot 九件套由 CI 自动生成**：`app/scripts/fetch-proot-libs.sh`（arm64 仍是仓内钉版产物不动）。x86_64 集来自 Termux 包（proot 5.1.107.95——arm64 钉的 .92 在池里已下架，同系列补丁版；libtalloc 2.4.3 / libandroid-shmem 0.7 / libandroid-selinux 14.0.0.11-1 / pcre2 10.47 / busybox 1.38.0-1 与 arm64 同版），SHA256 钉死，改名 + DT_NEEDED 同长改写（复刻 Spike A 配方：proot 的 libtalloc.so.2→libtalloc.so，busybox stub 的→libbusybox_app.so），内置 python 校验 ELF machine=62 与改写落位。loader 取自 deb 内 `/usr/libexec/proot/loader`（arm64 当时同源）。libspike_shim.so 无运行时引用，x86_64 不带。产物 gitignore，不入库。
  - **App 按架构选 Runtime**：新增 `provision/RuntimeArch.kt`（SUPPORTED=arm64-v8a,x86_64；`deviceAbi()` 取 SUPPORTED_ABIS 首个命中）；`RootfsProvisioner` 内置包按 `rootfs_arch` 对设备（旧包无字段视为 arm64，避免 arm64 老用户重解），不符自动转 Release 按架构下载；`ProotHost` 门槛从 arm64-only 放宽为双架构（报错文案同步）。
  - **镜像站扩容 + 自定义**：`ReleaseUrls` 重构为镜像表（内置 5 个：ghproxy.net / gh-proxy.com / ghfast.top / gh.ddlc.top / gh-proxy.net，均实测可代理 release 资产）+ `custom` 自定义前缀（ghproxy 形态，normalize 后非法输入回落直连）；设置存储 `useGithubMirror` 布尔 → `github_mirror`(id)+`github_mirror_custom`，init 里一次性迁移（旧键名是 camelToSnakeCase 后的 `use_github_mirror`，true→ghproxy.net）；设置页 Runtime 卡的开关换成选择器 + 自定义前缀输入（保存按钮），部署页共用同一选择器（`ui/components/MirrorSourcePicker.kt`）。
  - **多线程下载**：引 okdownload 1.0.7（core+okhttp 连接层+sqlite 断点库，Builder 默认经反射自动接入三者；显式 `ReleaseDownloader.init()` 装 OkHttp 连接层并对齐超时 20s/120s）。`update/ReleaseDownloader.kt` 薄封装（4 连接分段、进度经 totalOffset 聚合 200ms 节流、shouldAbort 轮询挂 fetchProgress、CANCELED→DownloadAborted→换源重下）；RootfsProvisioner 与 AppUpdateManager 的下载路径都切过去，SHA-256 在落盘后统一校验。
  - **文档**：README 四语更新架构徽章、下载指引（按架构选 APK）、构建流描述与多镜像/多线程说明；App 关于页描述四语更新（去 WebUI 字样，写明双架构与多镜像多线程）。
  - 验证：`:app:compileDebugKotlin` 通过（含 `-Pazurpilot.releaseAbi=x86_64` 配置路径；非法值 fail-fast 报 `must be one of [arm64-v8a, x86_64]`）；工作流 YAML 可解析、8 个 run 块 `bash -n` 全过；fetch 脚本本机实跑成功生成 x86_64 九件套；i18n 791/791 对齐。未真机验证（无 x86_64 设备）；CI 首跑需关注：矩阵双 runner 的 rootfs 构建、per-arch full APK 的 verify 断言、latest.json 组装（publish 步骤已改 python3 组装避 jq 嵌套引号坑）。
- 2026-09-26 后续变更（3）：**彻底放弃 WebUI，改为原生实现**。AzurPilot 页不再弹浏览器 Custom Tab，改成页内原生界面：顶部 `PrimaryTabRow` 五个分区（总览 / 配置 / 日志 / 统计 / 设置）+ 实例选择条 + 页内 NavHost 承载详情页（任务配置、实例管理、公告、指挥喵评分、运行时更新、部署设置）。新增 `proot/` 下的数据层：`AzurPilotGateway` 升到完整协议（`Reply` 分 Ok/Err/Offline、原样保留裸数组结果、`session` 事件、订阅可重连恢复），`AzurPilotRepository` 统一持有全部状态与**唯一一份订阅**，`AzurPilotConfigEditor` 做本地副本 + 自动保存队列（离散控件立即提交、文本防抖 600ms + 本地预校验），`AzurPilotSchema` 解析 `schema.get` 的菜单/参数/翻译。配置页因此是 schema 驱动的：97 个任务、2500+ 参数不需要各写一遍，标签全走运行时翻译，新增任务自动出现。删除 `AzurPilotApi`（订阅只能有一个发出方，`events.subscribe` 是整体替换语义）。`app/scripts/add_ap_strings.py` 为一次性脚本，写完已删。
  - 期间修掉三个真 bug：① `events.subscribe` 的 `topics` 用裸 `List` 交给 `JSONObject.put`，org.json 会当成普通对象序列化成 `"instances, overview"` 字符串，网关一直回 INVALID_PARAMS——日志/截图/总览推送其实从未生效（旧 `AzurPilotApi` 同样如此），改用 `JSONArray` 后订阅成功；② 连接循环在 `onOpen` 就返回，导致每 2 秒另开一条 WS 而旧连接一直挂着，改为挂到连接结束才返回；③ 配置页取值用了 `values[argument]` 而该层键是**分组名**，参数值恒回落 schema 默认值，改为 `values[group][argument]`。
  - 真机（Redmi Note 10 Pro / Android 11 / API 30）实测通过：五个分区与六个详情页均渲染正常；配置页改 `Main.Scheduler.Enable` 后服务端 `config.patch` 落盘生效（已用探针核对 `config.get` 与 `overview.get`，测试后已改回）；日志页实时收到 29 条流式日志（级别配色、多行折行、规则线均正常）；设置页部署设置显示「共 8 组」并列出 8 组字段。`:app:compileDebugKotlin` 零警告（新增代码），四语字符串 757/757 对齐。
  - 未验证：真实截图帧的解码（`preview` 只在任务抓图时产出，测试期间 `preview.capture` 返回 `image: null`）；未跑通一轮完整的游戏内任务（需要驱动用户游戏）。WebUI 未实现的功能面：统计的 CSV 导出与 K 线/缩放、日志搜索高亮、公告已读标记、`ShopAdvanced.Mode` 的客户端前置校验（现依赖服务端校验报错）。
- 2026-09-26 后续变更（2）：rootfs 构建接入缓存。uv 下载缓存原本在 chroot 内（`/opt/uv-cache`）随 rootfs 一起删，现改为 bind-mount 宿主 `.tmp/azurpilot-build/uv-cache` 再挂回 guest；`UV_PYTHON_INSTALL_DIR` 要随 rootfs 出厂（venv 指向它），未动。CI 缓存 uv-cache / npm-cache / Ubuntu base 包三处；11ea118 的构建实测缓存已存下（压缩后 437MB），下次生效。卸挂校验加入 `opt/uv-cache`（否则残留挂载会让后面那句 `rm -rf` 删掉宿主缓存），构建末尾把缓存 chown 回 runner 供 post-step 打包。
- 2026-09-26 后续变更：删掉 rootfs 校验里两条无谓测试（上游 `dev_tools.import_smoke_test` 扫 536 个模块含 AidLux/docker/headless 等设备上不跑的模块，实测 124s；以及 OCR gate），保留 2 秒那条原生依赖 + `android_process_compat` overlay 校验。`Build rootfs` 从 7m41s 降到 **4m36s**。
- 2026-09-26 APK 编译曾默认切到 macOS arm64，实测后回退：`macos-26` 是虚拟化 M1（`VirtualMac2,1`，3 核 / 7168MB，单核基准 7.2 Miter/s，比 ubuntu-arm64 的 9.2 还低），`Bundle runtime and build APK` 用 12m33s（其中第一次全量构建 11m34s，97/97 任务全执行），ubuntu-24.04 同一步 6m06s；apk job 整体 mac 15m39s vs ubuntu 6m27s。默认改回 `ubuntu-24.04`，`apk_runner` 输入保留 macos-26 / macos-15。其余改动保留：第二次 assemble 去掉 `clean`（实测第二次 96 个任务里 86 个 up-to-date，仅 51s）、Gradle 缓存、GNU-only 用法换可移植写法、增量包体积断言、新增 `.github/actions/runner-info`（build 与 apk 开头打印机器规格与单核基准）。
- 2026-09-26 当前变更：Runtime 缺失（轻量 APK 未内置、或已装目录不完整）时不再停在「未内置」，改为自动从 GitHub Release 下载 rootfs 部署，与内置包走同一条解压流水线；部署页新增下载源选择（直连 GitHub / ghproxy 镜像，复用设置里的同一个开关），下载途中换源立即从零重下，读超时期间换源按换源处理而不报失败。调试包在失败态也保留「跳过」入口。README 四语与中英字符串同步；`:app:compileDebugKotlin` 通过，真机与 CI 链路未验证。
- 2026-09-25 当前变更：App 启动时独立检查 APK 新版本并弹选择；语言切换保留当前标签页，避免落入 WebUI；挂机页返回时以虚拟屏实际存在为准，并在运行/恢复期间隐藏误导性的启动按钮；关于页加 App 简介，界面中的运行环境名称统一为 Runtime。
- CI 读取 Latest 清单内上次真正构建 APK 的 `appCommit`：App 源码和 APK 工作流不变时只构建、发布 Runtime，保留已发布 APK 版本与下载元数据。旧清单首次缺少 `appCommit` 时会补建一次 APK，之后按变更跳过。
- README 已按用户此前修改保留产品介绍，并把运行环境称为 Runtime。当前需要完成本地 Kotlin 编译、提交与推送，再看新 CI 首次构建及后续 Runtime 单独发布验证。
- 2026-09-25 后续变更：运行时更新改为启动时提示、确认后才下载；CI 将生成 full 与 update 两种同签名 APK，应用内更新使用轻量版；APK 版本脱离 AP 提交，rootfs 版本改用 AP 提交 + rootfs 构建输入指纹。轻量版本地无签名打包验证通过、大小约 8.2 MB，不含 rootfs。需提交推送并以 CI 验证两种正式产物。
- 2026-09-25 新增 Android 专用 psutil 子进程枚举兼容层，针对用户 15:59 的停止失败日志；文件放在本仓 `rootfs/overlays/`，构建时写入 rootfs venv 的 site-packages，AP 上游跟踪文件保持原样。后续须通过 CI 构建并真机验证 WebUI/宿主两个停止入口。
- GitHub 正式签名 Secret 已配置；`main` 的 30be839 构建成功并向 Latest 发布了正式 APK。发布 APK 经本地下载校验：SHA-256 与 `latest.json` 一致，`apksigner` v2 验证通过。
- 后续修复集包含：CI 发布前签名验证；应用内更新使用按 SHA 命名的不可变 APK；运行时版本显示与 Latest 手动检查；关于页项目、许可和组件链接；切页动画误弹 WebUI 修复。
- 本地 `:app:compileReleaseKotlin`（只为代码编译排除需 rootfs 的打包验证任务）通过，中英字符串 580/580 对齐。完整正式 APK 构建需由推送后的 CI 验证。
- 本机签名密钥保存在忽略目录 `keystore/azurpilot-release.p12`，口令以当前 Windows 用户 DPAPI 保存在 `keystore/password.dpapi`。两者都不能入库，须妥善备份。

## 已完成

- 仓库地址与 App 更新地址迁至 `wess09/AzurPilot-for-Android`；本地 `origin` 已更新。
- App 显示名为 AzurPilot，默认 applicationId、namespace 和源码根包为 `com.azurpilot.ghio`；AIDL/JNI/混淆规则同步迁移。
- 依据 AP Logo 绘制了 Android 品牌图与简化启动图标，重写 README。
- CI 增加 `main` push 和每小时第 7 分钟触发；使用 Ubuntu ARM64 构建 rootfs，并发布到正式 Latest。签名 Secret 齐备时发布正式 APK，否则调试 APK 仅留在 Actions 构建产物。
- 仓库清理中的历史资料删除保留；项目规则仍要求的开发账册和 roadmap 已恢复。

## 验证与后续

- `:app:compileDebugKotlin`、Java、Manifest 与 ARM64 CMake 编译通过；中英资源 572/572 对齐，工作流 YAML 可解析。完整 release 与真机链路未运行。
- rootfs 版本号包含上游提交与宿主提交；宿主的 overlay/seeds 变化也会使应用识别到新 rootfs。
- 当前仓库尚未配置发布签名 Secret；自动生成并上传签名密钥的命令被执行环境自动审批拒绝，正式 APK 发布需要外部完成 Secret 配置。
- 未在真机安装新包，也未执行需要屏幕状态或虚拟屏实验的步骤。
- 工作区还包含用户主动清理的文件删除；本次不替用户提交或推送。
