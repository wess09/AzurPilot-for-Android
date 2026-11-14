package com.azurpilot.ghio.report

import android.content.Context
import android.util.Base64
import com.azurpilot.ghio.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 返回固定 GitHub 工单中的评论地址及去重结果。
 *
 * Returns the comment URL within the fixed GitHub issue and deduplication result.
 *
 * @property commentUrl 固定工单中的 HTTPS 评论链接。 / HTTPS comment URL in the fixed issue.
 * @property commentId 64 位评论编号。 / 64-bit comment ID.
 * @property issueNumber 工单编号。 / Issue number.
 * @property duplicate 是否复用已有评论。 / Whether an existing comment was reused.
 */
@Serializable
data class DeviceReportResult(
    val commentUrl: String,
    val commentId: Long,
    val issueNumber: Int,
    val duplicate: Boolean,
)

/**
 * 将提交错误映射为本地文案，不显示服务器返回正文。
 *
 * Maps submission errors to local copy without exposing server response bodies.
 *
 * @property messageRes 可本地化错误文案。 / Localizable error message.
 */
class DeviceReportException(val messageRes: Int) : IOException()

/**
 * 经系统信任链校验服务器，以客户端证书执行 mTLS，并签署正文供源站校验。
 *
 * Uses system trust to validate the server, a client certificate for mTLS, and signed bodies
 * for origin verification. Network work runs on IO; cancellation closes the HTTP call.
 */
class DeviceReportClient(private val context: Context) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val credentials by lazy { loadCredentials() }

    private data class Credentials(val client: OkHttpClient, val key: PrivateKey)

    private fun loadCredentials(): Credentials {
        try {
            val cert = context.assets.open("device-report/client-cert.pem").use {
                CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
            }
            cert.checkValidity()
            val encoded = context.assets.open("device-report/client-key.pem").bufferedReader().use {
                it.readText().replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "").replace(Regex("\\s"), "")
            }
            val key = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(encoded, Base64.DEFAULT)))
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setKeyEntry("device-report", key, CharArray(0), arrayOf(cert))
            }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, CharArray(0))
            }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(null as KeyStore?)
            }.trustManagers.filterIsInstance<X509TrustManager>().single()
            val ssl = SSLContext.getInstance("TLS").apply {
                init(managers.keyManagers, arrayOf(trust), null)
            }
            return Credentials(
                OkHttpClient.Builder()
                    .sslSocketFactory(ssl.socketFactory, trust)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .retryOnConnectionFailure(false)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .callTimeout(45, TimeUnit.SECONDS)
                    .build(),
                key,
            )
        } catch (_: Exception) {
            throw DeviceReportException(R.string.device_report_unavailable)
        }
    }

    /**
     * 只发送用户已选择实测结果的快照；不自动重试写请求。
     *
     * Sends only snapshots with an explicitly selected tested result, without automatic write retries.
     */
    suspend fun submit(report: DeviceReport): DeviceReportResult = withContext(Dispatchers.IO) {
        if (report.compatibility !in setOf("working", "not_working")) {
            throw DeviceReportException(R.string.device_report_test_required)
        }
        val auth = credentials
        val body = json.encodeToString(report).toByteArray(Charsets.UTF_8)
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val prefix = "POST\n/v1/device-reports\n$timestamp\n$nonce\n".toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(auth.key)
            update(prefix)
            update(body)
            Base64.encodeToString(sign(), Base64.NO_WRAP)
        }
        val request = Request.Builder()
            .url("https://afa-api.azurpilot.com/v1/device-reports")
            .header("User-Agent", "AzurPilotAndroid/" + report.appVersion)
            .header("X-Report-Timestamp", timestamp)
            .header("X-Report-Nonce", nonce)
            .header("X-Report-Signature", signature)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        auth.client.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw DeviceReportException(
                    when (response.code) {
                        401, 403 -> R.string.device_report_auth_error
                        429 -> R.string.device_report_rate_limited
                        502, 503, 504 -> R.string.device_report_server_error
                        else -> R.string.device_report_send_error
                    },
                )
            }
            val responseBody = response.body
            val bytes = responseBody.byteStream().use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 8192) throw IOException("Response too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val result = json.decodeFromString<DeviceReportResult>(bytes.toString(Charsets.UTF_8))
            if (result.issueNumber != 1 || result.commentId <= 0 ||
                result.commentUrl != "https://github.com/wess09/AzurPilot-for-Android/issues/1#issuecomment-${result.commentId}"
            ) throw IOException("Invalid comment URL")
            result
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }
}
