package com.miguelvaladas.webrtcexample.stream

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.miguelvaladas.webrtcexample.data.stream.model.IceServer
import com.miguelvaladas.webrtcexample.data.stream.model.SignalingChannel
import jakarta.websocket.ClientEndpointConfig
import jakarta.websocket.CloseReason
import jakarta.websocket.Endpoint
import jakarta.websocket.EndpointConfig
import jakarta.websocket.MessageHandler
import jakarta.websocket.Session
import org.glassfish.tyrus.client.ClientManager
import org.glassfish.tyrus.client.ClientProperties
import org.json.JSONObject
import org.webrtc.*
import java.net.URI
import java.util.LinkedList
import java.util.Queue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class WebRtcClient(
    private val context: Context,
    private val localVideoRenderer: SurfaceViewRenderer
) {
    private val eglBaseContext = EglBase.create().eglBaseContext
    private val factory: PeerConnectionFactory = createPeerConnectionFactory()
    private var signalingUrl: URI? = null
    private var peerConnection: PeerConnection? = null
    private var socket: Session? = null
    private var signalingChannel: SignalingChannel? = null
    private var localVideoTrack: VideoTrack? = null
    private val gson = Gson()
    private var signalingListener: SignalingListener? = null
    private var executorService: ExecutorService? = null

    private var recipientClientId: String = String()
    private val peerConnectionFoundMap = HashMap<String, PeerConnection?>()
    private val pendingCustomIceCandidatesMap = HashMap<String, Queue<IceCandidate>>()

    init {
        localVideoRenderer.init(eglBaseContext, null)
        initLocalMediaStream()
        initializeSignalingListener()
    }

    private fun createPeerConnectionFactory(): PeerConnectionFactory {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(context)
                .createInitializationOptions()
        )
        return PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBaseContext))
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(
                    eglBaseContext,
                    true,
                    true
                )
            )
            .createPeerConnectionFactory()
    }

    private fun initLocalMediaStream() {
        val videoCapturer = createCameraCapturer(Camera1Enumerator(false))
        if (videoCapturer != null) {
            val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBaseContext)
            val videoSource = factory.createVideoSource(videoCapturer.isScreencast)
            videoCapturer.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
            videoCapturer.startCapture(1920, 1080, 30)

            localVideoTrack = factory.createVideoTrack("100", videoSource).apply {
                addSink(localVideoRenderer)
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

    private fun initializeSignalingListener() {
        signalingListener = object : SignalingListener {
            override fun onMessage(message: String) {
                Log.d(TAG, "Received message: $message")
                if (message.isNotEmpty() && message.contains("messagePayload")) {
                    val evt: AwsEvent = gson.fromJson(message, AwsEvent::class.java)
                    when {
                        evt.messageType.equals("SDP_OFFER", true) -> handleSdpOffer(evt)
                        evt.messageType.equals("ICE_CANDIDATE", true) -> handleIceCandidate(evt)
                    }
                }
            }

            override fun onOfferReceived(offer: SessionDescription) {
                peerConnection?.setRemoteDescription(CustomSdpObserver("setRemoteDesc"), offer)
            }

            override fun onAnswerReceived(answer: SessionDescription) {
                peerConnection?.setRemoteDescription(CustomSdpObserver("setRemoteDesc"), answer)
            }

            override fun onIceCandidateReceived(remoteCandidate: IceCandidate) {
                peerConnection?.addIceCandidate(remoteCandidate)
            }
        }
    }

    private fun handleSdpOffer(evt: AwsEvent) {
        val sdp = parseOfferEvent(evt)
        peerConnection?.setRemoteDescription(
            CustomSdpObserver("setRemoteDesc"),
            SessionDescription(SessionDescription.Type.OFFER, sdp)
        )
        recipientClientId = evt.senderClientId
        handlePendingIceCandidates(recipientClientId)
    }

    private fun handleIceCandidate(evt: AwsEvent) {
        val iceCandidate = parseIceCandidate(evt)
        iceCandidate?.let { addIceCandidate(evt.senderClientId, it) }
    }

    private fun addIceCandidate(clientId: String, iceCandidate: IceCandidate) {
        val peerConnection = peerConnectionFoundMap[clientId]
        if (peerConnection != null) {
            peerConnection.addIceCandidate(iceCandidate)
        } else {
            pendingCustomIceCandidatesMap.getOrPut(clientId, ::LinkedList).add(iceCandidate)
        }
    }

    private fun handlePendingIceCandidates(clientId: String) {
        pendingCustomIceCandidatesMap[clientId]?.let { queue ->
            while (queue.isNotEmpty()) {
                peerConnectionFoundMap[clientId]?.addIceCandidate(queue.poll())
            }
        }
    }

    fun setSignalingUrl(url: URI) {
        this.signalingUrl = url
    }

    fun setSignalingChannel(signalingChannel: SignalingChannel) {
        this.signalingChannel = signalingChannel
    }

    fun updateIceServers(iceServers: List<IceServer>) {
        Log.i(TAG, "updateIceServers: $iceServers")

        val rtcIceServers = iceServers.map { server ->
            PeerConnection.IceServer.builder(server.uris.joinToString(","))
                .setUsername(server.username)
                .setPassword(server.password)
                .createIceServer()
        }.toMutableList()

        rtcIceServers.add(
            PeerConnection.IceServer.builder("stun:stun.kinesisvideo.eu-central-1.amazonaws.com:443")
                .createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(rtcIceServers)
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        rtcConfig.continualGatheringPolicy =
            PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        rtcConfig.keyType = PeerConnection.KeyType.ECDSA
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED

        if (peerConnection == null) {
            createPeerConnection(rtcConfig)
        } else {
            peerConnection?.close()
            createPeerConnection(rtcConfig)
        }
    }

    private fun createPeerConnection(rtcConfig: PeerConnection.RTCConfiguration) {
        peerConnection =
            factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
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

                /// POSSIBLE ERROR
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        Log.d(TAG, "New ICE candidate: ${candidate.sdp}")
                        val awsMessage = createIceCandidateMessage(candidate)
                        sendIceCandidate(awsMessage)
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

                override fun onAddTrack(
                    receiver: RtpReceiver?,
                    streams: Array<out MediaStream>?
                ) {
                    Log.d(TAG, "Track added: ${receiver?.track()?.id()}")
                }
            })
        localVideoTrack?.let { videoTrack ->
            peerConnection?.addTrack(videoTrack)
        }
    }

    private fun createIceCandidateMessage(customIceCandidate: IceCandidate): Message {
        return Message(
            action = "ICE_CANDIDATE",
            recipientClientId = String(),
            senderClientId = String(),
            messagePayload = String(
                Base64.encode(
                    CustomIceCandidate(
                        customIceCandidate.sdp,
                        customIceCandidate.sdpMid,
                        customIceCandidate.sdpMLineIndex
                    ).toJson().toByteArray(),
                    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                )
            ),
        )
    }

    private fun Any.toJson(): String {
        return Gson().toJson(this)
    }

    fun startConnection() {
        executorService = Executors.newFixedThreadPool(10)
        signalingUrl?.let {
            connectWebSocket(it)
        } ?: throw IllegalStateException("Signaling URL must be set before starting connection.")
    }

    fun closeConnection() {
        executorService?.submit {
            peerConnection?.close()
            socket?.close()
            executorService!!.shutdown()
        }
    }

    private fun connectWebSocket(signalingUrl: URI) {
        executorService?.submit {
            try {
                val cec = ClientEndpointConfig.Builder.create().build()
                val clientManager = ClientManager.createClient()
                clientManager.properties[ClientProperties.LOG_HTTP_UPGRADE] = true
                signalingUrl.let {
                    socket = clientManager.connectToServer(endpoint, cec, it)
                }
            } catch (e: Exception) {
                Log.e(TAG, "WebSocket connection failed: ${e.message}")
            }
        }
    }

    private fun createOffer() {
        peerConnection?.createOffer(CustomSdpObserver("createOffer"), MediaConstraints())
    }

    private fun sendOffer(offer: SessionDescription) {
        val offerPayload =
            "{\"type\":\"offer\",\"sdp\":\"${offer.description.replace("\r\n", "\\r\\n")}\"}"
        val encodedString =
            Base64.encodeToString(offerPayload.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        val message = Message("SDP_OFFER", "", "", encodedString)

        val jsonMessage = gson.toJson(message)
        executorService?.submit {
            socket?.basicRemote?.sendText(jsonMessage)
        }
    }

    private fun sendAnswer(answer: SessionDescription) {
        val json = JSONObject().apply {
            put("type", "answer")
            put("sdp", answer.description)
        }
        socket?.basicRemote?.sendText(json.toString())
    }

    private fun sendIceCandidate(candidate: Message) {
        executorService?.submit {
            if (candidate.action.equals("ICE_CANDIDATE", ignoreCase = true)) {
                val jsonMessage = gson.toJson(candidate)
                Log.d(TAG, "Sending JSON Message= $jsonMessage")
                socket?.basicRemote?.sendText(jsonMessage)
                Log.d(TAG, "Sent Ice candidate message")
            }
        }
    }

    private val endpoint: Endpoint by lazy {
        object : Endpoint() {
            override fun onOpen(session: Session, config: EndpointConfig?) {
                Log.i(TAG, "onOpen: isSessionOpen: ${session.isOpen}")
                Log.i(TAG, "onOpen: $session")

                session.addMessageHandler(MessageHandler.Whole<Any> { message ->
                    Log.i(TAG, "onOpen - received message: $message ")
                    if (message is String)
                        handleSignalingMessage(message)
                })
                createOffer()
            }

            override fun onClose(session: Session, closeReason: CloseReason) {
                super.onClose(session, closeReason)
                Log.d(
                    TAG,
                    "onClose: Session ${session.requestURI}  closed with reason ${closeReason.toJson()}"
                )
            }

            override fun onError(session: Session, thr: Throwable) {
                super.onError(session, thr)
                Log.w(TAG, "onError: ${thr.printStackTrace()}")
            }
        }
    }

    private fun handleSignalingMessage(message: String) {
        Log.i(TAG, "handleSignalingMessage: $message")
        val jsonObject = JSONObject(message)
        when (jsonObject.getString("type")) {
            "offer" -> signalingListener?.onOfferReceived(
                SessionDescription(
                    SessionDescription.Type.OFFER,
                    jsonObject.getString("sdp")
                )
            )

            "answer" -> signalingListener?.onAnswerReceived(
                SessionDescription(
                    SessionDescription.Type.ANSWER,
                    jsonObject.getString("sdp")
                )
            )

            "candidate" -> {
                val candidate = IceCandidate(
                    jsonObject.getString("sdpMid"),
                    jsonObject.getInt("sdpMLineIndex"),
                    jsonObject.getString("candidate")
                )
                signalingListener?.onIceCandidateReceived(candidate)
            }
        }
    }

    inner class CustomSdpObserver(private val operation: String) : SdpObserver {
        override fun onCreateSuccess(sessionDescription: SessionDescription) {
            Log.d(TAG, "$operation: onCreateSuccess")
            peerConnection?.setLocalDescription(this, sessionDescription)
            handlePendingIceCandidates(recipientClientId)
            when (operation) {
                "createOffer" -> sendOffer(sessionDescription)
                "createAnswer" -> sendAnswer(sessionDescription)
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
        fun onMessage(message: String)

        fun onOfferReceived(offer: SessionDescription)

        fun onAnswerReceived(answer: SessionDescription)

        fun onIceCandidateReceived(remoteCandidate: IceCandidate)
    }

    companion object {
        const val TAG = "WebRtcClient"
    }
}
