package com.azurpilot.ghio.proot

/**
 * 保存当前会话的连接地址；配置修改在下次启动时发布，避免保存后访问尚未切换的端口。
 *
 * Stores the current session endpoint. Changes are published at startup so saving does not
 * redirect requests to a port the running server has not adopted. IO writes are volatile.
 */
object RuntimeConnection {
    /** 当前会话的本机入口。 / The current session's local endpoint. */
    @Volatile
    var endpoint: Endpoint = Endpoint("127.0.0.1", ProotHost.WEBUI_PORT, false)
        internal set

    /**
     * 描述监听配置对应的本机连接地址。
     *
     * Describes the local connection address corresponding to the bind configuration.
     *
     * @property host 回环主机名，IPv6 含方括号。 / Loopback host, bracketed for IPv6.
     * @property port 当前会话的端口。 / The current session port.
     * @property tls 是否使用配置的 TLS 证书。 / Whether configured TLS certificates are in use.
     */
    data class Endpoint(val host: String, val port: Int, val tls: Boolean) {
        /** HTTP 接口入口。 / The HTTP API base URL. */
        val httpBase: String get() = "${if (tls) "https" else "http"}://$host:$port"
        /** WebSocket 网关入口。 / The WebSocket gateway URL. */
        val wsUrl: String get() = "${if (tls) "wss" else "ws"}://$host:$port/api/v1/ws"
    }
}
