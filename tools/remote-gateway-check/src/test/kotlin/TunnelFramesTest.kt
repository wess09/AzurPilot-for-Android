import com.azurpilot.ghio.remoteaccess.TunnelFrame
import com.azurpilot.ghio.remoteaccess.TunnelFrameDecoder
import com.azurpilot.ghio.remoteaccess.TunnelFrameType
import com.azurpilot.ghio.remoteaccess.TunnelProtocolException
import com.azurpilot.ghio.remoteaccess.TUNNEL_MAX_PAYLOAD
import com.azurpilot.ghio.remoteaccess.encodeTunnelFrame
import com.azurpilot.ghio.remoteaccess.tunnelDataFrames
import com.azurpilot.ghio.remoteaccess.tunnelWsMessageFrames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 验证 v1 帧头、分块与坏帧隔离。 / Verifies the v1 frame header, chunking, and bad-frame isolation. */
class TunnelFramesTest {
    @Test fun roundTripsSmallAndEmptyFrames() {
        val frames = listOf(
            TunnelFrame(TunnelFrameType.HTTP_OPEN, 1, """{"m":"GET"}""".toByteArray()),
            TunnelFrame(TunnelFrameType.DONE, 65535, ByteArray(0)),
        )
        val decoded = TunnelFrameDecoder().feed(frames.flatMap { encodeTunnelFrame(it).asList() }.toByteArray())
        assertEquals(frames, decoded)
    }

    @Test fun splitsLargePayloadAndReassemblesDataFrames() {
        val payload = ByteArray(TUNNEL_MAX_PAYLOAD * 2 + 7) { (it % 251).toByte() }
        val chunks = tunnelDataFrames(7, payload)
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it.payload.size <= TUNNEL_MAX_PAYLOAD })
        val decoded = TunnelFrameDecoder().feed(chunks.flatMap { encodeTunnelFrame(it).asList() }.toByteArray())
        assertEquals(payload.toList(), decoded.flatMap { it.payload.asList() })
    }

    @Test fun wsMessagesUseFirstChunkContinuationsAndEndMarker() {
        val payload = ByteArray(TUNNEL_MAX_PAYLOAD + 3) { it.toByte() }
        val frames = tunnelWsMessageFrames(TunnelFrameType.WS_BIN, 9, payload)
        assertEquals(3, frames.size)
        assertEquals(TunnelFrameType.WS_BIN, frames.first().type)
        assertEquals(TunnelFrameType.DATA, frames[1].type)
        assertEquals(TunnelFrameType.WS_END, frames.last().type)
        val decoded = TunnelFrameDecoder().feed(frames.flatMap { encodeTunnelFrame(it).asList() }.toByteArray())
        val body = decoded.dropLast(1).flatMap { it.payload.asList() }.toByteArray()
        assertEquals(payload.size, body.size)
        assertEquals(payload.toList(), body.toList())
    }

    @Test fun wsLargeMessageChunksAtPayloadLimit() {
        assertEquals(2, ByteArray(60_000).asList().chunked(TUNNEL_MAX_PAYLOAD).size)
        assertEquals(3, tunnelWsMessageFrames(TunnelFrameType.WS_TEXT, 1, ByteArray(60_000)).size)
    }

    @Test fun rejectsBadHeadersAndOversizedPayload() {
        assertFailsWith<TunnelProtocolException> {
            TunnelFrameDecoder().feed(byteArrayOf(99, 0, 0, 0, 0, 0, 0))
        }
        assertFailsWith<TunnelProtocolException> {
            encodeTunnelFrame(TunnelFrame(TunnelFrameType.PING, 0, ByteArray(0)))
        }
        assertFailsWith<TunnelProtocolException> {
            encodeTunnelFrame(TunnelFrame(TunnelFrameType.DATA, 1, ByteArray(TUNNEL_MAX_PAYLOAD + 1)))
        }
        assertFailsWith<TunnelProtocolException> {
            TunnelFrameDecoder().feed(byteArrayOf(
                TunnelFrameType.DATA.toByte(), 0, 0,
                0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
            ))
        }
    }
}
