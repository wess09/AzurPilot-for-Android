package com.azurpilot.ghio.remoteaccess

import java.io.IOException

/** P2P 隧道帧类型；数值是协议 v1 的线上约定。 / Tunnel frame types; values are protocol v1 wire constants. */
object TunnelFrameType {
    const val HTTP_OPEN = 1
    const val HTTP_HEAD = 2
    const val DATA = 3
    const val DONE = 4
    const val WS_OPEN = 5
    const val WS_TEXT = 6
    const val WS_BIN = 7
    const val WS_CLOSE = 8
    const val PING = 9
    const val PONG = 10
    const val WS_END = 11

    val ALL = HTTP_OPEN..WS_END
}

/** 单帧 payload 上限；更大内容由调用方拆块。 / Maximum one-frame payload; callers split larger content. */
const val TUNNEL_MAX_PAYLOAD = 49152

private const val TUNNEL_HEADER_SIZE = 7

/**
 * 一个已解码的隧道帧；[streamId] 按 u16 无符号处理。
 *
 * One decoded tunnel frame; [streamId] is an unsigned u16 value.
 */
class TunnelFrame(val type: Int, val streamId: Int, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is TunnelFrame && other.type == type && other.streamId == streamId &&
            other.payload.contentEquals(payload)

    override fun hashCode(): Int = type * 31 + streamId + payload.contentHashCode()
}

/** 线上帧不合法时抛出；调用方关闭对应流而不是崩溃。 / Raised for invalid wire frames; callers close the stream. */
class TunnelProtocolException(message: String) : IOException(message)

/**
 * 编码一个完整帧；payload 必须已经不超过协议上限。
 *
 * Encodes one complete frame; the payload must already respect the protocol limit.
 */
fun encodeTunnelFrame(frame: TunnelFrame): ByteArray {
    if (frame.type !in TunnelFrameType.ALL) throw TunnelProtocolException("未知帧类型 ${frame.type}")
    if (frame.streamId !in 0..65535) throw TunnelProtocolException("streamId 超出 u16")
    if (frame.payload.size > TUNNEL_MAX_PAYLOAD) throw TunnelProtocolException("帧 payload 超过上限")
    return ByteArray(TUNNEL_HEADER_SIZE + frame.payload.size).apply {
        this[0] = frame.type.toByte()
        this[1] = (frame.streamId ushr 8).toByte()
        this[2] = frame.streamId.toByte()
        val length = frame.payload.size
        this[3] = (length ushr 24).toByte()
        this[4] = (length ushr 16).toByte()
        this[5] = (length ushr 8).toByte()
        this[6] = length.toByte()
        frame.payload.copyInto(this, TUNNEL_HEADER_SIZE)
    }
}

/** 把 body 拆成一串 DATA 帧；空 body 返回空列表。 / Splits a body into DATA frames; empty yields none. */
fun tunnelDataFrames(streamId: Int, payload: ByteArray): List<TunnelFrame> =
    payload.asList().chunked(TUNNEL_MAX_PAYLOAD).map {
        TunnelFrame(TunnelFrameType.DATA, streamId, it.toByteArray())
    }

/**
 * 按协议修正编码一条 WS 消息：首块 + DATA 续块 + WS_END。
 *
 * Encodes one WS message per the protocol amendment: first chunk + DATA continuations + WS_END.
 */
fun tunnelWsMessageFrames(firstType: Int, streamId: Int, payload: ByteArray): List<TunnelFrame> {
    require(firstType == TunnelFrameType.WS_TEXT || firstType == TunnelFrameType.WS_BIN)
    val chunks = payload.asList().chunked(TUNNEL_MAX_PAYLOAD)
    return buildList(capacity = chunks.size.coerceAtLeast(1) + 1) {
        add(TunnelFrame(firstType, streamId, chunks.firstOrNull()?.toByteArray() ?: ByteArray(0)))
        chunks.drop(1).forEach { add(TunnelFrame(TunnelFrameType.DATA, streamId, it.toByteArray())) }
        add(TunnelFrame(TunnelFrameType.WS_END, streamId, ByteArray(0)))
    }
}

/**
 * 流式解码器：输入可以任意切分，只输出完整帧。
 *
 * Streaming decoder: input may split arbitrarily; only complete frames are emitted.
 */
class TunnelFrameDecoder {
    private var buffer = ByteArray(0)

    /** 追加字节并返回当前可解码的完整帧。 / Appends bytes and returns every complete frame now available. */
    fun feed(input: ByteArray): List<TunnelFrame> {
        buffer = if (buffer.isEmpty()) input.copyOf() else buffer + input
        val frames = ArrayList<TunnelFrame>()
        var offset = 0
        while (buffer.size - offset >= TUNNEL_HEADER_SIZE) {
            val type = buffer[offset].toInt() and 0xff
            val streamId = ((buffer[offset + 1].toInt() and 0xff) shl 8) or
                (buffer[offset + 2].toInt() and 0xff)
            val length = (((buffer[offset + 3].toInt() and 0xff) shl 24) or
                ((buffer[offset + 4].toInt() and 0xff) shl 16) or
                ((buffer[offset + 5].toInt() and 0xff) shl 8) or
                (buffer[offset + 6].toInt() and 0xff)).toLong() and 0xffffffffL
            if (type !in TunnelFrameType.ALL) throw TunnelProtocolException("未知帧类型 $type")
            if (length > TUNNEL_MAX_PAYLOAD) throw TunnelProtocolException("帧长度 $length 超过上限")
            val end = offset + TUNNEL_HEADER_SIZE + length.toInt()
            if (buffer.size < end) break
            frames.add(TunnelFrame(type, streamId, buffer.copyOfRange(offset + TUNNEL_HEADER_SIZE, end)))
            offset = end
        }
        buffer = if (offset == 0) buffer else buffer.copyOfRange(offset, buffer.size)
        return frames
    }
}
