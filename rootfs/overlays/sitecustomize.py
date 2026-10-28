"""仅在 AzurPilot for Android 运行时启用进程、设备、装备码、更新兼容层、后台 NTP、自律寻敌两帧确认和宿主 OCR。

本文件被装进 venv site-packages 顶层，任何解释器启动都会经 site 机制自动导入，
因此必须用 AZURPILOT_ANDROID 环境变量门控：开发机与 CI 的裸 Python 不受影响；
命中时才给 psutil 打桩、修正虚拟屏前台检测并接入装备码桥；宿主另行注入 OCR 地址时
安装张量代理。APK 也将本文件放在 PYTHONPATH 根目录，供已有 rootfs 升级使用。

Enables process, device, equipment-code, and update compatibility, background NTP, two-frame
auto-search confirmation, and host OCR inside the AzurPilot for Android runtime.

The file sits at the top of the venv's site-packages, so every interpreter
startup imports it automatically through the site mechanism; it must therefore
be gated on the AZURPILOT_ANDROID environment variable to leave bare
interpreters on dev machines and CI untouched. When the gate matches, psutil and virtual-display
app detection are patched and the equipment-code bridge is connected. A host OCR address enables
the tensor proxy. The APK also puts this file at the PYTHONPATH root to update existing rootfs
installations.
"""

import os

if os.environ.get("AZURPILOT_ANDROID") == "1":
    from android_process_compat import install

    install()

    from android_equipment import install as install_equipment

    install_equipment()

    from android_device import install as install_device

    install_device()

    from android_app_launch import install as install_app_launch

    install_app_launch()

    from android_capture import install as install_capture

    install_capture()

    try:
        from android_auto_search import install as install_auto_search

        install_auto_search()
    except ImportError as error:
        # 老 rootfs 升级场景可能没有 android_auto_search.py：缺失时跳过，
        # 不中止其余启动设置（更新层与 OCR 仍会安装）。
        import sys as _sys

        print(f'[azurpilot] android_auto_search overlay unavailable, skipped: {error}',
              file=_sys.stderr)

    try:
        from android_ntp import install as install_ntp

        install_ntp()
    except ImportError as error:
        # 老 rootfs 升级场景可能没有 android_ntp.py：NTP 缺失时跳过，
        # 不中止其余启动设置（更新层与 OCR 仍会安装）。
        import sys as _sys

        print(f'[azurpilot] android_ntp overlay unavailable, skipped: {error}',
              file=_sys.stderr)

    from android_update import install as install_update

    install_update()

    # 宿主未注入 OCR 地址时，保持旧 APK 和构建质量门的 CPU 行为。
    if os.environ.get("AZURPILOT_OCR_ADDRESS"):
        from android_ocr import install as install_ocr

        install_ocr()
