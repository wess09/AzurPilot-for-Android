package com.azurpilot.ghio.proot

/** 测试夹具只替代本机监听地址，不替代生产网关。 / Replaces only the local endpoint in gateway tests. */
object RuntimeConnection {
    var endpoint = Endpoint("http://127.0.0.1:1/")
    data class Endpoint(val base: String) { val wsUrl get() = base + "api/v1/ws" }
}
typealias ApValue = Any?
