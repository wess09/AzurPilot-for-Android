# 息屏挂机 / Dim screen automation

## 中文

息屏挂机在后台模式下显示黑色遮罩，并把窗口亮度设为 `0.01`。显示器保持点亮，
任务继续运行；用户通过滑块退出遮罩。

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
- 时间、任务卡和解锁条作为整体，每 10 分钟切换位置。
- 换位范围由遮罩内容和可用区域的实际尺寸决定，避开系统栏和屏幕缺口。
- 拖动或回弹期间延后换位，避免滑块在手指下移动。
- 矮屏与大字体允许内容滚动，保留解锁入口。
- 使用现有 MD3 暗色主题、动态色、字阶和容器形状；黑色背景保持固定，无持续循环动画。

### 实现位置

`ScreenSaverOverlayManager` 管理悬浮窗口、运行状态观察与最低亮度。
`ScreenSaverView` 处理时钟、电池广播、任务展示、换位和拖动。
`ScreenSaverControls` 提供主页入口与显示设置开关。任务展示复用
`AzurPilotRepository` 的热流，不增加 WebSocket 订阅或改变当前浏览实例。

### 验证

执行 `compileDebugKotlin -x verifyBundledAzurPilotRuntime` 与 i18n 检查。
APK 构建还需要通过 `app/scripts/fetch_ocr_runtime.py` 准备 OCR 打包库。
在 Android 设备上检查完整拖动解锁、短划回弹、遮罩退出后亮度恢复，以及跨过
10 分钟边界后整块内容换位。任务自动进入应在调度器或工具启动时触发，准备环境本身不触发。

## English

Dim screen automation displays a black overlay in background mode and sets its window brightness
to `0.01`. The display stays on while tasks continue running. Dragging the slider dismisses it.

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
- The clock, task card, and unlock bar move together every 10 minutes.
- Movement uses the measured content and available area, avoiding system bars and display cutouts.
- Movement waits until dragging and the return animation finish, keeping the thumb under the finger.
- Short screens and large fonts allow scrolling to keep the unlock control reachable.
- The existing MD3 dark theme supplies dynamic colors, typography, and container shapes. The black
  background stays fixed, with no continuous animation.

### Implementation

`ScreenSaverOverlayManager` manages the overlay window, run-state observation, and minimum brightness.
`ScreenSaverView` handles the clock, battery broadcast, task presentation, movement, and dragging.
`ScreenSaverControls` supplies the home entry and display setting. Task presentation reuses
`AzurPilotRepository` flows without adding WebSocket subscriptions or changing the browsed instance.

### Verification

Run `compileDebugKotlin -x verifyBundledAzurPilotRuntime` and the i18n check. APK builds also require
the OCR libraries prepared by `app/scripts/fetch_ocr_runtime.py`. On an Android device, check full
drag dismissal, short-drag return, restored brightness after dismissal, and whole-content movement
after crossing a 10-minute boundary. Automatic entry follows scheduler or tool startup; preparing
the environment alone does not trigger it.
