package com.azurpilot.ghio.remoteaccess

import kotlin.math.abs

/**
 * 按 Particle Code v1 公开帧格式独立实现差分解码，不依赖渲染器或 Android 相机。
 *
 * Independently decodes the published Particle Code v1 frame format without renderer or camera dependencies.
 * Protocol reference: https://github.com/tianrking/ParticlePair#particle-code-v1
 */
object ParticleCode {
    private val dataPositions = intArrayOf(3, 5, 6, 7, 9, 10, 11, 12)
    private val borders = (0 until 324).filter { it / 18 in listOf(0, 17) || it % 18 in listOf(0, 17) }
    private val payload = (1..16).flatMap { y -> (1..16).map { x -> y * 18 + x } }.take(252)
    private val transforms = (0..7).map { mode -> IntArray(324) { index ->
        var x = index % 18
        var y = index / 18
        if (mode >= 4) x = 17 - x
        repeat(mode % 4) { val oldX = x; x = 17 - y; y = oldX }
        y * 18 + x
    } }

    private fun sync(index: Int): Int {
        val row = index / 18
        return if ((row * 3 + index % 18 * 5 + if (row == 0) 1 else 0) % 7 < 3) 1 else -1
    }

    /**
     * 比较两个 18×18 绿色通道样本，校验同步、纠错和 CRC 后才返回 128 位扫描密钥。
     *
     * Returns a 128-bit scan secret only after differential sync, error correction, and CRC checks.
     */
    fun decode(current: FloatArray, previous: FloatArray): String? {
        if (current.size != 324 || previous.size != 324) return null
        for (mapping in transforms) {
            val delta = FloatArray(324) { current[mapping[it]] - previous[mapping[it]] }
            val positive = borders.filter { sync(it) == 1 }
            val negative = borders.filter { sync(it) == -1 }
            val high = positive.map { delta[it].toDouble() }.average()
            val low = negative.map { delta[it].toDouble() }.average()
            val gain = (high - low) / 2
            if (abs(gain) < 3) continue
            val offset = (high + low) / 2
            val bits = BooleanArray(324) { (delta[it] - offset) * gain > 0 }
            if (borders.count { bits[it] == (sync(it) == 1) } < 58) continue
            val packet = IntArray(21)
            var valid = true
            for (wordIndex in packet.indices) {
                var word = 0
                for (bit in 0..11) if (bits[payload[wordIndex * 12 + bit]]) word = word or (1 shl bit)
                var syndrome = 0
                for (position in 1..12) if (word and (1 shl (position - 1)) != 0) syndrome = syndrome xor position
                if (syndrome > 12) { valid = false; break }
                if (syndrome != 0) word = word xor (1 shl (syndrome - 1))
                dataPositions.forEachIndexed { bit, position ->
                    if (word and (1 shl (position - 1)) != 0) packet[wordIndex] = packet[wordIndex] or (1 shl bit)
                }
            }
            if (!valid || packet[0] != 167 || packet[1] != 1 || packet[2] != 16) continue
            var crc = 65535
            for (byte in packet.take(19)) {
                crc = crc xor (byte shl 8)
                repeat(8) { crc = ((crc shl 1) xor if (crc and 32768 != 0) 4129 else 0) and 65535 }
            }
            if (crc != (packet[19] shl 8 or packet[20])) continue
            return packet.slice(3..18).joinToString("") { it.toString(16).padStart(2, '0') }
        }
        return null
    }
}
