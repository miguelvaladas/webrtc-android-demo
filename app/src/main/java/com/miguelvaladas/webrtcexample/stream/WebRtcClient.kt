package com.miguelvaladas.webrtcexample.stream

import android.content.Context
import android.util.Log
import com.miguelvaladas.webrtcexample.data.stream.model.IceServer
import com.miguelvaladas.webrtcexample.data.stream.model.SignalingChannel
import org.glassfish.tyrus.client.ClientManager
import org.json.JSONObject
import org.webrtc.*
import java.net.URI
import java.util.concurrent.Executors
import javax.websocket.Endpoint
import javax.websocket.EndpointConfig
import javax.websocket.MessageHandler
import javax.websocket.Session

class WebRtcClient(private val context: Context, private val localVideoRenderer: SurfaceViewRenderer) {
    private val eglBaseContext = EglBase.create().eglBaseContext
    private val factory: PeerConnectionFactory = createPeerConnectionFactory()
    private var signalingUrl: URI? = null
    private var localStream: MediaStream? = null
    private var peerConnection: PeerConnection? = null
    private var socket: Session? = null
    private var signalingChannel: SignalingChannel? = null
    private var signalingListener: SignalingListener? = null

    init {
        localVideoRenderer.init(eglBaseContext, null)
        initLocalMediaStream()
    }

    private fun createPeerConnectionFactory(): PeerConnectionFactory {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        val options = PeerConnectionFactory.Options()
        val videoEncoderFactory = DefaultVideoEncoderFactory(eglBaseContext, true, true)
        val videoDecoderFactory = DefaultVideoDecoderFactory(eglBaseContext)
        return PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(videoEncoderFactory)
            .setVideoDecoderFactory(videoDecoderFactory)
            .createPeerConnectionFactory()
    }

    private fun initLocalMediaStream() {
        val videoCapturer = createCameraCapturer(Camera1Enumerator(false))
        if (videoCapturer != null) {
            val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBaseContext)
            val videoSource = factory.createVideoSource(videoCapturer.isScreencast)
            videoCapturer.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
            videoCapturer.startCapture(1280, 720, 30)

            val videoTrack = factory.createVideoTrack("100", videoSource)
            localVideoRenderer?.let { renderer ->
                videoTrack.addSink(renderer)
            }

            localStream = factory.createLocalMediaStream("KvsLocalStream").apply {
                if (!addTrack(videoTrack)) {
                    Log.e(TAG, "Add video track failed")
                }
            }
        } else {
            Log.e(TAG, "Failed to create video capturer")
        }
    }

    private fun createCameraCapturer(enumerator: CameraEnumerator): VideoCapturer? {
        return enumerator.deviceNames
            .asSequence()
            .mapNotNull { enumerator.createCapturer(it, null) }
            .firstOrNull()
    }

    fun setSignalingUrl(url: URI) {
        this.signalingUrl = url
    }

    fun setSignalingChannel(signalingChannel: com.miguelvaladas.webrtcexample.data.stream.model.SignalingChannel) {
        this.signalingChannel = signalingChannel
        updateIceServers(signalingChannel.iceServers)
    }
/*
    fun setLocalVideoRenderer(renderer: SurfaceViewRenderer?) {
        localVideoRenderer = renderer
        localVideoRenderer?.init(eglBaseContext, null)
        localVideoRenderer?.setMirror(true)
    }

 */

    fun startConnection() {
        createPeerConnection()
        signalingListener = object : SignalingListener {
            override fun onOfferReceived(offer: SessionDescription) {
                peerConnection?.setRemoteDescription(CustomSdpObserver("setRemoteDescriptionOffer"), offer)
                createAnswer()
            }

            override fun onAnswerReceived(answer: SessionDescription) {
                peerConnection?.setRemoteDescription(CustomSdpObserver("setRemoteDescriptionAnswer"), answer)
            }

            override fun onIceCandidateReceived(candidate: IceCandidate) {
                peerConnection?.addIceCandidate(candidate)
            }
        }
        signalingUrl?.let {
            connectWebSocket(it)
            createOffer()
        } ?: throw IllegalStateException("Signaling URL must be set before starting connection.")
    }

    fun closeConnection() {
        peerConnection?.close()
        socket?.close()
        localStream = null
    }

    private fun updateIceServers(iceServers: List<IceServer>) {
        val rtcIceServers = iceServers.map { server ->
            PeerConnection.IceServer.builder(server.uris.joinToString(","))
                .setUsername(server.username)
                .setPassword(server.password)
                .createIceServer()
        }
        createPeerConnection(PeerConnection.RTCConfiguration(rtcIceServers))
    }

    private fun connectWebSocket(signalingUrl: URI) {
        Executors.newSingleThreadExecutor().submit {
            try {
                val clientManager = ClientManager.createClient()
                socket = clientManager.connectToServer(endpoint, signalingUrl)
            } catch (e: Exception) {
                Log.e(TAG, "WebSocket connection failed: $e")
            }
        }
    }

    private fun createPeerConnection(rtcConfig: PeerConnection.RTCConfiguration? = null) {
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(signalingState: PeerConnection.SignalingState?) {
                Log.d(TAG, "Signaling state changed: $signalingState")
            }

            override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE connection state changed: $iceConnectionState")
            }

            override fun onIceConnectionReceivingChange(isReceiving: Boolean) {
                Log.d(TAG, "ICE connection receiving change: $isReceiving")
            }

            override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "ICE gathering state changed: $iceGatheringState")
            }

            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    Log.d(TAG, "New ICE candidate: ${candidate.sdp}")
                    sendIceCandidate(candidate)
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                Log.d(TAG, "ICE candidates removed")
            }

            override fun onAddStream(stream: MediaStream?) {
                Log.d(TAG, "Stream added: ${stream?.id}")
                stream?.videoTracks?.firstOrNull()?.addSink(localVideoRenderer)
            }

            override fun onRemoveStream(stream: MediaStream?) {
                Log.d(TAG, "Stream removed: ${stream?.id}")
            }

            override fun onDataChannel(dataChannel: DataChannel?) {
                Log.d(TAG, "Data channel changed: ${dataChannel?.label()}")
            }

            override fun onRenegotiationNeeded() {
                Log.d(TAG, "Renegotiation needed")
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                Log.d(TAG, "Track added: ${receiver?.track()?.id()}")
            }
        }

        peerConnection = factory.createPeerConnection(
            rtcConfig ?: PeerConnection.RTCConfiguration(emptyList()),
            observer
        )
        localStream?.let { stream ->
            peerConnection?.addStream(stream)
        }
    }

    private fun createOffer() {
        peerConnection?.createOffer(CustomSdpObserver("createOffer"), MediaConstraints())
    }

    private fun createAnswer() {
        peerConnection?.createAnswer(CustomSdpObserver("createAnswer"), MediaConstraints())
    }

    private fun sendOffer(offer: SessionDescription) {
        val json = JSONObject().apply {
            put("type", "offer")
            put("sdp", offer.description)
        }
        socket?.basicRemote?.sendText(json.toString())
    }

    private fun sendAnswer(answer: SessionDescription) {
        val json = JSONObject().apply {
            put("type", "answer")
            put("sdp", answer.description)
        }
        socket?.basicRemote?.sendText(json.toString())
    }

    private fun sendIceCandidate(candidate: IceCandidate) {
        val json = JSONObject().apply {
            put("type", "candidate")
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
            put("candidate", candidate.sdp)
        }
        socket?.basicRemote?.sendText(json.toString())
    }

    companion object {
        const val TAG = "WebRtcClient"
    }

    private val endpoint: Endpoint by lazy {
        object : Endpoint() {
            override fun onOpen(session: Session, config: EndpointConfig?) {
                session.addMessageHandler(MessageHandler.Whole<String> { message ->
                    handleSignalingMessage(message)
                })
            }
        }
    }

    private fun handleSignalingMessage(message: String) {
        val jsonObject = JSONObject(message)
        when (jsonObject.getString("type")) {
            "offer" -> handleOfferMessage(jsonObject)
            "answer" -> handleAnswerMessage(jsonObject)
            "candidate" -> handleIceCandidateMessage(jsonObject)
        }
    }

    private fun handleOfferMessage(jsonObject: JSONObject) {
        val sdp = jsonObject.getString("sdp")
        val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, sdp)
        signalingListener?.onOfferReceived(sessionDescription)
    }

    private fun handleAnswerMessage(jsonObject: JSONObject) {
        val sdp = jsonObject.getString("sdp")
        val sessionDescription = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        signalingListener?.onAnswerReceived(sessionDescription)
    }

    private fun handleIceCandidateMessage(jsonObject: JSONObject) {
        val sdpMid = jsonObject.getString("sdpMid")
        val sdpMLineIndex = jsonObject.getInt("sdpMLineIndex")
        val candidate = jsonObject.getString("candidate")
        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        signalingListener?.onIceCandidateReceived(iceCandidate)
    }

    inner class CustomSdpObserver(private val operation: String) : SdpObserver {
        override fun onCreateSuccess(sessionDescription: SessionDescription) {
            Log.d(TAG, "$operation: onCreateSuccess")
            peerConnection?.setLocalDescription(this, sessionDescription)
            if (operation == "createOffer") {
                sendOffer(sessionDescription)
            } else if (operation == "createAnswer") {
                sendAnswer(sessionDescription)
            }
        }

        override fun onSetSuccess() {
            Log.d(TAG, "$operation: onSetSuccess")
        }

        override fun onCreateFailure(error: String?) {
            Log.e(TAG, "$operation: onCreateFailure - $error")
        }

        override fun onSetFailure(error: String?) {
            Log.e(TAG, "$operation: onSetFailure - $error")
        }
    }

    interface SignalingListener {
        fun onOfferReceived(offer: SessionDescription)
        fun onAnswerReceived(answer: SessionDescription)
        fun onIceCandidateReceived(candidate: IceCandidate)
    }
}
