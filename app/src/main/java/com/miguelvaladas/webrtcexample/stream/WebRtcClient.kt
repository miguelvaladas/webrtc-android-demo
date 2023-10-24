package com.miguelvaladas.webrtcexample.stream

import android.content.Context
import android.util.Log
import com.miguelvaladas.webrtcexample.data.stream.model.IceServer
import com.miguelvaladas.webrtcexample.data.stream.model.SignalingChannel
import org.json.JSONObject
import org.webrtc.*
import java.net.URI
import java.util.concurrent.Executors
import javax.websocket.CloseReason
import javax.websocket.ContainerProvider
import javax.websocket.Endpoint
import javax.websocket.EndpointConfig
import javax.websocket.MessageHandler
import javax.websocket.Session


class WebRtcClient(private val context: Context) {
    private val eglBaseContext: EglBase.Context
    private val factory: PeerConnectionFactory
    private var signalingUrl: URI? = null
    private var localStream: MediaStream? = null
    private var peerConnection: PeerConnection? = null
    private var socket: Session? = null
    private var signalingChannel: SignalingChannel? = null
    private var localVideoRenderer: SurfaceViewRenderer? = null

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )

        eglBaseContext = EglBase.create().eglBaseContext
        val options = PeerConnectionFactory.Options()
        val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(eglBaseContext, true, true)
        val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(defaultVideoEncoderFactory)
            .setVideoDecoderFactory(defaultVideoDecoderFactory)
            .createPeerConnectionFactory()
    }

    fun setSignalingUrl(url: URI) {
        this.signalingUrl = url
    }

    fun setSignalingChannel(signalingChannel: SignalingChannel) {
        this.signalingChannel = signalingChannel
        updateIceServers(signalingChannel.iceServers)
    }

    fun setLocalVideoRenderer(renderer: SurfaceViewRenderer) {
        localVideoRenderer = renderer
        localVideoRenderer?.init(eglBaseContext, null)
        localVideoRenderer?.setMirror(true)
    }

    fun startConnection() {
        startLocalMedia()
        createPeerConnection()
        signalingUrl?.let {
            connectWebSocket(it)
            createOffer()
        } ?: throw IllegalStateException("Signaling URL must be set before starting connection.")
    }

    fun closeConnection() {
        Executors.newFixedThreadPool(2).apply {
            submit {
                peerConnection?.close()
                socket?.close()
            }
        }
    }

    private fun updateIceServers(iceServers: List<IceServer>) {
        val rtcIceServers = mutableListOf<PeerConnection.IceServer>()
        iceServers.forEach { server ->
            server.uris.forEach { uri ->
                rtcIceServers.add(
                    PeerConnection.IceServer.builder(uri)
                        .setUsername(server.username)
                        .setPassword(server.password)
                        .createIceServer()
                )
            }
        }
        val rtcConfig = PeerConnection.RTCConfiguration(rtcIceServers)
        createPeerConnection(rtcConfig)
    }

    private fun connectWebSocket(signalingUrl: URI) {
        Executors.newFixedThreadPool(2).apply {
            submit {
                val container = ContainerProvider.getWebSocketContainer()
                socket = container.connectToServer(object : Endpoint() {
                    override fun onOpen(session: Session, config: EndpointConfig) {
                        session.addMessageHandler(MessageHandler.Whole<Any> { message ->
                            when (message) {
                                is String -> onSignalingMessageReceived(message)
                                is Boolean -> Log.i(TAG, "Received Boolean message: $message")
                                else -> Log.w(
                                    TAG,
                                    "Unhandled message type: ${message?.javaClass?.name}"
                                )
                            }
                        })
                    }

                    override fun onClose(session: Session, closeReason: CloseReason) {
                        Log.d("WebRtcClient", "WebSocket closed: ${closeReason.reasonPhrase}")
                    }

                    override fun onError(session: Session?, t: Throwable?) {
                        Log.e("WebRtcClient", "WebSocket error", t)
                    }
                }, signalingUrl)
            }
        }
    }

    private fun onSignalingMessageReceived(message: String) {
        Log.i(TAG, "receivedMessage: $message")

        val jsonObject = JSONObject(message)

        when (jsonObject.getString("type")) {
            "offer" -> {
                val offer = SessionDescription(
                    SessionDescription.Type.OFFER,
                    jsonObject.getString("sdp")
                )
                peerConnection?.setRemoteDescription(
                    SimpleSdpObserver(),
                    offer
                )
                peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(sessionDescription: SessionDescription) {
                        peerConnection?.setLocalDescription(SimpleSdpObserver(), sessionDescription)
                        sendSdpAnswer(sessionDescription)
                    }

                    override fun onSetSuccess() {}

                    override fun onCreateFailure(error: String?) {
                        Log.e("WebRtcClient", "Error creating answer: $error")
                    }

                    override fun onSetFailure(error: String?) {
                        Log.e("WebRtcClient", "Error setting answer: $error")
                    }
                }, MediaConstraints())
            }

            "answer" -> {
                val answer = SessionDescription(
                    SessionDescription.Type.ANSWER,
                    jsonObject.getString("sdp")
                )
                peerConnection?.setRemoteDescription(SimpleSdpObserver(), answer)
            }

            "ice-candidate" -> {
                val iceCandidate = IceCandidate(
                    jsonObject.getString("sdpMid"),
                    jsonObject.getInt("sdpMLineIndex"),
                    jsonObject.getString("candidate")
                )
                peerConnection?.addIceCandidate(iceCandidate)
            }
        }
    }

    private fun startLocalMedia() {
        val videoCapturer = createCameraCapturer(Camera2Enumerator(context))

        val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBaseContext)
        val videoSource = factory.createVideoSource(videoCapturer.isScreencast)

        videoCapturer.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
        videoCapturer.startCapture(1280, 720, 30)

        val videoTrack = factory.createVideoTrack("100", videoSource)
        videoTrack.addSink(localVideoRenderer)

        localStream = factory.createLocalMediaStream("1")
        localStream?.addTrack(videoTrack)

        peerConnection?.addStream(localStream)
    }

    private fun createCameraCapturer(enumerator: CameraEnumerator): VideoCapturer {
        val deviceNames = enumerator.deviceNames

        deviceNames.forEach { deviceName ->
            if (enumerator.isFrontFacing(deviceName)) {
                val videoCapturer = enumerator.createCapturer(deviceName, null)
                videoCapturer?.let { return it }
            }
        }

        deviceNames.forEach { deviceName ->
            if (!enumerator.isFrontFacing(deviceName)) {
                val videoCapturer = enumerator.createCapturer(deviceName, null)
                videoCapturer?.let { return it }
            }
        }

        throw IllegalStateException("No cameras available.")
    }

    private fun createPeerConnection(rtcConfig: PeerConnection.RTCConfiguration? = null) {
        val defaultIceServers = mutableListOf<PeerConnection.IceServer>()
        defaultIceServers.add(
            PeerConnection.IceServer.builder("stun:stun.kinesisvideo.eu-central-1.amazonaws.com:443")
                .createIceServer()
        )

        signalingChannel?.iceServers?.forEach { iceServer ->
            defaultIceServers.add(
                PeerConnection.IceServer.builder(iceServer.uris.joinToString(","))
                    .setUsername(iceServer.username.trim())
                    .setPassword(iceServer.password.trim())
                    .createIceServer()
            )
        }

        val config = rtcConfig ?: PeerConnection.RTCConfiguration(defaultIceServers)
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(iceCandidate: IceCandidate?) {
                val json = JSONObject()
                json.put("type", "ice-candidate")
                json.put("sdpMid", iceCandidate?.sdpMid)
                json.put("sdpMLineIndex", iceCandidate?.sdpMLineIndex)
                json.put("candidate", iceCandidate?.sdp)
                socket?.basicRemote?.sendText(json.toString())
            }

            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        }
        peerConnection = factory.createPeerConnection(config, observer)
        localStream?.let {
            peerConnection?.addStream(it)
        }
    }

    private fun createOffer() {
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                peerConnection?.setLocalDescription(SimpleSdpObserver(), sessionDescription)
                sendSdpOffer(sessionDescription)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e("WebRtcClient", "Error creating offer: $error")
            }

            override fun onSetFailure(error: String?) {
                Log.e("WebRtcClient", "Error setting offer: $error")
            }
        }, MediaConstraints())
    }

    private fun sendSdpOffer(sessionDescription: SessionDescription) {
        val json = JSONObject()
        json.put("type", "offer")
        json.put("sdp", sessionDescription.description)
        socket?.basicRemote?.sendText(json.toString())
    }

    private fun sendSdpAnswer(sessionDescription: SessionDescription) {
        val json = JSONObject()
        json.put("type", "answer")
        json.put("sdp", sessionDescription.description)
        socket?.basicRemote?.sendText(json.toString())
    }

    private class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }

    companion object {
        const val TAG = "WebRTCClient"
    }
}