package com.miguelvaladas.webrtcexample.data.stream.model

data class Stream(
    val streamType: String,
    val signalingChannel: SignalingChannel
)

data class SignalingChannel(
    val name: String,
    val arn: String,
    val region: String,
    val masterEndpoint: String,
    val viewerEndpoint: String,
    val httpsEndpoint: String,
    val iceServers: List<IceServer>
)

data class IceServer(
    val username: String,
    val password: String,
    val ttl: Int,
    val uris: List<String>
)
