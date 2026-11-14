package com.azurpilot.ghio.remoteaccess

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 保存一个 AP 的固定入口及独立授权；凭据只加密保存在应用的免备份目录。
 *
 * Stores an AP stable entry and independent authorization, encrypted in app no-backup storage.
 *
 * @property deviceId AP 安装标识。 / AP installation identifier.
 * @property name AP 显示名称。 / AP display name.
 * @property url 固定远程入口。 / Stable remote entry.
 * @property accessToken 可在 AP 撤销的凭据。 / Credential revocable in AP.
 */
@Serializable
data class RemotePairing(
    @SerialName("device_id") val deviceId: String,
    val name: String,
    val url: String,
    @SerialName("access_token") val accessToken: String,
)

/**
 * 负责注册服务调用与 Keystore 加密存储；所有磁盘和网络工作都在 IO 调度器。
 *
 * Calls the registry and stores Keystore-encrypted pairings, performing disk and network IO off-main.
 */
class RemotePairings(context: Context) {
    private val file = File(context.noBackupFilesDir, "remote-pairings.enc")
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).build()

    /** 固定注册服务地址。 / Fixed registry service URL. */
    companion object {
        const val REGISTRY = "https://remote.azurpilot.com"
        const val LEGACY_REGISTRY = "https://ap-remote.108-186-180-52.sslip.io:8443"
        private const val KEY_ALIAS = "azurpilot.remote-pairings.v1"
        private val lock = Any()
    }

    @Serializable
    private data class Claim(val code: String = "", @SerialName("scan_secret") val scanSecret: String = "")

    /** 读取已配对入口，解密失败时保留原文件供恢复。 / Reads pairings, preserving files on decryption failure. */
    suspend fun list(): List<RemotePairing> = withContext(Dispatchers.IO) { synchronized(lock) { read() } }

    /** 通过六码或扫描内容领取一次性配对。 / Claims a one-time pairing using six digits or scanned data. */
    suspend fun pair(input: String): RemotePairing = withContext(Dispatchers.IO) {
        val value = input.trim()
        val request = if (value.matches(Regex("[0-9]{6}"))) Claim(value) else {
            require(value.matches(Regex("[a-f0-9]{32}")))
            Claim(scanSecret = value)
        }
        synchronized(lock) { check(read().size < 20) }
        client.newCall(Request.Builder().url("$REGISTRY/api/v1/pairings/claim")
            .post(json.encodeToString(request).toRequestBody("application/json".toMediaType())).build())
            .execute().use { response ->
                if (!response.isSuccessful) throw registryFailure(response.code, response.body.string())
                val pairing = json.decodeFromString<RemotePairing>(response.body.string())
                require(pairing.deviceId.matches(Regex("[a-zA-Z0-9_-]{16,80}")))
                require(pairing.url == "$REGISTRY/d/${pairing.deviceId}/")
                require(pairing.accessToken.matches(Regex("[A-Za-z0-9_-]{43,128}")))
                synchronized(lock) { write(read().filterNot { it.deviceId == pairing.deviceId } + pairing) }
                pairing
            }
    }

    /** 移除本机保存的入口；AP 的授权可在 AP 远程页撤销。 / Removes a saved entry; AP can revoke its authorization. */
    suspend fun remove(deviceId: String) = withContext(Dispatchers.IO) {
        synchronized(lock) { write(read().filterNot { it.deviceId == deviceId }) }
    }

    /** 每次打开都重新解析固定入口，避免重启后继续使用旧 Tunnel。 / Resolves the stable URL on every open. */
    suspend fun resolve(pairing: RemotePairing): String = withContext(Dispatchers.IO) {
        require(pairing.url == "$REGISTRY/d/${pairing.deviceId}/")
        client.newCall(Request.Builder().url(pairing.url).header("Cache-Control", "no-cache").build())
            .execute().use { response ->
                check(response.code != 302 || response.header("Location") != "/error") { "AP 暂时离线" }
                check(response.code == 301) { "HTTP ${response.code}" }
                val location = response.header("Location").orEmpty()
                require(location.matches(Regex("https://[a-z0-9-]+\\.trycloudflare\\.com/")))
                location
            }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private fun read(): List<RemotePairing> {
        if (!file.exists()) return emptyList()
        val parts = file.readText().split('.')
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        val saved = json.decodeFromString<List<RemotePairing>>(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).decodeToString())
        // 只迁移已知试验入口，保留设备身份和独立授权。
        return saved.map { pairing ->
            if (pairing.url == "$LEGACY_REGISTRY/d/${pairing.deviceId}/")
                pairing.copy(url = "$REGISTRY/d/${pairing.deviceId}/") else pairing
        }
    }

    private fun write(pairings: List<RemotePairing>) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(json.encodeToString(pairings).encodeToByteArray())
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(encrypted, Base64.NO_WRAP))
        check(temp.renameTo(file))
    }
}
