package com.miguelvaladas.webrtcexample.stream


data class AwsMessage(
    var action: String,
    var recipientClientId: String,
    var senderClientId: String,
    var messagePayload: String
)