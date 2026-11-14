package com.azurpilot.ghio.ocr

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import kotlinx.serialization.json.*

/**
 * 在 :ocr 进程托管原生推理；Binder 只控制存活，张量仍通过认证回环 API 传递。
 *
 * 避免 Binder 大张量限制，绑定期间继承宿主重要性，不另建前台通知。
 *
 * Hosts native inference in :ocr. Binder controls lifetime; tensors use the authenticated
 * loopback API. Avoids Binder tensor size limits and inherits host importance while bound,
 * without another foreground notification.
 */
class OcrWorkerService : Service() {
    private var server: OcrWorkerServer? = null
    private val binder = Binder()

    override fun onBind(intent: Intent): IBinder? {
        if (server == null) {
            val token = intent.getStringExtra("token") ?: return null
            val disabled = Json.parseToJsonElement(intent.getStringExtra("disabled_models") ?: "{}")
                .jsonObject.mapValues { it.value.jsonPrimitive.content }
            val acceleration = intent.getBooleanExtra("hardware_acceleration_enabled", true)
            server = OcrWorkerServer(this, token, disabled, acceleration).also { it.start() }
        }
        return binder.takeIf { server?.address?.isNotEmpty() == true }
    }

    override fun onDestroy() {
        server?.close()
        super.onDestroy()
    }
}
