package com.azurpilot.ghio.remote.internal

/**
 * 串行化特权进程中虚拟屏的建立、释放和重建。
 *
 * Serializes creation, release, and recreation of the virtual display in the privileged process.
 */
internal val displayLifecycleLock = Any()
