package com.miguelvaladas.webrtcexample.stream

import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import java.util.ArrayList
import java.util.HashMap
import java.util.Queue
import javax.websocket.Session

object KinesisRTCObject {

    var recipientClientId: String = String()
    var localPeer: PeerConnection? = null
    val peerIceServers = ArrayList<PeerConnection.IceServer>()
    val peerConnectionFoundMap = HashMap<String?, PeerConnection?>()
    val pendingIceCandidatesMap = HashMap<String?, Queue<IceCandidate>>()
    lateinit var session: Session

}