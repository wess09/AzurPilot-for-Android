package com.azurpilot.ghio.third

import java.io.IOException
import java.util.Scanner

/**
 * 同步执行外部命令并读取其 stdout 的小工具
 *
 * 三个方法全部阻塞到进程退出；调用方自行负责线程归属（勿在承载 Looper 的线程上调用）。
 * 退出码非 0 一律抛 [IOException]，把命令与退出码带进消息。
 *
 * 从 scrcpy 服务端的 `third/Command.java` 移植（Apache-2.0, Genymobile/scrcpy）。
 *
 * Small helper that runs external commands synchronously and reads their
 * stdout.
 *
 * All three methods block until the child process exits; callers own the thread
 * affinity (never call on a Looper-backed thread). Any non-zero exit code is
 * raised as an [IOException] carrying the command and the exit code.
 *
 * Ported from scrcpy's server `third/Command.java` (Apache-2.0,
 * Genymobile/scrcpy).
 */
object Command {

    /**
     * 执行命令，仅关心退出码 / Runs a command and only checks its exit code.
     *
     * @throws IOException 进程启动失败，或退出码非 0 / the process could not be
     *   started, or exited with a non-zero code
     * @throws InterruptedException 等待退出时当前线程被中断 / the current thread
     *   was interrupted while waiting for the exit
     */
    @Throws(IOException::class, InterruptedException::class)
    fun exec(vararg cmd: String) {
        val process = Runtime.getRuntime().exec(cmd)
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IOException("Command " + cmd.contentToString() + " returned with value " + exitCode)
        }
    }

    /**
     * 执行命令并读取完整 stdout / Runs a command and reads its full stdout.
     *
     * @return stdout 全文，按行拼接（每行补 `\n`）/ the whole stdout, joined
     *   line by line (each line terminated by `\n`)
     * @throws IOException 进程启动失败，或退出码非 0 / the process could not be
     *   started, or exited with a non-zero code
     * @throws InterruptedException 等待退出时当前线程被中断 / the current thread
     *   was interrupted while waiting for the exit
     */
    @Throws(IOException::class, InterruptedException::class)
    fun execReadOutput(vararg cmd: String): String {
        val process = Runtime.getRuntime().exec(cmd)
        val output = IO.toString(process.inputStream)
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IOException("Command " + cmd.contentToString() + " returned with value " + exitCode)
        }
        return output
    }
}
