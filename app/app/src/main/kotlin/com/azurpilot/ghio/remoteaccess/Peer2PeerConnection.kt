package com.azurpilot.ghio.remoteaccess

import java.io.IOException

/**
 * 已打开的 P2P 隧道；实现负责把帧映射到 DataChannel 消息。
 *
 * An opened P2P tunnel; implementations map frames onto DataChannel messages.
 */
interface Peer2PeerConnection {
    /** 本地 HTTP/WS 入口，P2P 打开后才可用。 / Local HTTP/WS entry, available after P2P opens. */
    val localUrl: String

    /**
     * 发送一个完整帧；返回 false 表示通道已关闭。
     *
     * Sends one complete frame; false means the channel is already closed.
     */
    fun send(frame: TunnelFrame): Boolean

    /** 关闭隧道并释放底层资源。 / Closes the tunnel and releases underlying resources. */
    fun close()

    /**
     * 设置远端帧回调；实现可能从任意线程调用。
     *
     * Sets the remote-frame callback; implementations may invoke it on any thread.
     */
    fun setFrameListener(listener: ((TunnelFrame) -> Unit)?)

    /** 设置远端关闭回调；只触发一次。 / Sets the remote-close callback; fired at most once. */
    fun setCloseListener(listener: (() -> Unit)?)
}

/** 建立隧道的工厂；抛出的任何异常都只作为 P2P 失败处理。 / Opens tunnels; any throw is treated as P2P failure only. */
fun interface Peer2PeerConnectionFactory {
    suspend fun connect(entry: String, deviceId: String, stunHost: String): Peer2PeerConnection
}

/**
 * JVM 默认工厂：没有 WebRTC 原生库时明确失败，保持测试可注入。
 *
 * Default JVM factory: fails explicitly without WebRTC natives, keeping tests injectable.
 */
object UnavailablePeer2PeerConnectionFactory : Peer2PeerConnectionFactory {
    override suspend fun connect(entry: String, deviceId: String, stunHost: String): Peer2PeerConnection =
        throw IOException("P2P unavailable")
}
