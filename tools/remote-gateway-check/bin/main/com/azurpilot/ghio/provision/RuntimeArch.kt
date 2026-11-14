package com.azurpilot.ghio.provision

import android.os.Build

/**
 * Runtime（rootfs）支持的设备 ABI 清单与设备侧解析。
 *
 * 命名与 rootfs 构建脚本写进 BUILD_MANIFEST 的 `rootfs_arch`、CI 矩阵的 job 名、
 * Release 的 `runtimes` 键完全一致：新增架构要同时补 `rootfs/build/build-azurpilot.sh`
 * 的映射表与 `.github/workflows/rootfs.yml` 的矩阵。
 *
 * The set of device ABIs the Runtime (rootfs) supports, plus device-side
 * resolution.
 *
 * Names match `rootfs_arch` in BUILD_MANIFEST, the CI matrix job names, and
 * the `runtimes` keys in the release manifest. Adding an architecture means
 * updating the mapping table in `rootfs/build/build-azurpilot.sh` and the
 * matrix in `.github/workflows/rootfs.yml` at the same time.
 */
object RuntimeArch {
    /** ARM64 设备（rootfs 主架构）/ ARM64 devices (the rootfs's primary arch). */
    const val ARM64 = "arm64-v8a"

    /** x86_64 设备 / x86_64 devices. */
    const val X86_64 = "x86_64"

    /** proot 不做指令翻译，只支持这些架构的原生执行 / proot performs no instruction translation; only these ABIs run natively. */
    val SUPPORTED = listOf(ARM64, X86_64)

    /**
     * 设备首选的受支持 ABI（SUPPORTED_ABIS 自带偏好序）；全不支持时返回 null。
     *
     * The device's first supported ABI (SUPPORTED_ABIS is already in
     * preference order); null when none is supported.
     */
    fun deviceAbi(): String? = Build.SUPPORTED_ABIS.firstOrNull { it in SUPPORTED }
}
