package com.miguelvaladas.webrtcexample.stream

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.LinkedList
import java.util.Locale
import java.util.Queue
import java.util.concurrent.Executors
import javax.websocket.MessageHandler

class SignalingListener {

    private val TAG = "CustomMessageHandler"
    private val gson = Gson()
    private val kinesisRTObject = KinesisRTCObject

    val messageHandler: MessageHandler = object : MessageHandler.Whole<String> {
        override fun onMessage(message: String) {
            Log.d(TAG, "Received message: $message")

            if (message.isNotEmpty() && message.contains("messagePayload")) {
                val evt: AwsEvent = gson.fromJson(message, AwsEvent::class.java)
                if (evt.messagePayload.isNotEmpty()) {
                    if (evt.messageType.equals("SDP_OFFER", true)) {
                        Log.d(TAG, "Offer received: SenderClientId=" + evt.senderClientId)
                        val decode: ByteArray = Base64.decode(evt.messagePayload, 0)
                        Log.d(TAG, String(decode))
                        onSdpOffer(evt)
                    }
                    if (evt.messageType.equals("ICE_CANDIDATE", true)) {
                        Log.d(TAG, "Ice Candidate received: SenderClientId=" + evt.senderClientId)
                        val decode: ByteArray = Base64.decode(evt.messagePayload, 0)
                        Log.d(TAG, String(decode))
                        onIceCandidate(evt)
                    }
                }
            }
        }
    }

    fun onSdpOffer(event: AwsEvent) {
        Log.d(TAG, "Received SDP Offer: Setting Remote Description ")
        val sdp = parseOfferEvent(event)
        kinesisRTObject.localPeer!!.setRemoteDescription(
            KinesisVideoSdpObserver(),
            SessionDescription(SessionDescription.Type.OFFER, sdp)
        )
        kinesisRTObject.recipientClientId = event.senderClientId
        Log.d(
            TAG,
            "Received SDP offer for client ID: $kinesisRTObject.recipientClientId.Creating answer"
        )
        createSdpAnswer()
    }

    fun onIceCandidate(event: AwsEvent) {
        Log.d(TAG, "Received IceCandidate from remote ")
        val iceCandidate = parseIceCandidate(event)
        iceCandidate?.let { checkAndAddIceCandidate(event, it) }
            ?: Log.e(TAG, "Invalid Ice candidate")
    }

    fun onError(event: AwsEvent) {
        Log.e(TAG, "Received error message: $event")
    }

    fun onException(e: Exception) {
        Log.e(TAG, "Signaling client returned exception " + e.message)
    }


    private fun createSdpAnswer() {
        kinesisRTObject.localPeer!!.createAnswer(object : KinesisVideoSdpObserver() {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                Log.d(TAG, "Creating answer : success")
                super.onCreateSuccess(sessionDescription)
                kinesisRTObject.localPeer!!.setLocalDescription(
                    KinesisVideoSdpObserver(),
                    sessionDescription
                )
                val answer =
                    createAnswerMessage(sessionDescription, kinesisRTObject.recipientClientId)
                sendSdpAnswer(answer)
                kinesisRTObject.peerConnectionFoundMap[kinesisRTObject.recipientClientId] =
                    kinesisRTObject.localPeer
                handlePendingIceCandidates(kinesisRTObject.recipientClientId)
            }
        }, MediaConstraints())
    }

    private fun sendSdpAnswer(answer: AwsMessage) {
        Executors.newFixedThreadPool(4).apply {
            submit {
                if (answer.action.equals("SDP_ANSWER", ignoreCase = true)) {
                    Log.d(
                        TAG,
                        "Answer sent " + String(
                            Base64.decode(
                                answer.messagePayload.toByteArray(),
                                Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE
                            )
                        )
                    )
                    val jsonMessage = gson.toJson(answer)
                    Log.d(TAG, "Sending JSON Message= $jsonMessage")
                    KinesisRTCObject.session.basicRemote.sendText(jsonMessage)
                    Log.d(TAG, "Sent JSON Message= $jsonMessage")
                }
            }
        }
    }

    private fun handlePendingIceCandidates(clientId: String?) {
        Log.d(
            TAG,
            "Pending ice candidates found? " + kinesisRTObject.pendingIceCandidatesMap[clientId]
        )
        val pendingIceCandidatesQueueByClientId = kinesisRTObject.pendingIceCandidatesMap[clientId]
        while (pendingIceCandidatesQueueByClientId != null && !pendingIceCandidatesQueueByClientId.isEmpty()) {
            val iceCandidate = pendingIceCandidatesQueueByClientId.peek()
            val peer = kinesisRTObject.peerConnectionFoundMap[clientId]
            val addIce = peer!!.addIceCandidate(iceCandidate)
            Log.d(
                TAG,
                "Added ice candidate after SDP exchange " + iceCandidate + " " + if (addIce) "Successfully" else "Failed"
            )
            pendingIceCandidatesQueueByClientId.remove()
        }
        kinesisRTObject.pendingIceCandidatesMap.remove(clientId)
    }

    private fun checkAndAddIceCandidate(message: AwsEvent, iceCandidate: IceCandidate) {
        if (!kinesisRTObject.peerConnectionFoundMap.containsKey(message.senderClientId)) {
            Log.d(
                TAG,
                "SDP exchange is not complete. Ice candidate $iceCandidate + added to pending queue"
            )

            if (kinesisRTObject.pendingIceCandidatesMap.containsKey(message.senderClientId)) {
                val pendingIceCandidatesQueueByClientId =
                    kinesisRTObject.pendingIceCandidatesMap[message.senderClientId]!!
                pendingIceCandidatesQueueByClientId.add(iceCandidate)
                kinesisRTObject.pendingIceCandidatesMap[message.senderClientId] =
                    pendingIceCandidatesQueueByClientId
            } else {
                val pendingIceCandidatesQueueByClientId: Queue<IceCandidate> = LinkedList()
                pendingIceCandidatesQueueByClientId.add(iceCandidate)
                kinesisRTObject.pendingIceCandidatesMap[message.senderClientId] =
                    pendingIceCandidatesQueueByClientId
            }
        } else {
            Log.d(
                TAG,
                "Peer connection found already, connections started ${kinesisRTObject.peerConnectionFoundMap.size}"
            )
            val peer = kinesisRTObject.peerConnectionFoundMap[message.senderClientId]
            val addIce = peer!!.addIceCandidate(iceCandidate)
            val result = if (addIce) "Successfully" else "Failed"
            Log.d(TAG, "Added ice candidate $iceCandidate $result")
        }
    }


    open class KinesisVideoSdpObserver : SdpObserver {
        protected val TAG = KinesisVideoSdpObserver::class.java.simpleName

        override fun onCreateSuccess(sessionDescription: SessionDescription) {
            Log.d(TAG, "onCreateSuccess(): SDP=" + sessionDescription.description)
        }

        override fun onSetSuccess() {
            Log.d(TAG, "onSetSuccess(): SDP")
        }

        override fun onCreateFailure(error: String) {
            Log.e(TAG, "onCreateFailure(): Error=$error")
        }

        override fun onSetFailure(error: String) {
            Log.e(TAG, "onSetFailure(): Error=$error")
        }
    }
}