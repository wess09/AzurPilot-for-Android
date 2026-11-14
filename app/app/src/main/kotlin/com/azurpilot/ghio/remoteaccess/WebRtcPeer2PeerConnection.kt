package com.azurpilot.ghio.remoteaccess

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android WebRTC 实现：AFA 主动创建 `tunnel` DataChannel，按 non-trickle SDP 信令连接。
 *
 * Android WebRTC implementation: AFA creates the `tunnel` DataChannel and connects using
 * non-trickle SDP signaling.
 */
class WebRtcPeer2PeerConnectionFactory(
    private val context: Context,
    private val signaling: Peer2PeerSignaling,
) : Peer2PeerConnectionFactory {
    override suspend fun connect(entry: String, deviceId: String, stunHost: String): Peer2PeerConnection {
        val connection = WebRtcPeer2PeerConnection(context, signaling, entry, deviceId, stunHost)
        return try {
            connection.open()
        } catch (cancellation: CancellationException) {
            connection.close()
            throw cancellation
        } catch (failure: Throwable) {
            connection.close()
            throw failure
        }
    }
}

private class WebRtcPeer2PeerConnection(
    private val context: Context,
    private val signaling: Peer2PeerSignaling,
    private val entry: String,
    private val deviceId: String,
    private val stunHost: String,
) : Peer2PeerConnection {
    private val closed = AtomicBoolean(false)
    private val decoder = TunnelFrameDecoder()
    private val pendingFrames = ArrayList<TunnelFrame>()
    private var frameListener: ((TunnelFrame) -> Unit)? = null
    private var closeListener: (() -> Unit)? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var channel: DataChannel? = null
    private var server: LocalTunnelServer? = null
    private val channelOpen = CompletableDeferred<Unit>()
    private val iceGathering = CompletableDeferred<Unit>()

    override val localUrl: String
        get() = server?.url ?: throw IllegalStateException("P2P tunnel is not open")

    suspend fun open(): Peer2PeerConnection {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions(),
        )
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val configuration = PeerConnection.RTCConfiguration(iceServers(stunHost))
        val connection = factory?.createPeerConnection(configuration, PeerObserver())
            ?: throw IOException("PeerConnectionFactory unavailable")
        peer = connection
        val init = DataChannel.Init().apply { ordered = true }
        channel = connection.createDataChannel("tunnel", init)?.also {
            it.registerObserver(DataObserver())
        } ?: throw IOException("tunnel DataChannel unavailable")

        val offer = createOffer(connection)
        val localDescriptionSet = CompletableDeferred<Unit>()
        connection.setLocalDescription(SetObserver(localDescriptionSet), offer)
        localDescriptionSet.await()
        withTimeout(15_000) { iceGathering.await() }
        val completeOffer = connection.localDescription?.description
            ?: throw IOException("local SDP unavailable")
        val signalId = signaling.offer(entry, deviceId, completeOffer)
        val answer = signaling.answer(entry, deviceId, signalId)
        val remoteDescriptionSet = CompletableDeferred<Unit>()
        connection.setRemoteDescription(
            SetObserver(remoteDescriptionSet),
            SessionDescription(SessionDescription.Type.ANSWER, answer),
        )
        remoteDescriptionSet.await()
        withTimeout(25_000) { channelOpen.await() }
        server = LocalTunnelServer(this).also { it.startTunnel() }
        return this
    }

    override fun send(frame: TunnelFrame): Boolean {
        val current = channel ?: return false
        if (closed.get() || current.state() != DataChannel.State.OPEN) return false
        return runCatching {
            current.send(DataChannel.Buffer(ByteBuffer.wrap(encodeTunnelFrame(frame)), true))
        }.getOrDefault(false)
    }

    override fun setFrameListener(listener: ((TunnelFrame) -> Unit)?) {
        synchronized(pendingFrames) {
            frameListener = listener
            if (listener != null) {
                pendingFrames.forEach(listener)
                pendingFrames.clear()
            }
        }
    }

    override fun setCloseListener(listener: (() -> Unit)?) {
        closeListener = listener
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server?.stop() }
        channel?.close()
        channel?.unregisterObserver()
        channel?.dispose()
        peer?.close()
        peer?.dispose()
        factory?.dispose()
        channel = null
        peer = null
        factory = null
        closeListener?.invoke()
    }

    private suspend fun createOffer(connection: PeerConnection): SessionDescription {
        val result = CompletableDeferred<SessionDescription>()
        connection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                result.complete(description)
            }

            override fun onSetSuccess() = Unit
            override fun onCreateFailure(message: String) {
                result.completeExceptionally(IOException(message))
            }

            override fun onSetFailure(message: String) {
                result.completeExceptionally(IOException(message))
            }
        }, MediaConstraints())
        return result.await()
    }

    private fun dispatch(frame: TunnelFrame) {
        synchronized(pendingFrames) {
            val listener = frameListener
            if (listener == null) pendingFrames.add(frame) else listener(frame)
        }
    }

    private inner class DataObserver : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit

        override fun onStateChange() {
            when (channel?.state()) {
                DataChannel.State.OPEN -> channelOpen.complete(Unit)
                DataChannel.State.CLOSED -> close()
                else -> Unit
            }
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            runCatching { decoder.feed(bytes) }
                .onFailure { close() }
                .getOrDefault(emptyList())
                .forEach(::dispatch)
        }
    }

    private inner class PeerObserver : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) iceGathering.complete(Unit)
        }

        override fun onIceCandidate(candidate: IceCandidate) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(dataChannel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            if (state == PeerConnection.PeerConnectionState.FAILED ||
                state == PeerConnection.PeerConnectionState.CLOSED
            ) close()
        }
    }

    private class SetObserver(private val result: CompletableDeferred<Unit>) : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() {
            result.complete(Unit)
        }

        override fun onCreateFailure(message: String) {
            result.completeExceptionally(IOException(message))
        }

        override fun onSetFailure(message: String) {
            result.completeExceptionally(IOException(message))
        }
    }

    companion object {
        private fun iceServers(stunHost: String): List<PeerConnection.IceServer> = buildList {
            if (stunHost.isNotBlank()) {
                add(PeerConnection.IceServer.builder("stun:$stunHost:3478").createIceServer())
                add(PeerConnection.IceServer.builder("stun:$stunHost:34788").createIceServer())
            }
            add(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer())
            add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        }
    }
}
