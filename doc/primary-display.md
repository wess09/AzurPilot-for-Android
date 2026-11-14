# 主屏运行 / Run on the primary display

## 中文

在 Android 11 或更高版本的设备上，可使用无障碍服务直接控制主屏，不需要 Shizuku、Root 或虚拟屏。

1. 在云手机设置中将主屏分辨率设为 720p。游戏横屏时必须是 1280×720；启动游戏前允许主屏处于 720×1280 竖屏。应用不会修改分辨率或缩放截图。
2. 在 AzurPilot 中打开「设置 → 屏幕与运行模式」，选择「主屏（无障碍）」，再在系统设置中启用 AzurPilot 无障碍服务。
3. 返回主页开始运行，保持主屏亮起、解锁，游戏留在前台。主屏模式会隐藏悬浮球和控制面板，以免遮挡截图。要停止，在系统通知里点「停止运行」，也可以回到 AzurPilot 的主页停止。

主屏模式直接截取默认显示器，并用无障碍手势执行点按、长按和滑动。截图受系统频率限制；游戏退到后台时，不会把触摸发给其他应用。调度器关闭游戏时返回桌面，需要重开时重新建立游戏任务，不能强停应用进程。依赖特权的工具（例如清理游戏缓存或装备码剪贴板桥）需要虚拟屏模式。

主屏设备桥使用抽象 Unix socket，地址为 `<applicationId>.primary.v1`，并验证客户端 UID 与 App 一致。每条连接只处理一条 JSON 请求；截图回复头之后是 1280×720 的 BGR888 数据。运行时在创建 Android 设备对象时选定通道，主屏操作失败不会回退到虚拟屏。

## English

On Android 11 or later, you can use the accessibility service to control the primary screen without Shizuku, root, or a virtual display.

1. Set your cloud phone's primary screen to 720p. When the game is in landscape, the screen must be 1280×720. The 720×1280 portrait orientation is allowed before game launch. The app does not change the resolution or scale screenshots.
2. In AzurPilot, open **Settings → Screen & run mode**, choose **Primary screen (accessibility)**, then enable the AzurPilot accessibility service in system settings.
3. Start the run from the home page and keep the screen on and unlocked, with the game in the foreground. Primary mode hides the floating ball and control panel so they don't cover the game in screenshots. To stop, tap **Stop** in the system notification or return to AzurPilot's home page.

Primary mode captures the default display directly and uses accessibility gestures for taps, long presses, and swipes. The OS limits the screenshot rate. Touches are rejected when the game is in the background. Stopping the game takes the device to the launcher; restarting recreates the game task without force-stopping its process. Features that need privileged access, such as clearing the game cache or the equipment code clipboard bridge, require virtual-display mode.

The primary device bridge uses the abstract Unix socket `<applicationId>.primary.v1` and checks that the client shares the app's UID. Each connection handles one JSON request. Successful capture metadata is followed by 1280×720 BGR888 pixels. The runtime selects a transport when it creates an Android device object; failures in primary mode never fall back to the virtual display.
