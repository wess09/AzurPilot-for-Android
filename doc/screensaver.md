# 息屏挂机 / Dim screen automation

## 中文

息屏挂机在后台模式下显示黑色遮罩，并把窗口亮度设为 `0.01`。显示器保持点亮，
任务继续运行；用户通过滑块退出遮罩。
独立全屏宿主隐藏状态栏和导航栏，黑色背景铺满屏幕；边缘系统手势可暂时唤出系统栏。
全屏宿主离开前台时同步撤下遮罩，避免导航栏恢复后继续常亮。

### 使用

1. 在主页点击「息屏挂机」，立即显示遮罩。首次使用需要授予悬浮窗权限，再点击一次入口。
2. 在「设置 → 显示」中开启「任务启动时自动进入」，下一次启动调度器或工具时自动显示遮罩。
3. 从滑块开始向右拖到末端，松手解锁。单击、从轨道其他位置拖动、短距离快划均不会解锁。
   TalkBack 用户可以通过自定义「解锁」操作退出。

遮罩显示本地时间、日期、电量，以及正在运行实例的任务图标和名称。
运行实例与正在浏览的实例不同时，优先使用运行实例的数据；没有任务数据时显示运行、
等待或连接状态。调度器、工具或运行环境停止时自动退出。
任务状态请求失败时保留上次已知状态；WebUI 可达不能单独证明任务已经停止。

### 防烧屏与布局

- 时钟独立刷新，在分钟边界更新，并遵循系统的 12/24 小时设置。
- 时间、任务卡和解锁条作为整体，无交互 30 秒后移动 1 个物理像素，之后每 30 秒继续一步。
- 漂移范围由内容和可用区域的实际尺寸决定，各方向最多 24 个物理像素，避开屏幕缺口。
- 触摸或回弹期间暂停；结束后重新等待完整 30 秒，避免滑块在手指下移动。
- 任务卡、图标底座、解锁轨道与滑块均为黑底细边框，减少大面积常亮色块。
- 矮屏与大字体允许内容滚动，保留解锁入口。
- 使用现有 MD3 暗色主题、动态色、字阶和容器形状；黑色背景保持固定，无持续循环动画。

### 实现位置

`ScreenSaverOverlayManager` 管理悬浮窗口、运行状态观察与最低亮度。
`ScreenSaverActivity` 提供沉浸式全屏宿主，遮罩退出时一并关闭。
`ScreenSaverView` 处理时钟、电池广播、任务展示、空闲计时与拖动。
`ScreenSaverDrift` 将每次漂移限制为一个轴上的一个物理像素。
`ScreenSaverControls` 提供主页入口与显示设置开关。任务展示复用
`AzurPilotRepository` 的热流，不增加 WebSocket 订阅或改变当前浏览实例。

### 验证

执行 `compileDebugKotlin -x verifyBundledAzurPilotRuntime` 与 i18n 检查。
APK 构建还需要通过 `app/scripts/fetch_ocr_runtime.py` 准备 OCR 打包库。
在 Android 设备上检查三键与手势导航的系统栏隐藏、边缘手势临时显示、完整拖动解锁、
短划回弹及退出后的亮度恢复。无交互 30 秒应移动一个物理像素；触摸与回弹后重新计时。
检查漂移边界、矮屏、大字体和全屏宿主离开前台时的清理。
任务自动进入应在调度器或工具启动时触发，准备环境本身不触发。

## English

Dim screen automation displays a black overlay in background mode and sets its window brightness
to `0.01`. The display stays on while tasks continue running. Dragging the slider dismisses it.
A separate immersive host hides status and navigation bars, with black covering the whole screen.
System edge gestures can reveal the bars transiently. Leaving the foreground dismisses the overlay
with its host so navigation does not remain illuminated beneath it.

### Usage

1. Tap **Dim screen** on the home page to show the overlay immediately. On first use, grant the
   overlay permission and tap the entry again.
2. Enable **Enter automatically when a run starts** under **Settings → Display** to show the
   overlay when the next scheduler or tool run starts.
3. Drag from the thumb to the right end and release to unlock. Taps, drags starting elsewhere on
   the track, and short fast swipes do not unlock. TalkBack users can dismiss it through the
   custom unlock action.

The overlay displays local time, date, battery level, and the running instance's task icon and name.
When the running and browsed instances differ, the running instance takes precedence. Missing task
data falls back to running, waiting, or connecting status. Stopping the scheduler, tool, or runtime
environment dismisses the overlay automatically.
Failed task-status requests retain the last known state; WebUI reachability alone does not confirm
that a task has stopped.

### Burn-in mitigation and layout

- The clock refreshes independently at minute boundaries and respects the system's 12/24-hour format.
- The clock, task card, and unlock bar move together by one physical pixel after 30 idle seconds,
  then take another step every 30 seconds.
- Drift uses measured content and available space, capped at 24 physical pixels per direction and
  avoiding display cutouts.
- Touches and settling pause movement; completion restarts the full 30-second idle delay.
- The task card, icon container, unlock track, and thumb use thin outlines on black to reduce large
  illuminated fills.
- Short screens and large fonts allow scrolling to keep the unlock control reachable.
- The existing MD3 dark theme supplies dynamic colors, typography, and container shapes. The black
  background stays fixed, with no continuous animation.

### Implementation

`ScreenSaverOverlayManager` manages the overlay window, run-state observation, and minimum brightness.
`ScreenSaverActivity` supplies the immersive host and closes with the overlay.
`ScreenSaverView` handles the clock, battery broadcast, task presentation, idle timer, and dragging.
`ScreenSaverDrift` limits each step to one physical pixel on one axis.
`ScreenSaverControls` supplies the home entry and display setting. Task presentation reuses
`AzurPilotRepository` flows without adding WebSocket subscriptions or changing the browsed instance.

### Verification

Run `compileDebugKotlin -x verifyBundledAzurPilotRuntime` and the i18n check. APK builds also require
the OCR libraries prepared by `app/scripts/fetch_ocr_runtime.py`. On an Android device, check hidden
bars with three-button and gesture navigation, transient bars from edge gestures, full-drag dismissal,
short-drag return, and restored brightness. After 30 idle seconds, content moves one physical pixel;
touches and settling restart the timer. Check drift boundaries, short screens, large fonts, and
cleanup when the immersive host leaves the foreground. Automatic entry follows scheduler or tool
startup; preparing the environment alone does not trigger it.
