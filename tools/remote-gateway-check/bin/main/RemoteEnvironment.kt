package com.azurpilot.ghio.remoteaccess

/** 合成凭据；网络解析由测试注入，不读取 Android 密钥。 / Synthetic credentials, no Android key access. */
data class RemotePairing(val deviceId: String, val name: String, val url: String, val accessToken: String)
class RemotePairings(val resolver: suspend (RemotePairing) -> String) {
    suspend fun resolve(pairing: RemotePairing) = resolver(pairing)
}
