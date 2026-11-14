package com.azurpilot.ghio.remoteaccess

import org.json.JSONObject
import java.io.IOException

/** 配对服务的业务失败类别。 / Business failures returned by the pairing registry. */
enum class RemoteRegistryFailure { UNAVAILABLE_CODE, DEVICE_OFFLINE, RATE_LIMITED, API_MISMATCH, SERVICE_ERROR }

/** 保留状态及错误类别，不携带配对码或凭据。 / Keeps status and failure category without secrets. */
class RemoteRegistryException(val failure: RemoteRegistryFailure, val status: Int) : IOException("Registry HTTP $status")

/** 解析 Go 服务错误，并兼容旧服务的 JSON 404。 / Parses Go errors and legacy JSON 404 responses. */
fun registryFailure(status: Int, body: String): RemoteRegistryException {
    val value = runCatching { JSONObject(body) }.getOrNull()
    val code = value?.optString("code").orEmpty()
    val failure = when {
        code in setOf("PAIRING_UNAVAILABLE", "PAIRING_EXPIRED", "INVALID_PAIRING_CODE") || status == 410 ->
            RemoteRegistryFailure.UNAVAILABLE_CODE
        code == "DEVICE_OFFLINE" -> RemoteRegistryFailure.DEVICE_OFFLINE
        code == "PAIRING_RATE_LIMITED" || status == 429 -> RemoteRegistryFailure.RATE_LIMITED
        status == 404 && code.isEmpty() && value?.optString("error").orEmpty().startsWith("配对") ->
            RemoteRegistryFailure.UNAVAILABLE_CODE
        status == 404 -> RemoteRegistryFailure.API_MISMATCH
        else -> RemoteRegistryFailure.SERVICE_ERROR
    }
    return RemoteRegistryException(failure, status)
}
