package com.miguelvaladas.webrtcexample.stream

data class CustomIceCandidate(
    val candidate: String,
    val sdpMid: String,
    val sdpMLineIndex: Int
)