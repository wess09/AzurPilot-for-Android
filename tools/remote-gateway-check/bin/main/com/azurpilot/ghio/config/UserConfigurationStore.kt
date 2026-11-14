package com.azurpilot.ghio.config

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import com.azurpilot.ghio.domain.UserConfiguration
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/**
 * 承载 App 私有设置（[UserConfiguration]）的持久化契约
 *
 * 文件版本不兼容时回落当前默认值，不把异常抛给调用方。
 *
 * Carries the persistence contract for the app's private [UserConfiguration].
 *
 * Falls back to the current defaults on an incompatible file version instead
 * of throwing at the caller.
 */
interface UserConfigurationStore {
    /** 当前配置流 / The current configuration stream. */
    val data: Flow<UserConfiguration>

    /**
     * 以读-改-写方式原子更新配置 / Updates the configuration atomically via read-modify-write.
     */
    suspend fun update(transform: (UserConfiguration) -> UserConfiguration)
}

/**
 * [UserConfigurationStore] 的 DataStore 实现：纯委托，不带额外策略
 * The DataStore-backed [UserConfigurationStore]: a pure delegate with no extra
 * policy of its own.
 */
class DataStoreUserConfigurationStore(
    private val store: DataStore<UserConfiguration>,
) : UserConfigurationStore {
    override val data: Flow<UserConfiguration> = store.data

    override suspend fun update(transform: (UserConfiguration) -> UserConfiguration) {
        store.updateData(transform)
    }
}

/**
 * 序列化信封：把 schema 版本与配置包在一起，读侧据此判定兼容性
 *
 * Serialization envelope wrapping the configuration with its schema version so
 * the read side can decide compatibility.
 */
@Serializable
private data class ConfigurationEnvelope(
    val schemaVersion: Int,
    val configuration: UserConfiguration,
)

/**
 * [UserConfiguration] 的 JSON 序列化器
 *
 * 文件内容是 [ConfigurationEnvelope] 信封：版本匹配才解出配置，版本不匹配
 * （含空文件）一律回落 [defaultValue]；JSON 结构损坏抛 [CorruptionException]，
 * 由建库处的 ReplaceFileCorruptionHandler 换回默认值并重写文件。
 *
 * Serializes [UserConfiguration] as JSON.
 *
 * The file holds a [ConfigurationEnvelope]: the configuration is unwrapped
 * only when the schema version matches; any mismatch (including a blank file)
 * falls back to [defaultValue], while structurally corrupt JSON throws
 * [CorruptionException], which the store's ReplaceFileCorruptionHandler answers
 * with defaults and a rewritten file.
 */
object UserConfigurationSerializer : Serializer<UserConfiguration> {
    /** 当前信封版本；改动即让不匹配的旧文件整体回落默认值 / The current envelope version; changing it resets old files wholesale to defaults. */
    private const val SCHEMA_VERSION = 1
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override val defaultValue: UserConfiguration = UserConfiguration()

    override suspend fun readFrom(input: InputStream): UserConfiguration {
        val content = input.readBytes().decodeToString()
        if (content.isBlank()) return defaultValue
        try {
            val envelope = json.decodeFromString<ConfigurationEnvelope>(content)
            return if (envelope.schemaVersion == SCHEMA_VERSION) envelope.configuration else defaultValue
        } catch (exc: SerializationException) {
            throw CorruptionException("Invalid user configuration", exc)
        }
    }

    override suspend fun writeTo(t: UserConfiguration, output: OutputStream) {
        output.write(json.encodeToString(ConfigurationEnvelope(SCHEMA_VERSION, t)).encodeToByteArray())
    }
}
