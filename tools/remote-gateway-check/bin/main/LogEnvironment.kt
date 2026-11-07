package timber.log

/** 忽略 Android 日志依赖，避免测试泄露合成凭据。 / Omits Android logging in JVM fixtures. */
object Timber {
    fun d(message: String) = Unit
    fun d(error: Throwable, message: String) = Unit
    fun w(message: String, vararg arguments: Any?) = Unit
}
